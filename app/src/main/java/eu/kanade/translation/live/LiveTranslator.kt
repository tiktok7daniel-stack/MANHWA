package eu.kanade.translation.live

import mihon.app.di.appGraph
import android.util.LruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.HeavyWork
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.model.Translation
import eu.kanade.translation.collection.TranslationCollection
import eu.kanade.translation.learning.LessonStore
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.team.TeamStore
import eu.kanade.translation.translator.TeamTranslator
import java.util.Collections
import java.util.IdentityHashMap
import eu.kanade.translation.data.PageImage
import eu.kanade.translation.data.PageImageProcessor
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.live.PageOcr.rescaled
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.HybridTranslator
import eu.kanade.translation.translator.aiPart
import eu.kanade.translation.translator.fellBack
import eu.kanade.translation.translator.GoogleTranslator
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import eu.kanade.translation.ChapterTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import java.io.ByteArrayInputStream

/**
 * On-the-fly translation in the reader.
 *
 * A queue worker translates the page on screen first, then the following pages one after another while the
 * reader keeps reading. Each result is written to [ReaderPage.translation], which the page views observe, so
 * the translated bubbles appear on the page as soon as that page is done. Results are kept in a cache for the
 * whole app session, so scrolling back or reopening the chapter never translates a page twice.
 */
@Inject
@SingleIn(AppScope::class)
class LiveTranslator(
    private val preferences: TranslationPreferences,
    private val readerPreferences: ReaderPreferences,
    private val glossaryStore: GlossaryStore,
    private val editStore: eu.kanade.translation.edit.TranslationEditStore,
    private val teamStore: TeamStore,
    private val collection: TranslationCollection,
    private val lessonStore: LessonStore,
    private val translationManager: TranslationManager,
    private val liveProgress: LiveChapterProgress,
) {

    enum class Phase { OFF, IDLE, WORKING, PAUSED, DONE, ERROR }

    data class Status(
        val phase: Phase = Phase.OFF,
        /** 1-based number of the page being translated now. */
        val workingPage: Int? = null,
        /** 1-based number of the page on screen. */
        val focusPage: Int = 0,
        /** Whether the page on screen is already translated. */
        val focusReady: Boolean = false,
        val translated: Int = 0,
        val total: Int = 0,
        val error: String? = null,
    ) {
        val enabled: Boolean get() = phase != Phase.OFF
    }

    /** Translated pages of this app session, keyed by chapter id and page index. */
    private val cache = LruCache<String, PageTranslation>(CACHE_PAGES)

    private fun key(page: ReaderPage) =
        "${page.chapter.chapter.id}:${page.index}:${preferences.translateToLanguage().get()}:${preferences.translationEngine().get()}"

    fun session(scope: CoroutineScope, mangaId: () -> Long?, sourceLang: () -> String?): Session =
        Session(scope, mangaId, sourceLang)

    inner class Session(
        private val scope: CoroutineScope,
        private val mangaId: () -> Long?,
        private val sourceLang: () -> String?,
    ) {

        private val _status = MutableStateFlow(Status(phase = if (preferences.liveTranslation().get()) Phase.IDLE else Phase.OFF))
        val status: StateFlow<Status> = _status.asStateFlow()

        @Volatile private var pages: List<ReaderPage> = emptyList()

        @Volatile private var focus = 0

        @Volatile private var paused = false
        private val failed = HashSet<ReaderPage>()
        private val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile private var recognizer: TextRecognizer? = null

        @Volatile private var translator: TextTranslator? = null

        /** Set from the UI; the worker rebuilds its engine before the next page instead of closing it mid-call. */
        @Volatile private var resetEngine = false

        /** MANHWA: the team the engine was built for ("" = not the team). */
        @Volatile private var builtForTeam = ""

        @Volatile private var lastError: String? = null

        /** "Translate this chapter now" from the reader: works on this chapter even with auto translation off. */
        @Volatile private var forced = false

        /** A fused work's continuation: translated for this reader session only, the saved setting stays as it is. */
        @Volatile private var sessionOn = false

        // MANHWA: a chapter already in the reading language (an Arabic source, the Arabic part of a fused work) is
        // shown as it is; "translate now" still works on it
        private fun active() = ((preferences.liveTranslation().get() || sessionOn) && !alreadyInTargetLanguage()) || forced

        private fun alreadyInTargetLanguage(): Boolean {
            val lang = sourceLang()?.lowercase()?.substringBefore('-') ?: return false
            return lang == eu.kanade.translation.translator.TextTranslatorLanguage.fromPref(preferences.translateToLanguage()).code
        }

        /** MANHWA: the chapter on screen already has a saved translation: nothing is translated again. */
        @Volatile private var savedChapter = false

        /**
         * MANHWA: the chapter on screen is being translated from the manga screen and is past reading its pages:
         * the reader waits for it instead of translating the same chapter twice, and shows it when it is saved.
         */
        @Volatile private var heldFor: Translation? = null

        /** Pages showing a translation from the waiting chapter's finished batches: the saved file replaces them. */
        private val fromPartial: MutableSet<ReaderPage> = Collections.newSetFromMap(IdentityHashMap())

        init {
            scope.launch(Dispatchers.IO) {
                try {
                    work()
                } finally {
                    recognizer?.close()
                    translator?.close()
                    liveProgress.stop()
                }
            }
            // The chapter the reader waits for was finished outside: its pages come from the saved file
            translationManager.finished
                .onEach { done ->
                    val held = heldFor ?: return@onEach
                    if (done.chapterId != held.chapter.id) return@onEach
                    if (done.state == Translation.State.TRANSLATED) takeSaved(held)
                    heldFor = null
                    publish()
                    wake.trySend(Unit)
                }
                .launchIn(scope)
            // The chapter the reader waits for: every batch translated outside shows at once
            translationManager.partial
                .onEach { all ->
                    val held = heldFor ?: return@onEach
                    all[held.chapter.id]?.let { putPages(it, replacePartial = false) }
                }
                .launchIn(scope)
            // A glossary edit updates the bubbles already on screen at once, without asking the AI again
            glossaryStore.edits
                .onEach { edit -> if (edit.mangaId == mangaId()) applyGlossaryEdit(edit) }
                .launchIn(scope)
        }

        /** Called for every page the reader shows; the page on screen always goes first in the queue. */
        fun focus(page: ReaderPage) {
            if (page is InsertPage) return
            val chapterPages = page.chapter.pages ?: return
            if (chapterPages !== pages) {
                synchronized(failed) { failed.clear() }
                // Pages of the chapter left behind are not kept for the whole session
                synchronized(textless) { textless.retainAll { runCatching { it.chapter.pages === chapterPages }.getOrDefault(false) } }
                // Checked before this session's own results are put back: a translation from the file means the
                // chapter was translated already (pages without text are not in the file and need nothing)
                savedChapter = chapterPages.any { it.translation != null }
                heldFor = null
                synchronized(fromPartial) { fromPartial.clear() }
                pages = chapterPages
                // The chapter left behind is no longer "translating now"; this one reports itself when it starts
                if (liveProgress.current.value?.chapterId != page.chapter.chapter.id) liveProgress.stop()
                if (!savedChapter) handOver(page.chapter.chapter.id, page.chapter.chapter.name, page.chapter.chapter.scanlator)
                forced = false
                restoreFromCache(chapterPages)
            }
            focus = page.index
            publish()
            wake.trySend(Unit)
        }

        fun setEnabled(enabled: Boolean) {
            if (!enabled) sessionOn = false
            preferences.liveTranslation().set(enabled)
            paused = false
            synchronized(failed) { failed.clear() }
            lastError = null
            publish(error = null)
            wake.trySend(Unit)
        }

        fun togglePause() {
            paused = !paused
            publish()
            wake.trySend(Unit)
        }

        /**
         * MANHWA: the reader is left (another app, the home screen) while this chapter is being translated live:
         * what is done is handed over, so the background translation carries on with the rest at full strength.
         * Null when there is nothing left to hand over (translation off, the chapter done or saved, or the queue
         * already has it). The pages are keyed by their name in the chapter's files, as the chapter translator reads them.
         */
        fun handBack(expectedChapterId: Long): Pair<Long, Map<String, PageTranslation>>? {
            // A chapter the reader paused stays paused: nothing goes to the background
            if (!active() || paused || savedChapter || heldFor != null) return null
            val list = pages
            val chapterId = list.firstOrNull()?.chapter?.chapter?.id ?: return null
            if (chapterId != expectedChapterId) return null
            val left = list.count { it.translation == null && synchronized(textless) { it !in textless } }
            if (left == 0) return null
            val done = list.mapNotNull { page ->
                val name = page.translationKey ?: return@mapNotNull null
                page.translation?.takeIf { t -> t.blocks.any { it.translation.isNotBlank() } }?.let { name to it }
            }.toMap()
            // This reader rests meanwhile: one translation of the chapter at a time, the background one
            handedBack = true
            paused = true
            publish()
            return chapterId to done
        }

        @Volatile private var handedBack = false

        /**
         * MANHWA: back in the reader after [handBack]: the background translation's pages show here (or this reader
         * takes the chapter back when the background has not really started on it), and live translation goes on.
         */
        fun comeBack() {
            if (!handedBack) return
            handedBack = false
            paused = false
            pages.firstOrNull()?.chapter?.chapter?.let {
                // Still waiting for its download: the reader takes it back, the background does not start it too
                if (translationManager.getQueuedTranslationOrNull(it.id) == null) translationManager.translateAfterDownload -= it.id
                handOver(it.id, it.name, it.scanlator)
            }
            publish()
            wake.trySend(Unit)
        }

        /** Translates every chapter of this reader session (a fused continuation) without saving the setting. */
        fun enableForSession() {
            sessionOn = true
            paused = false
            synchronized(failed) { failed.clear() }
            lastError = null
            publish(error = null)
            wake.trySend(Unit)
        }

        /** Translates the chapter on screen once, without turning automatic translation on. */
        fun translateNow() {
            forced = true
            paused = false
            // The reader asked for it now: no more waiting on the manga screen's job
            heldFor = null
            synchronized(failed) { failed.clear() }
            lastError = null
            if (!readerPreferences.showTranslations.get()) readerPreferences.showTranslations.set(true)
            publish(error = null)
            wake.trySend(Unit)
        }

        /** Tries the pages that failed again (after a network or API problem). */
        fun retry() {
            synchronized(failed) { failed.clear() }
            heldFor = null
            resetEngine = true
            paused = false
            lastError = null
            publish(error = null)
            wake.trySend(Unit)
        }

        /** Forgets this chapter's live results and translates again, e.g. after many glossary changes. */
        fun retranslate() {
            synchronized(failed) { failed.clear() }
            savedChapter = false
            heldFor = null
            pages.forEach { page ->
                cache.remove(key(page))
                page.translation = null
            }
            resetEngine = true
            lastError = null
            preferences.liveTranslation().set(true)
            paused = false
            publish(error = null)
            wake.trySend(Unit)
        }

        private suspend fun work() {
            while (scope.isActive) {
                val next = if (canWork()) pick() else null
                if (next == null) {
                    publish(working = null)
                    // Sleep until something changes; wake up now and then for pages that finished loading
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                    continue
                }
                publish(working = next.number)
                try {
                    val result = translate(next)
                    if (result != null) {
                        cache.put(key(next), result)
                        withContext(Dispatchers.Main) { next.translation = result }
                    } else {
                        // Nothing to translate on this page (art only): don't try it again
                        synchronized(failed) { failed += next }
                        synchronized(textless) { textless += next }
                    }
                    publish(error = null)
                    chapterProgress(next)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e) { "Live translation failed on page ${next.number}" }
                    synchronized(failed) { failed += next }
                    // Network and API problems stop the queue until Retry; a bad page only skips that page
                    if (e is java.io.IOException) {
                        lastError = e.message?.take(160) ?: e.javaClass.simpleName
                        publish(error = lastError)
                    } else {
                        publish()
                    }
                }
            }
        }

        /** MANHWA: pages read and found without any text (they need no translation). */
        private val textless: MutableSet<ReaderPage> = Collections.newSetFromMap(IdentityHashMap())

        /** The chapter id whose live translation was saved this session (saved once). */
        @Volatile private var savedLive: Long? = null

        /**
         * MANHWA: reports how far this chapter got; once every page is done, a fused work's continuation chapter is
         * saved as a translated chapter (its row then shows it translated, and it is never translated again).
         */
        private suspend fun chapterProgress(page: ReaderPage) {
            val list = pages
            val chapter = page.chapter.chapter
            val chapterId = chapter.id
            if (list.isEmpty() || list.firstOrNull()?.chapter?.chapter?.id != chapterId) return
            val done = list.count { it.translation != null || synchronized(textless) { it in textless } }
            liveProgress.report(chapterId, done, list.size, mangaId(), chapter.name)
            if (done < list.size || savedChapter || heldFor != null || savedLive == chapterId) return
            if (synchronized(fromPartial) { fromPartial.isNotEmpty() }) return
            savedLive = chapterId
            withContext(Dispatchers.IO) {
                runCatching {
                    val graph = teamStore.appContext.appGraph
                    val manga = mangaId()?.let { graph.getMangaById.await(it) } ?: return@runCatching
                    if (graph.chapterFusion.continuationOf(manga.id) == null) return@runCatching
                    val source = graph.sourceManager.get(manga.source) ?: return@runCatching
                    val saved = list.mapNotNull { p ->
                        val translation = p.translation ?: return@mapNotNull null
                        (p.translationKey ?: "%03d".format(p.number)) to translation
                    }.toMap()
                    translationManager.saveLiveChapter(manga, chapter, source, saved)
                }.onFailure { logcat(LogPriority.WARN, it) { "Live chapter not saved" } }
            }
        }

        private fun canWork(): Boolean =
            active() && !paused && readerPreferences.showTranslations.get() &&
                lastError == null && heldFor == null && !checkingSaved && (!savedChapter || forced)

        /**
         * MANHWA: the chapter just opened is in the manga screen's translation queue. Still waiting or fetching
         * its pages: that job stops and the reader translates it here, page by page. Already translating: the
         * reader waits for it (no chapter is translated twice) and shows it as soon as it is saved.
         */
        private fun handOver(chapterId: Long?, name: String, scanlator: String?) {
            chapterId ?: return
            val queued = translationManager.getQueuedTranslationOrNull(chapterId)
            if (queued == null) {
                // Not queued: it may have been translated and saved after its pages were loaded (or it is read
                // online). Looked for before translating anything, so a saved chapter is never paid for twice.
                lookForSaved(chapterId, name, scanlator)
                return
            }
            if (queued.status == Translation.State.ERROR) return
            val stage = queued.progress.value.stage
            // Waited for only while it really runs: a paused or waiting chapter is translated here instead
            if (queued.status == Translation.State.TRANSLATING && stage >= Translation.Stage.TRANSLATING) {
                heldFor = queued
                // Batches already done before the chapter was opened
                translationManager.partial.value[chapterId]?.let { ready -> scope.launch { putPages(ready, replacePartial = false) } }
            } else {
                translationManager.cancelQueuedTranslation(queued)
            }
        }

        /** True while the saved file of the chapter just opened is looked for: nothing is translated meanwhile. */
        @Volatile private var checkingSaved = false

        private fun lookForSaved(chapterId: Long, name: String, scanlator: String?) {
            checkingSaved = true
            scope.launch(Dispatchers.IO) {
                try {
                    val graph = teamStore.appContext.appGraph
                    val manga = mangaId()?.let { graph.getMangaById.await(it) } ?: return@launch
                    val source = graph.sourceManager.get(manga.source) ?: return@launch
                    val saved = runCatching { translationManager.getChapterTranslation(name, scanlator, manga.title, source) }.getOrDefault(emptyMap())
                    // Still the chapter on screen
                    if (saved.isNotEmpty() && pages.firstOrNull()?.chapter?.chapter?.id == chapterId) {
                        putPages(saved, replacePartial = true)
                        savedChapter = true
                    }
                } finally {
                    checkingSaved = false
                    publish()
                    wake.trySend(Unit)
                }
            }
        }

        /** Puts the saved translation of [held] on the chapter's pages, by file name or by page number. */
        private suspend fun takeSaved(held: Translation) {
            val saved = withContext(Dispatchers.IO) {
                translationManager.getChapterTranslation(held.chapter.name, held.chapter.scanlator, held.manga.title, held.source)
            }
            if (saved.isEmpty()) return
            // The saved (reviewed) chapter replaces what its batches showed before
            putPages(saved, replacePartial = true)
            synchronized(fromPartial) { fromPartial.clear() }
            savedChapter = true
        }

        /**
         * Puts [ready] pages on the chapter on screen, matched by file name or page number: on pages with nothing
         * yet, and with [replacePartial] also over what an earlier batch showed.
         */
        private suspend fun putPages(ready: Map<String, PageTranslation>, replacePartial: Boolean) {
            if (ready.isEmpty()) return
            val byNumber = ready.entries.associate { (name, page) -> name.substringBeforeLast('.').filter(Char::isDigit).toIntOrNull() to page }
            withContext(Dispatchers.Main) {
                pages.forEach { page ->
                    val earlier = synchronized(fromPartial) { page in fromPartial }
                    if (page.translation != null && !(replacePartial && earlier)) return@forEach
                    val found = page.translationKey?.let(ready::get) ?: byNumber[page.number] ?: return@forEach
                    page.translation = found
                    if (!replacePartial) synchronized(fromPartial) { fromPartial += page }
                }
            }
            publish()
        }

        /** The page on screen first, then the next pages that are loaded and not translated yet. */
        private fun pick(): ReaderPage? {
            val list = pages
            if (list.isEmpty()) return null
            val start = focus.coerceIn(0, list.lastIndex)
            val end = (start + LOOKAHEAD).coerceAtMost(list.lastIndex)
            return (start..end).asSequence().map { list[it] }.firstOrNull { page ->
                page.translation == null &&
                    synchronized(failed) { page !in failed } &&
                    page.status == Page.State.Ready &&
                    page.stream != null
            }
        }

        private fun restoreFromCache(list: List<ReaderPage>) {
            list.forEach { page ->
                if (page.translation == null) cache.get(key(page))?.let { page.translation = it }
            }
        }

        /** The OCR language: from the source's language when it is clear, otherwise the setting. */
        private fun ocrLanguage(): TextRecognizerLanguage = when (sourceLang()?.lowercase()?.substringBefore('-')) {
            "ko" -> TextRecognizerLanguage.KOREAN
            "ja" -> TextRecognizerLanguage.JAPANESE
            "zh" -> TextRecognizerLanguage.CHINESE
            null, "", "all", "other" -> TextRecognizerLanguage.fromPref(preferences.translateFromLanguage())
            else -> TextRecognizerLanguage.ENGLISH
        }

        /** The team (behind Google's quick draft when that is on), or the engine for "no keys". */
        private fun buildEngine(
            team: List<TeamStore.Member>,
            quickDraft: Boolean,
            from: TextRecognizerLanguage,
            to: TextTranslatorLanguage,
        ): TextTranslator = when {
            // MANHWA fast mode (like Google Lens): machine translation straight after the OCR, no AI
            preferences.translationFastMode().get() -> eu.kanade.translation.translator.FastTranslator(
                from,
                to,
                preferences,
                teamStore.appContext.appGraph.networkHelper.client,
            )
            // MANHWA: no key but a model on the phone: the phone translates the page being read, as it does chapters
            team.isEmpty() && eu.kanade.translation.local.LocalAiEngine.available(teamStore.appContext) ->
                eu.kanade.translation.local.LocalAiTranslator(
                    teamStore.appContext,
                    from,
                    to,
                    instructions = eu.kanade.translation.style.StyleProfile.instruction(preferences, to.name),
                    tuning = eu.kanade.translation.local.LocalAiRules.Tuning.named(preferences.translationLocalTuning().get()),
                ).also { eu.kanade.translation.local.LocalAiEngine.warm(teamStore.appContext) }
            team.isEmpty() -> TextTranslators.withoutKeys(preferences).build(preferences, from, to)
            // The page being read goes first through the router, even while chapters are translated
            quickDraft -> HybridTranslator(from, to, GoogleTranslator(from, to), TeamTranslator(from, to, teamStore, team, AiRouter.Lane.LIVE))
            else -> TeamTranslator(from, to, teamStore, team, AiRouter.Lane.LIVE)
        }

        private suspend fun translate(page: ReaderPage): PageTranslation? {
            val from = ocrLanguage()
            val to = TextTranslatorLanguage.fromPref(preferences.translateToLanguage())
            val ocr = recognizer?.takeIf { it.language == from } ?: TextRecognizer(from).also {
                recognizer?.close()
                recognizer = it
            }
            if (resetEngine) {
                resetEngine = false
                translator?.close()
                translator = null
            }
            // MANHWA: the team is the AI here too. With "quick draft" on, Google's draft shows first and the team's
            // translation replaces it; without any working key, the engine chosen for "no keys" translates.
            val team = teamStore.active()
            val quickDraft = preferences.translationQuickDraft().get()
            val teamSignature = team.joinToString(",") { "${it.id}:${it.model}:${it.key.hashCode()}:${it.baseUrl}" } +
                "|$quickDraft|${preferences.translationEngine().get()}|${preferences.translationFastMode().get()}:${preferences.translationFastEngine().get()}" +
                "|${eu.kanade.translation.local.LocalAiEngine.available(teamStore.appContext)}:${preferences.translationLocalModelPath().get()}:${preferences.translationLocalTuning().get()}"
            if (teamSignature != builtForTeam) {
                builtForTeam = teamSignature
                translator?.close()
                translator = null
            }
            val engine = translator?.takeIf { it.fromLang == from && it.toLang == to }
                ?: buildEngine(team, quickDraft, from, to).also {
                    translator?.close()
                    translator = it
                }

            // 1. Read the page and find the bubbles. Image work goes through the shared gate (reader first), so it
            // never runs together with a background translation's OCR or another page's drawing.
            val stream = page.stream ?: return null
            val bytes = stream().use { it.readBytes() }
            val images = mutableMapOf<String, PageImage>()
            // MANHWA: the team that reads unsure and stylised lines again from their own image (null without keys)
            val rereader = (engine as? TeamTranslator) ?: ((engine as? HybridTranslator)?.ai as? TeamTranslator)
            val unsureCrops = mutableListOf<kotlin.Pair<Int, ByteArray>>()
            // MANHWA: sound effects stay as drawn, neither translated nor erased
            val skipSfx = preferences.translationSkipSfx().get()
            val result: PageTranslation = HeavyWork.reader<PageTranslation?> {
                val decoded = PageImageProcessor.decode { ByteArrayInputStream(bytes) } ?: return@reader null
                try {
                    // OCR ran on the downscaled bitmap (in strips for tall pages); boxes go back to the real page size
                    // MANHWA dual OCR: PaddleOCR reads with ML Kit when its models are on the phone
                    val appContext = teamStore.appContext
                    val paddle = eu.kanade.translation.ocr.PaddleOcrEngine.readerFor(appContext, from)
                    PageOcr.recognize(ocr, decoded.bitmap, paddle, eu.kanade.translation.ocr.PaddleOcrEngine.mode(appContext)).rescaled(1f / decoded.scale).also {
                        if (skipSfx) it.blocks.removeAll { b -> b.kind.equals(PageOcr.SFX, ignoreCase = true) }
                        if (!preferences.translationArtText().get()) it.blocks.forEach { b -> if (b.kind.equals(PageOcr.ART, ignoreCase = true)) b.kind = "" }
                        if (preferences.translationOcrHealer().get()) it.blocks.forEach { b -> b.text = OcrHealer.heal(b.text) }
                        PageImageProcessor.prepare(it, decoded)
                        if (engine.aiPart != null && preferences.translationVision().get()) {
                            images[KEY] = PageImageProcessor.tiles(decoded)
                        } else if (rereader != null) {
                            // Stylised letters ("A NATTY") and readings the OCR was not sure of are cut out to be
                            // read again from their image, as for whole chapters (a few per page)
                            it.blocks.forEachIndexed { i, b ->
                                val art = b.kind.equals(PageOcr.ART, ignoreCase = true) || b.kind.equals(TextKindRules.SHOUT, ignoreCase = true)
                                if ((b.ocrConfidence < AiTranslator.UNSURE_OCR || art) && unsureCrops.size < LIVE_REREAD) {
                                    PageImageProcessor.crop(decoded, b)?.let { jpeg -> unsureCrops += i to jpeg }
                                }
                            }
                        }
                    }
                } finally {
                    decoded.bitmap.recycle()
                }
            } ?: return null
            if (result.blocks.isEmpty() && images.isEmpty()) return null
            if (rereader != null && unsureCrops.isNotEmpty()) {
                val fixed = runCatching { rereader.reread(unsureCrops.mapIndexed { n, c -> "$n" to c.second }) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyMap())
                fixed.forEach { (id, text) ->
                    val index = unsureCrops.getOrNull(id.toIntOrNull() ?: -1)?.first ?: return@forEach
                    result.blocks.getOrNull(index)?.let { block ->
                        block.text = text
                        block.ocrConfidence = 1f
                    }
                }
            }

            // 2. Translate, following the series glossary and adding the new names it finds
            val id = mangaId()
            engine.aiPart?.apply {
                // MANHWA master codex under the series glossary (English to Arabic)
                val codex = if ((from == TextRecognizerLanguage.ENGLISH || from == TextRecognizerLanguage.KOREAN) && to == TextTranslatorLanguage.ARABIC) {
                    eu.kanade.translation.collection.ManhwaCodex.termsIn(result.blocks.joinToString(" ") { it.text }, preferences.translationCodexPacks().get())
                } else {
                    emptyMap()
                }
                glossary = eu.kanade.translation.glossary.GlossaryEnforcer.seriesFirst(id?.let(glossaryStore::asMap).orEmpty(), codex)
                learnTerms = id != null
                // MANHWA: new names come with who they are (kind, man or woman), so the next pages agree with them
                richTerms = id != null
                discoveredTerms.clear()
                discoveredDetails.clear()
            }
            // MANHWA fast mode: the work's names and the codex go in before the lines are sent
            (engine as? eu.kanade.translation.translator.FastTranslator)?.let { fast ->
                val codex = if ((from == TextRecognizerLanguage.ENGLISH || from == TextRecognizerLanguage.KOREAN) && to == TextTranslatorLanguage.ARABIC) {
                    eu.kanade.translation.collection.ManhwaCodex.termsIn(result.blocks.joinToString(" ") { it.text }, preferences.translationCodexPacks().get())
                } else {
                    emptyMap()
                }
                // The work's own names win over the codex
                fast.glossary = eu.kanade.translation.glossary.GlossaryEnforcer.seriesFirst(id?.let(glossaryStore::asMap).orEmpty(), codex)
            }
            // MANHWA phone model: the work's names, the codex and who the characters are
            (engine as? eu.kanade.translation.local.LocalAiTranslator)?.let { phone ->
                val codex = if ((from == TextRecognizerLanguage.ENGLISH || from == TextRecognizerLanguage.KOREAN) && to == TextTranslatorLanguage.ARABIC) {
                    eu.kanade.translation.collection.ManhwaCodex.termsIn(result.blocks.joinToString(" ") { it.text }, preferences.translationCodexPacks().get())
                } else {
                    emptyMap()
                }
                phone.glossary = eu.kanade.translation.glossary.GlossaryEnforcer.seriesFirst(id?.let(glossaryStore::asMap).orEmpty(), codex)
                phone.notes = id?.let(glossaryStore::notes).orEmpty()
            }
            // MANHWA: lines from the collection are written at once; only the rest goes to the translator
            val known: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
            val useLibrary = preferences.translationCollection().get()
            if (useLibrary) {
                val glossary = id?.let(glossaryStore::asMap).orEmpty()
                result.blocks.forEach { block ->
                    val hit = collection.lookup(
                        from.name,
                        eu.kanade.translation.style.StyleProfile.libraryTarget(preferences, to.name),
                        block.text,
                        glossary,
                        acceptNear = engine is eu.kanade.translation.translator.FastTranslator,
                    )
                    if (hit?.sure == true) {
                        block.translation = hit.translation
                        known += block
                    } else if (hit != null && engine is eu.kanade.translation.local.LocalAiTranslator) {
                        // MANHWA: a line the collection is not sure of is the phone model's draft to start from
                        block.translation = hit.translation
                    }
                }
                collection.record(result.blocks.size, known.size)
            }
            engine.aiPart?.apply {
                glossaryNotes = id?.let(glossaryStore::notes).orEmpty()
                lessons = id?.let(lessonStore::forPrompt).orEmpty()
                fitHints = true
                // MANHWA: the story so far too, as for whole chapters
                storySoFar = id?.let(glossaryStore::storySoFar).orEmpty()
                this.skipSfx = skipSfx
            }
            val work = if (known.isEmpty()) result else result.copy(blocks = result.blocks.filterTo(mutableListOf()) { it !in known })
            val map = mutableMapOf(KEY to work)
            // Hybrid: the Google draft shows on the page right away, the AI version replaces it a moment later
            (engine as? HybridTranslator)?.onDraft = { drafts ->
                // With lines from the collection, the draft is the whole page: those lines and the Google draft
                (if (known.isEmpty()) drafts[KEY] else result)?.takeIf { it.blocks.isNotEmpty() }?.let { draft ->
                    val copy = draft.copy(blocks = draft.blocks.map { it.copy() }.toMutableList())
                    scope.launch(Dispatchers.Main) { if (page.translation == null) page.translation = copy }
                }
            }
            try {
                if (work.blocks.isNotEmpty() || known.isEmpty()) engine.translate(map, images)
                // MANHWA quick check: bubbles left untranslated, too long or missing a glossary name are asked once
                // more; the new answer stays only when it passes where the first did not
                val checkGlossary = id?.let(glossaryStore::asMap).orEmpty()
                val latin = to.code !in ChapterTranslator.NON_LATIN_TARGETS
                val bad = work.blocks.filter {
                    it.translation.isNotBlank() && !it.kind.equals(PageOcr.SFX, ignoreCase = true) &&
                        eu.kanade.translation.team.QualityCheck.check(it.text, it.translation, checkGlossary, latin) != null
                }
                if (bad.isNotEmpty() && engine.aiPart != null) {
                    val before = bad.associateWith { it.translation }
                    runCatching { engine.translate(mutableMapOf(KEY to work.copy(blocks = bad.toMutableList()))) }
                        .onFailure { if (it is CancellationException) throw it }
                    before.forEach { (block, old) ->
                        val fixed = block.translation.isNotBlank() &&
                            eu.kanade.translation.team.QualityCheck.check(block.text, block.translation, checkGlossary, latin) == null
                        if (!fixed) block.translation = old
                    }
                }
            } finally {
                engine.aiPart?.apply {
                    glossaryNotes = emptyMap()
                    lessons = ""
                    fitHints = false
                    storySoFar = ""
                    this.skipSfx = false
                }
            }
            if (known.isNotEmpty()) {
                // The page as translated: the collection's lines and the translator's, in their places
                val returned: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
                returned += work.blocks
                val merged = result.blocks.filterTo(mutableListOf()) { it in known || it in returned }
                val placed: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
                placed += merged
                work.blocks.filterTo(merged) { it !in placed }
                result.blocks = merged
            }
            if (result.blocks.any { it.needsSnap }) {
                // Bubbles added by the vision model: placed on their real letters, or dropped
                HeavyWork.reader {
                    PageImageProcessor.decode { ByteArrayInputStream(bytes) }?.let { again ->
                        try {
                            PageImageProcessor.prepare(result, again)
                        } finally {
                            again.bitmap.recycle()
                        }
                    }
                }
            }
            // One translation per place: duplicates and empty pieces merged, nothing drawn twice
            PageOcr.finish(result, keepSfxArt = skipSfx)
            editStore.restore(result, "l${page.chapter.chapter.id}", "${page.index}")
            if (id != null) engine.aiPart?.let { ai -> glossaryStore.merge(id, ai.discoveredTerms, ai.discoveredDetails.mapValues { (_, info) -> info.kind to info.gender }) }
            // MANHWA: what this page taught goes into the collection too
            if (useLibrary && id != null && engine.aiPart != null && !engine.fellBack) {
                collection.learn(
                    from.name,
                    eu.kanade.translation.style.StyleProfile.libraryTarget(preferences, to.name),
                    id,
                    glossaryStore.asMap(id),
                    result.blocks.filter { it.translation.isNotBlank() }.map { TranslationCollection.Line(it.text, it.translation, it.kind) },
                    TranslationCollection.Source.TEAM,
                )
            }
            // MANHWA phone memory: the phone's checked lines, below every key's
            val phone = engine as? eu.kanade.translation.local.LocalAiTranslator
            if (useLibrary && id != null && phone != null) {
                collection.learn(
                    from.name,
                    eu.kanade.translation.style.StyleProfile.libraryTarget(preferences, to.name),
                    id,
                    glossaryStore.asMap(id),
                    phone.learnable(listOf(result)),
                    TranslationCollection.Source.LOCAL,
                )
            }

            // 3. Apply edited glossary terms the model may have ignored
            if (id != null) {
                glossaryStore.get(id).filter { it.edited }.forEach { entry ->
                    result.blocks.forEach { block ->
                        // The model left the original term untranslated: put the reader's translation in
                        if (block.text.contains(entry.source, ignoreCase = true) && block.translation.contains(entry.source, ignoreCase = true)) {
                            block.translation = block.translation.replace(entry.source, entry.translation, ignoreCase = true)
                        }
                    }
                }
            }
            return result.takeIf { it.blocks.isNotEmpty() }
        }

        private suspend fun applyGlossaryEdit(edit: GlossaryStore.Edit) {
            val changed = pages.mapNotNull { page ->
                val current = page.translation ?: return@mapNotNull null
                val word = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(edit.oldTranslation) + "(?![\\p{L}\\p{N}])")
                fun touches(block: eu.kanade.translation.model.TranslationBlock) =
                    block.text.contains(edit.source, ignoreCase = true) && word.containsMatchIn(block.translation)
                if (current.blocks.none(::touches)) return@mapNotNull null
                val updated = current.copy(
                    blocks = current.blocks.map {
                        if (touches(it)) it.copy(translation = word.replace(it.translation, Regex.escapeReplacement(edit.newTranslation))) else it
                    }.toMutableList(),
                )
                page to updated
            }
            if (changed.isEmpty()) return
            withContext(Dispatchers.Main) {
                changed.forEach { (page, updated) ->
                    cache.put(key(page), updated)
                    page.translation = updated
                }
            }
        }

        private fun publish(working: Int? = _status.value.workingPage, error: String? = lastError) {
            val list = pages
            val translated = list.count { it.translation != null }
            val focusPage = list.getOrNull(focus)
            val focusDone = focusPage != null && (focusPage.translation != null || synchronized(failed) { focusPage in failed })
            val phase = when {
                !active() -> Phase.OFF
                error != null -> Phase.ERROR
                paused || !readerPreferences.showTranslations.get() -> Phase.PAUSED
                working != null -> Phase.WORKING
                list.isNotEmpty() && translated + synchronized(failed) { failed.size } >= list.size -> Phase.DONE
                else -> Phase.IDLE
            }
            _status.value = Status(
                phase = phase,
                workingPage = working,
                focusPage = focus + 1,
                focusReady = focusDone,
                translated = translated,
                total = list.size,
                error = error,
            )
        }
    }

    private companion object {
        const val KEY = "page"
        // MANHWA: most lines per page read again from their image by a key that sees images
        const val LIVE_REREAD = 3
        const val LOOKAHEAD = 8
        const val POLL_MS = 600L
        const val CACHE_PAGES = 400
    }
}
