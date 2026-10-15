package eu.kanade.translation.live

import android.util.LruCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.HeavyWork
import eu.kanade.translation.collection.TranslationCollection
import eu.kanade.translation.learning.LessonStore
import eu.kanade.translation.model.TranslationBlock
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
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
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

        private fun active() = preferences.liveTranslation().get() || forced

        init {
            scope.launch(Dispatchers.IO) {
                try {
                    work()
                } finally {
                    recognizer?.close()
                    translator?.close()
                }
            }
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
                pages = chapterPages
                forced = false
                restoreFromCache(chapterPages)
            }
            focus = page.index
            publish()
            wake.trySend(Unit)
        }

        fun setEnabled(enabled: Boolean) {
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

        /** Translates the chapter on screen once, without turning automatic translation on. */
        fun translateNow() {
            forced = true
            paused = false
            synchronized(failed) { failed.clear() }
            lastError = null
            if (!readerPreferences.showTranslations.get()) readerPreferences.showTranslations.set(true)
            publish(error = null)
            wake.trySend(Unit)
        }

        /** Tries the pages that failed again (after a network or API problem). */
        fun retry() {
            synchronized(failed) { failed.clear() }
            resetEngine = true
            paused = false
            lastError = null
            publish(error = null)
            wake.trySend(Unit)
        }

        /** Forgets this chapter's live results and translates again, e.g. after many glossary changes. */
        fun retranslate() {
            synchronized(failed) { failed.clear() }
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
                    }
                    publish(error = null)
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

        private fun canWork(): Boolean =
            active() && !paused && readerPreferences.showTranslations.get() &&
                lastError == null

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
            // MANHWA: with "team inside the reader" on, the team (and the collection, below) translate here too;
            // off (the default), the engine is chosen exactly as before
            val teamHere = preferences.translationTeamInReader().get()
            val team = if (teamHere && preferences.translationTeamEnabled().get()) teamStore.active() else emptyList()
            val teamSignature = team.joinToString(",") { "${it.id}:${it.model}:${it.key.hashCode()}" }
            if (teamSignature != builtForTeam) {
                builtForTeam = teamSignature
                translator?.close()
                translator = null
            }
            val engine = translator?.takeIf { it.fromLang == from && it.toLang == to }
                ?: (if (team.isNotEmpty()) TeamTranslator(from, to, teamStore, team) else TextTranslators.fromPref(preferences.translationEngine()).build(preferences, from, to)).also {
                    translator?.close()
                    translator = it
                }

            // 1. Read the page and find the bubbles. Image work goes through the shared gate (reader first), so it
            // never runs together with a background translation's OCR or another page's drawing.
            val stream = page.stream ?: return null
            val bytes = stream().use { it.readBytes() }
            val images = mutableMapOf<String, PageImage>()
            val result: PageTranslation = HeavyWork.reader<PageTranslation?> {
                val decoded = PageImageProcessor.decode { ByteArrayInputStream(bytes) } ?: return@reader null
                try {
                    // OCR ran on the downscaled bitmap (in strips for tall pages); boxes go back to the real page size
                    PageOcr.recognize(ocr, decoded.bitmap).rescaled(1f / decoded.scale).also {
                        PageImageProcessor.prepare(it, decoded)
                        if (engine.aiPart != null && preferences.translationVision().get()) {
                            images[KEY] = PageImageProcessor.tiles(decoded)
                        }
                    }
                } finally {
                    decoded.bitmap.recycle()
                }
            } ?: return null
            if (result.blocks.isEmpty() && images.isEmpty()) return null

            // 2. Translate, following the series glossary and adding the new names it finds
            val id = mangaId()
            engine.aiPart?.apply {
                glossary = id?.let(glossaryStore::asMap).orEmpty()
                learnTerms = id != null
                discoveredTerms.clear()
            }
            // MANHWA: lines from the collection are written at once; only the rest goes to the translator
            val known: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
            if (teamHere) {
                val glossary = id?.let(glossaryStore::asMap).orEmpty()
                result.blocks.forEach { block ->
                    val hit = collection.lookup(from.name, to.name, block.text, glossary)
                    if (hit?.sure == true) {
                        block.translation = hit.translation
                        known += block
                    }
                }
                collection.record(result.blocks.size, known.size)
                engine.aiPart?.apply {
                    glossaryNotes = id?.let(glossaryStore::notes).orEmpty()
                    lessons = id?.let(lessonStore::forPrompt).orEmpty()
                    fitHints = true
                }
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
            } finally {
                if (teamHere) {
                    engine.aiPart?.apply {
                        glossaryNotes = emptyMap()
                        lessons = ""
                        fitHints = false
                    }
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
            PageOcr.finish(result)
            editStore.restore(result, "l${page.chapter.chapter.id}", "${page.index}")
            if (id != null) engine.aiPart?.let { glossaryStore.merge(id, it.discoveredTerms) }
            // MANHWA: what this page taught goes into the collection too
            if (teamHere && id != null) {
                collection.learn(
                    from.name,
                    to.name,
                    id,
                    glossaryStore.asMap(id),
                    result.blocks.map { TranslationCollection.Line(it.text, it.translation, it.kind) },
                    TranslationCollection.Source.TEAM,
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
        const val LOOKAHEAD = 8
        const val POLL_MS = 600L
        const val CACHE_PAGES = 400
    }
}
