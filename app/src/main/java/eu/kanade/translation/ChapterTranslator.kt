package eu.kanade.translation

import mihon.app.di.appGraph
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.google.mlkit.vision.text.Text
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.collection.TextKey
import eu.kanade.translation.collection.TranslationCollection
import eu.kanade.translation.data.PageImage
import eu.kanade.translation.learning.LessonStore
import eu.kanade.translation.team.ChapterPlanner
import eu.kanade.translation.team.QualityCheck
import eu.kanade.translation.team.TeamStore
import eu.kanade.translation.translator.TeamTranslator
import java.util.Collections
import java.util.IdentityHashMap
import eu.kanade.translation.data.PageImageProcessor
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.live.OcrHealer
import eu.kanade.translation.live.PageOcr
import eu.kanade.translation.live.PageOcr.rescaled
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.aiPart
import eu.kanade.translation.translator.fellBack
import eu.kanade.translation.translator.bubbleCharBudget
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import eu.kanade.tachiyomi.util.system.cancelNotification
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs

@Inject
@SingleIn(AppScope::class)
class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val translationPreferences: TranslationPreferences,
    private val glossaryStore: GlossaryStore,
    private val collection: TranslationCollection,
    private val teamStore: TeamStore,
    private val lessonStore: LessonStore,
) {

    private val translationMutex = kotlinx.coroutines.sync.Mutex()

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /** A chapter that will not be worked on any more: translated, failed, or turned away when it was asked for. */
    data class Finished(val mangaId: Long, val chapterId: Long, val state: Translation.State)

    /**
     * Every chapter's end, sent before it leaves the queue. The status of a chapter that leaves the queue could
     * be missed by whoever watched it, which left the manga screen loading forever.
     */
    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 256)
    val finished = _finished.asSharedFlow()

    /**
     * MANHWA: pages of chapters still being translated whose batch is done (chapter id to page name to page),
     * so a reader waiting for the chapter shows them at once. Gone when the chapter ends (the file takes over).
     */
    private val _partial = MutableStateFlow<Map<Long, Map<String, PageTranslation>>>(emptyMap())
    val partial = _partial.asStateFlow()

    private fun finish(manga: Manga, chapter: Chapter, state: Translation.State) {
        _partial.update { it - chapter.id }
        _finished.tryEmit(Finished(manga.id, chapter.id, state))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    /** Made when the first chapter is translated: building it at startup could take the app down with it. */
    private var textRecognizer: TextRecognizer? = null
    private var textTranslator: TextTranslator

    init {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        textTranslator = TextTranslators.withoutKeys(translationPreferences)
            .build(translationPreferences, fromLang, toLang)
        // MANHWA: sound effects are left as drawn by default, also for readers from before it was the default
        if (!translationPreferences.translationSfxMigrated().get()) {
            translationPreferences.translationSkipSfx().set(true)
            translationPreferences.translationSfxMigrated().set(true)
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        isPaused = false
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    @Synchronized
    fun stop(reason: String? = null) {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach {
                it.status = Translation.State.ERROR
                finish(it.manga, it.chapter, Translation.State.ERROR)
            }
        if (reason != null) return
        isPaused = false
    }

    @Synchronized
    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach {
                it.status = Translation.State.QUEUE
                // Shows it waits again, keeping how far it got
                it.requeue()
            }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    @Synchronized
    private fun launchTranslatorJob() {
        if (isRunning) return

        translationJob = scope.launch {
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations =
                        queue.asSequence().filter { it.status.value <= Translation.State.TRANSLATING.value }
                            .groupBy { it.source }.toList().take(5).map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    val activeTranslationsErroredFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.contains(Translation.State.ERROR)
                        }.filter { it }
                    activeTranslationsErroredFlow.first()
                }
            }.distinctUntilChanged()
            supervisorScope {
                val translationJobs = mutableMapOf<Translation, Job>()

                activeTranslationFlow.collectLatest { activeTranslations ->
                    val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                    translationJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        translationJobs.remove(download)
                    }

                    val translationsToStart = activeTranslations.filter { it !in translationJobs }
                    translationsToStart.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        var reported = false
        try {
            // One chapter at a time: the recognizer, translator and glossary are shared
            translationMutex.withLock { translateChapter(translation) }
            finish(translation.manga, translation.chapter, translation.status)
            reported = true
            if (translation.status == Translation.State.TRANSLATED) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            translation.status = Translation.State.ERROR
            finish(translation.manga, translation.chapter, Translation.State.ERROR)
            reported = true
            stop()
        } finally {
            // Stopped from outside (removed from the queue, the reader took the chapter over): the chapter's
            // ring must still end, never spin forever
            if (!reported) finish(translation.manga, translation.chapter, translation.status)
        }
    }

    /**
     * MANHWA: the translation is written next to its final name, flushed to the disk, and only then renamed, so
     * a crash or a closed app mid-save never leaves a half-written file that would open as a broken chapter.
     */
    internal fun saveAtomically(dir: UniFile, name: String, pages: Map<String, PageTranslation>) {
        val partName = "$name.part"
        dir.findFile(partName)?.delete()
        val part = dir.createFile(partName) ?: throw IOException("Could not create $partName")
        val path = part.uri.takeIf { it.scheme == "file" }?.path
        if (path != null) {
            java.io.FileOutputStream(path).use { out ->
                Json.encodeToStream(pages, out)
                out.flush()
                out.fd.sync()
            }
        } else {
            part.openOutputStream().use { Json.encodeToStream(pages, it) }
        }
        if (part.length() <= 0L) throw IOException("Nothing was written")
        // A plain folder: the new file takes the old one's place in one step (the reader never finds none)
        if (path != null) {
            val from = java.io.File(path)
            if (from.renameTo(java.io.File(from.parentFile, name))) return
        }
        dir.findFile(name)?.delete()
        if (!part.renameTo(name)) throw IOException("Could not rename $partName")
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    /** Queues [chapter]; a chapter that can't or needn't be translated is reported as finished at once. */
    suspend fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource
            ?: return finish(manga, chapter, Translation.State.ERROR)
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) {
            return finish(manga, chapter, Translation.State.TRANSLATED)
        }
        val queued = queueState.value.firstOrNull { it.chapter.id == chapter.id }
        if (queued != null) {
            // A chapter that failed before is tried again; one still waiting or working is left as it is
            if (queued.status != Translation.State.ERROR) return
            _queueState.update { it - queued }
        }
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val engine = if (teamMembers().isNotEmpty()) null else TextTranslators.withoutKeys(translationPreferences)
        if (engine == TextTranslators.MLKIT && !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) {
            // A toast from a background thread closes the app: it is shown on the main thread
            withContext(Dispatchers.Main) { context.toast(ATMR.strings.error_mlkit_language_unsupported) }
            return finish(manga, chapter, Translation.State.ERROR)
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        addToQueue(translation)
    }

    /** What [textTranslator] was built for; a different signature means it has to be built again. */
    private var builtFor = ""

    /** MANHWA: the team's working keys; with none, the engine chosen for "no keys". */
    private fun teamMembers(): List<TeamStore.Member> = teamStore.active()

    private fun translatorSignature(translation: Translation): String {
        val team = teamMembers().joinToString(",") { "${it.id}:${it.model}:${it.key.hashCode()}:${it.baseUrl}" }
        val style = with(translationPreferences) {
            "${translationInstructions().get().hashCode()}:${translationEngineTemperature().get()}:${translationEngineMaxOutputTokens().get()}"
        }
        val local = if (team.isEmpty()) eu.kanade.translation.local.LocalAiEngine.available(context).toString() + translationPreferences.translationStyleDialect().get() else ""
        return "${translation.fromLang}|${translation.toLang}|${translationPreferences.translationEngine().get()}|$style|$team|$local"
    }

    private fun buildTranslator(translation: Translation): TextTranslator {
        val team = teamMembers()
        return if (team.isNotEmpty()) {
            TeamTranslator(translation.fromLang, translation.toLang, teamStore, team)
        } else if (eu.kanade.translation.local.LocalAiEngine.available(context)) {
            // MANHWA on-device AI: no key works, the model on the phone translates (Google fills what it misses)
            eu.kanade.translation.local.LocalAiTranslator(
                context,
                translation.fromLang,
                translation.toLang,
                instructions = eu.kanade.translation.style.StyleProfile.instruction(translationPreferences, translation.toLang.name),
            )
        } else {
            TextTranslators.withoutKeys(translationPreferences)
                .build(translationPreferences, translation.fromLang, translation.toLang)
        }
    }

    /**
     * Pages already read (OCR) for chapters that failed later, so "try again" carries on from the translation
     * instead of reading every page again. Kept only for a few chapters.
     */
    private val readCache = object : android.util.LruCache<Long, ReadPages>(READ_CACHE_BYTES) {
        // Sized by the page slices kept for vision keys (a long webtoon chapter can hold tens of MB of them)
        override fun sizeOf(key: Long, value: ReadPages): Int =
            (value.images.values.sumOf { p -> p.tiles.sumOf { it.jpeg.size.toLong() } } + READ_PAGE_TEXT_BYTES * value.pages.size)
                .coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private class ReadPages(val pages: Map<String, PageTranslation>, val images: Map<String, PageImage>)

    /** Its own copy of the pages: translating changes the blocks in place, the kept reading must stay as read. */
    private fun Map<String, PageTranslation>.copied(): MutableMap<String, PageTranslation> =
        mapValuesTo(mutableMapOf()) { (_, page) -> page.copy(blocks = page.blocks.map { it.copy() }.toMutableList()) }

    private suspend fun translateChapter(translation: Translation) {
        val notificationId = Notifications.ID_NEW_CHAPTERS - 2000 - (translation.chapter.id % 1000).toInt()
        try {
            translation.status = Translation.State.TRANSLATING
            // Check if recognizer reinitialization is needed
            val current = textRecognizer
            if (current == null || translation.fromLang != current.language) {
                current?.close()
                textRecognizer = TextRecognizer(translation.fromLang)
            }
            val recognizer = textRecognizer!!
            // Check if translator reinitialization is needed: other languages, another engine, or the team changed
            val signature = translatorSignature(translation)
            if (signature != builtFor) {
                withContext(Dispatchers.IO) {
                    textTranslator.close()
                }
                textTranslator = buildTranslator(translation)
                builtFor = signature
            }
            // Directory where translations for a manga is stored
            val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)

            // translations save file
            val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)

            // Directory where chapter images is stored
            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.chapter.url,
                translation.manga.title,
                translation.source,
            ) ?: throw StepFailure(Translation.Note.NOT_DOWNLOADED)

            // Vision engines also get the page images, so they can read what the OCR missed
            val vision = textTranslator.aiPart != null && translationPreferences.translationVision().get()
            // MANHWA: sound effects stay as drawn; unsure readings are cut out to be read again from their image
            val skipSfx = translationPreferences.translationSkipSfx().get()
            val artText = translationPreferences.translationArtText().get()
            val healOcr = translationPreferences.translationOcrHealer().get()
            val canReread = textTranslator is TeamTranslator
            // MANHWA dual OCR: PaddleOCR with ML Kit (null when ML Kit reads alone or its models are not here yet)
            val paddle = eu.kanade.translation.ocr.PaddleOcrEngine.readerFor(context, translation.fromLang)
            val ocrMode = eu.kanade.translation.ocr.PaddleOcrEngine.mode(context)
            val unsureCrops = mutableListOf<Triple<String, Int, ByteArray>>()
            val streams = getChapterPages(chapterPath)
            val showProgress = progressNotifier(translation, notificationId)

            // 1. Read the text of every page (25–55%), or carry on from an earlier try
            val earlier = readCache.get(translation.chapter.id)
            val pages = earlier?.pages?.copied() ?: mutableMapOf()
            val images = earlier?.images?.toMutableMap() ?: mutableMapOf()
            if (earlier == null) {
                translation.advance(Translation.Stage.READING, 0, streams.size)
                showProgress()
                val tmpFile = translationMangaDir.createFile("tmp")!!
                /**
                 * saving the stream to tmp file cuz i can't get the
                 * BitmapFactory.decodeStream() to work with the stream from .cbz archive
                 */
                withContext(Dispatchers.IO) {
                    for ((index, entry) in streams.withIndex()) {
                        val (fileName, streamFn) = entry
                        coroutineContext.ensureActive()
                        // Shown while the reader goes first through the shared gate
                        translation.note(if (HeavyWork.readerBusy) Translation.Note.READER else null)
                        // One page at a time through the shared gate: the reader's live translation and drawing go first.
                        // A page that can't be read (broken image, out of memory) is skipped; the chapter goes on.
                        val pageTranslation = try {
                            HeavyWork.background<PageTranslation?> {
                            streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }
                            val decoded = PageImageProcessor.decode { context.contentResolver.openInputStream(tmpFile.uri)!! }
                                ?: return@background null
                            try {
                                // OCR in strips on the decoded page, then the colour behind every line
                                PageOcr.recognize(recognizer, decoded.bitmap, paddle, ocrMode).rescaled(1f / decoded.scale).also {
                                    if (skipSfx) it.blocks.removeAll { b -> b.kind.equals(PageOcr.SFX, ignoreCase = true) }
                                    if (!artText) it.blocks.forEach { b -> if (b.kind.equals(PageOcr.ART, ignoreCase = true)) b.kind = "" }
                                    if (healOcr) it.blocks.forEach { b -> b.text = OcrHealer.heal(b.text) }
                                    PageImageProcessor.prepare(it, decoded)
                                    if (vision) images[fileName] = PageImageProcessor.tiles(decoded)
                                    if (canReread) {
                                        it.blocks.forEachIndexed { i, b ->
                                            // Art text is always read again from its image: stylised letters fool the OCR
                                            val art = b.kind.equals(PageOcr.ART, ignoreCase = true)
                                            if ((b.ocrConfidence < AiTranslator.UNSURE_OCR || art) && unsureCrops.size < MAX_REREAD) {
                                                PageImageProcessor.crop(decoded, b)?.let { jpeg -> unsureCrops += Triple(fileName, i, jpeg) }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                decoded.bitmap.recycle()
                            }
                            }
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.WARN, e) { "Page $fileName could not be read, skipping it" }
                            null
                        }
                        translation.advance(Translation.Stage.READING, index + 1, streams.size)
                        showProgress()
                        if (pageTranslation == null) continue
                        if (pageTranslation.blocks.isNotEmpty() || fileName in images) pages[fileName] = pageTranslation
                    }
                }
                tmpFile.delete()
                // MANHWA: bubbles the OCR was not sure of are read again from their own image by a key that sees images
                if (unsureCrops.isNotEmpty()) {
                    translation.note(Translation.Note.AI)
                    val fixed = (textTranslator as? TeamTranslator)?.reread(unsureCrops.mapIndexed { i, c -> "$i" to c.third }).orEmpty()
                    fixed.forEach { (id, text) ->
                        val (fileName, index, _) = unsureCrops.getOrNull(id.toIntOrNull() ?: -1) ?: return@forEach
                        pages[fileName]?.blocks?.getOrNull(index)?.let { block ->
                            block.text = text
                            block.ocrConfidence = 1f
                        }
                    }
                    logcat { "Re-read ${fixed.size} of ${unsureCrops.size} unsure bubbles from their image" }
                }
                readCache.put(translation.chapter.id, ReadPages(pages.copied(), images.toMap()))
            }

            // 2. Plan (55–60%)
            translation.advance(Translation.Stage.PLANNING)
            showProgress()
            val src = translation.fromLang.name
            // MANHWA: spoken Egyptian lines have their own place in the translation library
            val tgt = eu.kanade.translation.style.StyleProfile.libraryTarget(translationPreferences, translation.toLang.name)
            // MANHWA: the same work from another source in the library shares this one's dictionary
            if (translation.manga.favorite) glossaryStore.registerWork(translation.manga.id, translation.manga.title)
            val useLibrary = translationPreferences.translationCollection().get()
            collection.limitBytes = translationPreferences.translationCollectionLimitMb().get().toLong() * 1024 * 1024

            // 3. Translate (60–92%), batch by batch, never waiting silently
            val ai = textTranslator.aiPart
            withContext(Dispatchers.IO) {
                // The series glossary keeps names consistent; new names found are added to it
                // MANHWA master codex: each genre's standard terms found in this chapter, under the series glossary
                val codex = codexFor(translation.fromLang, translation.toLang, pages.values.joinToString("\n") { page -> page.blocks.joinToString(" ") { it.text } })
                ai?.apply {
                    glossary = codex + glossaryStore.asMap(translation.manga.id)
                    learnTerms = true
                    discoveredTerms.clear()
                    // MANHWA smart glossary: who each name is (gender, how they speak) and the story so far
                    glossaryNotes = glossaryStore.notes(translation.manga.id)
                    richTerms = true
                    discoveredDetails.clear()
                    storySoFar = glossaryStore.storySoFar(translation.manga.id) + brainContext(translation.manga.id, translation.manga.title, translation.chapter.chapterNumber)
                    lessons = lessonStore.forPrompt(translation.manga.id)
                    fitHints = true
                    askSummary = true
                    this.skipSfx = skipSfx
                    summaries.clear()
                    onBatchDone = { done, total ->
                        translation.advance(Translation.Stage.TRANSLATING, done, total)
                        showProgress()
                    }
                    onRetry = { error -> translation.note(noteFor(error)) }
                    onAlive = { translation.touch() }
                }
                // MANHWA scout: the chapter's names and terms (with gender and how they speak) found in one call
                // before translating, so every key uses the same ones from the first page
                if (ai != null && pages.values.sumOf { it.blocks.size } >= SCOUT_MIN_LINES) {
                    translation.note(Translation.Note.AI)
                    runCatching {
                        val text = pages.values.joinToString("\n") { page -> page.blocks.joinToString(" | ") { it.text } }
                        val found = ai.scout(text)
                        glossaryStore.merge(
                            translation.manga.id,
                            found.associate { it.source to it.translation },
                            found.associate { it.source to (it.kind to it.gender) },
                            found.filter { it.note.isNotBlank() }.associate { it.source to it.note },
                        )
                        ai.glossary = codex + glossaryStore.asMap(translation.manga.id)
                        ai.glossaryNotes = glossaryStore.notes(translation.manga.id)
                    }.onFailure {
                        if (it is CancellationException) throw it
                        logcat(LogPriority.WARN, it) { "Scout failed, translating without it" }
                    }
                }
                // MANHWA on-device AI: the series glossary and the codex for the model on the phone too
                (textTranslator as? eu.kanade.translation.local.LocalAiTranslator)?.apply {
                    glossary = codex + glossaryStore.asMap(translation.manga.id)
                    // The phone shows its progress as it goes (it can be slow): never "stopped moving"
                    onBatchDone = { done, total ->
                        translation.advance(Translation.Stage.TRANSLATING, done, total)
                        showProgress()
                    }
                }
                // MANHWA collection: lines translated before, in any manhwa, are written at once; repeats inside
                // the chapter are translated once; only what is left goes to the translator
                val library = if (useLibrary) fromLibrary(pages, src, tgt, glossaryStore.asMap(translation.manga.id)) else LibraryPass.NONE
                translation.fromLibrary(library.percent)
                val work = pages.mapNotNull { (name, page) ->
                    val rest = page.blocks.filter { it !in library.known && !library.copies.containsKey(it) }
                    if (rest.isEmpty()) null else name to page.copy(blocks = rest.toMutableList())
                }.toMap(mutableMapOf())
                val workImages = images.filterKeys { it in work }
                // The team's plan for this chapter: what is heavy, how many calls, how long
                (textTranslator as? TeamTranslator)?.onPlan = { plan, keys ->
                    translation.planned(
                        Translation.Plan(
                            pages = streams.size,
                            heavy = plan.count(ChapterPlanner.Level.HEAVY),
                            normal = plan.count(ChapterPlanner.Level.NORMAL),
                            light = plan.count(ChapterPlanner.Level.LIGHT),
                            skipped = (streams.size - plan.levels.size).coerceAtLeast(0),
                            requests = plan.batches.size,
                            keys = keys,
                            estimateMs = ChapterPlanner.estimateMs(plan, keys),
                        ),
                    )
                }
                // Every finished batch goes to a reader waiting for this chapter (the whole page: the collection's
                // lines and the new ones, which are the same blocks), before the review and the save
                val chapterId = translation.chapter.id
                (textTranslator as? TeamTranslator)?.onPagesDone = { done ->
                    val ready = done.keys.mapNotNull { name ->
                        pages[name]?.let { page ->
                            name to page.copy(blocks = page.blocks.filter { it.translation.isNotBlank() }.map { it.copy() }.toMutableList())
                        }
                    }.toMap()
                    _partial.update { it + (chapterId to (it[chapterId].orEmpty() + ready)) }
                }
                translation.advance(Translation.Stage.TRANSLATING)
                translation.note(if (ai != null && work.isNotEmpty()) Translation.Note.AI else null)
                showProgress()
                val draftsBefore = ai?.useDrafts ?: false
                var chapterSummary = ""
                var termDetails = emptyMap<String, Pair<String, String>>()
                try {
                    // Suggestions from the collection go to the AI as drafts to follow or fix
                    if (library.hinted > 0) ai?.useDrafts = true
                    // Translate the text in blocks , this mutates the original blocks
                    if (work.isNotEmpty()) withWatchdog(translation) { textTranslator.translate(work, workImages) }
                } finally {
                    chapterSummary = ai?.summaries?.takeLast(SUMMARY_LINES)?.joinToString(" ").orEmpty()
                    termDetails = ai?.discoveredDetails?.mapValues { (_, info) -> info.kind to info.gender }.orEmpty()
                    (textTranslator as? TeamTranslator)?.onPagesDone = null
                    (textTranslator as? eu.kanade.translation.local.LocalAiTranslator)?.onBatchDone = null
                    ai?.apply {
                        useDrafts = draftsBefore
                        onBatchDone = null
                        onRetry = null
                        onAlive = null
                        glossaryNotes = emptyMap()
                        richTerms = false
                        storySoFar = ""
                        lessons = ""
                        fitHints = false
                        askSummary = false
                        this.skipSfx = false
                    }
                }
                mergeBack(pages, work, library)
                ai?.let { glossaryStore.merge(translation.manga.id, it.discoveredTerms, termDetails) }
                glossaryStore.addChapterSummary(translation.manga.id, translation.chapter.name, chapterSummary, translation.chapter.chapterNumber)
                // MANHWA work brain: who is who and what happened, learnt from this chapter in the background
                if (translationPreferences.translationSeriesBrain().get()) {
                    context.appGraph.seriesBrain.learnLater(
                        translation.manga.id,
                        translation.manga.title,
                        translation.chapter.name,
                        translation.chapter.chapterNumber,
                        chapterSummary,
                        pages.values.flatMap { it.blocks }.filter { it.translation.isNotBlank() }.map { it.text to it.translation },
                    )
                }

                // 4. Review (92–98%): the automatic check, then (accurate mode) the editor
                translation.advance(Translation.Stage.REVIEWING, 0, 3)
                showProgress()
                val glossaryAfter = glossaryStore.asMap(translation.manga.id)
                recheck(pages, glossaryAfter, translation)
                translation.advance(Translation.Stage.REVIEWING, 1, 3)
                showProgress()
                // MANHWA smart review: the whole chapter in accurate mode, otherwise only the bubbles that look doubtful
                val accurate = translationPreferences.translationTeamAccurate().get()
                val newTerms = ai?.discoveredTerms?.keys?.toSet().orEmpty()
                val doubts = if (accurate) null else doubtful(pages, glossaryAfter, translation, newTerms)
                if (ai != null && (accurate || !doubts.isNullOrEmpty())) {
                    translation.note(Translation.Note.AI)
                    // With the team, the review goes to its strongest key and model through the router
                    val team = textTranslator as? TeamTranslator
                    val fixed = runCatching {
                        if (team != null) {
                            team.asEditor { editor -> edit(editor, pages, translation.manga.id, translation.manga.title, doubts) }
                        } else {
                            edit(ai, pages, translation.manga.id, translation.manga.title, doubts)
                        }
                    }
                        .onFailure {
                            if (it is CancellationException) throw it
                            logcat(LogPriority.WARN, it) { "Editor failed, keeping the team's translation" }
                        }
                        .getOrDefault(emptyList())
                    if (useLibrary && fixed.isNotEmpty()) {
                        collection.learn(src, tgt, translation.manga.id, glossaryAfter, fixed, TranslationCollection.Source.EDITOR)
                    }
                }
                // MANHWA second opinion: the doubtful and important lines are translated again by another key, and
                // the better translation of each is kept
                var consensus = eu.kanade.translation.team.TranslationConsensusEngine.Outcome(0, 0)
                if (ai != null && translationPreferences.translationConsensus().get() && textTranslator is TeamTranslator) {
                    val lines = eu.kanade.translation.team.TranslationConsensusEngine.pick(pages, doubts?.keys.orEmpty(), newTerms)
                    val genders = glossaryStore.get(translation.manga.id).filter { it.gender.isNotBlank() }.associate { it.source to it.gender }
                    consensus = runCatching {
                        eu.kanade.translation.team.TranslationConsensusEngine.review(
                            store = teamStore,
                            firstKeyId = teamStore.lead()?.id,
                            lines = lines,
                            glossary = glossaryAfter,
                            genders = genders,
                            title = translation.manga.title,
                            targetLabel = translation.toLang.label,
                            targetLatin = translation.toLang.code !in NON_LATIN_TARGETS,
                        )
                    }.onFailure { if (it is CancellationException) throw it }.getOrDefault(consensus)
                    if (useLibrary && consensus.replacedLines.isNotEmpty()) {
                        collection.learn(
                            src, tgt, translation.manga.id, glossaryAfter,
                            consensus.replacedLines.map { (b, _) -> TranslationCollection.Line(b.text, b.translation, b.kind) },
                            TranslationCollection.Source.EDITOR,
                        )
                    }
                }
                // MANHWA orchestra: the phone judges the lines two keys did equally well (free); the reader may still choose
                if (consensus.ties.isNotEmpty() && eu.kanade.translation.orchestra.OrchestraStore.active(context)) {
                    val style = eu.kanade.translation.style.StyleProfile.instruction(translationPreferences, translation.toLang.name)
                    var judged = 0
                    // The phone judges within a time limit: the chapter is never held up by a slow phone
                    val judgeUntil = System.currentTimeMillis() + JUDGE_BUDGET_MS
                    consensus.ties.forEach { (source, kept, other) ->
                        if (System.currentTimeMillis() > judgeUntil || eu.kanade.translation.orchestra.OrchestraStore.slowNow(context)) return@forEach
                        translation.touch()
                        val verdict = runCatching { eu.kanade.translation.orchestra.LocalJudge.pick(context, source, kept, other, style, glossaryAfter) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        if (verdict == 2) {
                            pages.values.flatMap { it.blocks }.firstOrNull { it.text == source && it.translation == kept }?.translation = other
                        }
                        if (verdict != null) judged++
                    }
                    eu.kanade.translation.orchestra.OrchestraStore.record(context, judged = judged)
                }
                // MANHWA quality badge: where the lines came from and what is still doubtful
                runCatching {
                    val stillDoubtful = doubtful(pages, glossaryAfter, translation, emptySet()).keys.map { it.text }.take(20)
                    val aiLines = work.values.sumOf { it.blocks.size }
                    val allLines = pages.values.sumOf { it.blocks.count { b -> b.translation.isNotBlank() } }
                    eu.kanade.translation.team.ChapterQuality(translationPreferences.translationChapterQuality()).save(
                        translation.chapter.id,
                        eu.kanade.translation.team.ChapterQuality.Report(
                            fromLibrary = (allLines - aiLines).coerceAtLeast(0),
                            byAi = aiLines.coerceAtMost(allLines),
                            reviewed = consensus.reviewed + (doubts?.size ?: 0),
                            changed = consensus.replaced,
                            doubtful = stillDoubtful,
                            choices = consensus.ties.map { (src, a, b) -> eu.kanade.translation.team.ChapterQuality.Tie(src, a, b) },
                        ),
                    )
                }
                translation.advance(Translation.Stage.REVIEWING, 2, 3)
                showProgress()

                // Bubbles added by the vision model: placed on their real letters, or dropped
                val toPlace = pages.filterValues { page -> page.blocks.any { it.needsSnap } }
                for ((i, entry) in toPlace.entries.withIndex()) {
                    val (name, page) = entry
                    val open = streams.firstOrNull { it.first == name }?.second ?: continue
                    val bytes = runCatching { open().use { it.readBytes() } }.getOrNull() ?: continue
                    HeavyWork.background {
                        PageImageProcessor.decode { java.io.ByteArrayInputStream(bytes) }?.let { decoded ->
                            try {
                                PageImageProcessor.prepare(page, decoded)
                            } finally {
                                decoded.bitmap.recycle()
                            }
                        }
                    }
                    if (i == toPlace.size - 1) {
                        translation.advance(Translation.Stage.REVIEWING, 3, 3)
                        showProgress()
                    }
                }
                pages.values.forEach { PageOcr.finish(it, keepSfxArt = skipSfx) }
                pages.entries.removeAll { it.value.blocks.isEmpty() }
            }

            // 5. Save (98–100%) and make sure the file is really there before calling the chapter done
            translation.advance(Translation.Stage.SAVING)
            showProgress()
            try {
                saveAtomically(translationMangaDir, saveFile, pages)
            } catch (e: Exception) {
                throw StepFailure(Translation.Note.SAVE_FAILED, e.message)
            }
            val saved = provider.findTranslationFile(translation.chapter.name, translation.chapter.scanlator, translation.manga.title, translation.source)
            if (saved == null || saved.length() <= 0L) throw StepFailure(Translation.Note.SAVE_FAILED)
            translation.advance(Translation.Stage.SAVING, 1, 1)
            readCache.remove(translation.chapter.id)
            // Enough new lessons: the lead turns them into short rules for the next chapters
            textTranslator.aiPart?.let { lead -> lessonStore.compressIfDue(translation.manga.id, lead) }
            // Everything this chapter taught goes into the collection, for every manhwa after it
            if (useLibrary && textTranslator.aiPart != null && !textTranslator.fellBack) {
                withContext(Dispatchers.IO) {
                    val lines = pages.values.flatMap { it.blocks }
                        .filter { it.translation.isNotBlank() && !(skipSfx && it.kind.equals(PageOcr.SFX, ignoreCase = true)) }
                        .map { TranslationCollection.Line(it.text, it.translation, it.kind) }
                    collection.learn(src, tgt, translation.manga.id, glossaryStore.asMap(translation.manga.id), lines, TranslationCollection.Source.TEAM)
                }
            }
            // MANHWA: the chapter's lines can be searched later from the search in translations
            withContext(Dispatchers.IO) {
                runCatching {
                    context.appGraph.translationSearchIndex
                        .index(translation.manga.id, translation.manga.title, translation.chapter.id, translation.chapter.name, pages)
                }
            }
            translation.status = Translation.State.TRANSLATED
            context.cancelNotification(notificationId)
            context.notify(
                Notifications.ID_NEW_CHAPTERS - 1000 - (translation.chapter.id % 1000).toInt(),
                Notifications.CHANNEL_NEW_CHAPTERS,
            ) {
                setSmallIcon(R.drawable.ic_translate)
                setContentTitle(translation.manga.title)
                setContentText(context.stringResource(ATMR.strings.notification_translated, translation.chapter.name))
                setAutoCancel(true)
                setContentIntent(NotificationReceiver.openChapterPendingActivity(context, translation.manga, translation.chapter))
            }
        } catch (error: Throwable) {
            context.cancelNotification(notificationId)
            // Paused or stopped: the queue sets the status itself
            if (error is CancellationException) throw error
            val failure = error as? StepFailure
            translation.fail(failure?.note ?: noteFor(error), failure?.detail ?: error.message?.take(160))
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        }
    }

    /** What the collection did for a chapter: lines written at once, repeats waiting for their first copy, hints. */
    private class LibraryPass(
        val known: MutableSet<TranslationBlock>,
        val copies: IdentityHashMap<TranslationBlock, TranslationBlock>,
        val hinted: Int,
        val percent: Int,
    ) {
        companion object {
            val NONE = LibraryPass(Collections.newSetFromMap(IdentityHashMap()), IdentityHashMap(), 0, 0)
        }
    }

    /** The master codex terms of [text], for English or Korean to Arabic (the packs are written for those). */
    private fun codexFor(from: TextRecognizerLanguage, to: TextTranslatorLanguage, text: String): Map<String, String> =
        if ((from == TextRecognizerLanguage.ENGLISH || from == TextRecognizerLanguage.KOREAN) && to == TextTranslatorLanguage.ARABIC) {
            eu.kanade.translation.collection.ManhwaCodex.termsIn(text, translationPreferences.translationCodexPacks().get())
        } else {
            emptyMap()
        }

    private fun fromLibrary(
        pages: Map<String, PageTranslation>,
        src: String,
        tgt: String,
        glossary: Map<String, String>,
    ): LibraryPass {
        val known: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
        val copies = IdentityHashMap<TranslationBlock, TranslationBlock>()
        val firstOf = HashMap<String, TranslationBlock>()
        var hinted = 0
        var lines = 0
        for (page in pages.values) {
            for (block in page.blocks) {
                if (block.text.isBlank()) continue
                lines++
                val hit = collection.lookup(src, tgt, block.text, glossary)
                if (hit?.sure == true) {
                    block.translation = hit.translation
                    known += block
                    continue
                }
                // The same short line again in this chapter ("!!!", "Huff…"): translated once, copied after
                val line = TextKey.normalize(block.text)
                if (line.length <= REPEAT_MAX_LENGTH) {
                    val first = firstOf.putIfAbsent(line, block)
                    if (first != null) {
                        copies[block] = first
                        continue
                    }
                }
                if (hit != null) {
                    block.translation = hit.translation
                    hinted++
                }
            }
        }
        collection.record(lines, known.size)
        return LibraryPass(known, copies, hinted, if (lines > 0) known.size * 100 / lines else 0)
    }

    /**
     * Puts the translated lines back into the chapter's pages, in their places: the lines from the collection,
     * the repeats (given their first copy's translation) and what the translator returned, including bubbles
     * the vision model added and without the ones it dropped.
     */
    private fun mergeBack(pages: MutableMap<String, PageTranslation>, work: Map<String, PageTranslation>, library: LibraryPass) {
        for ((name, page) in pages) {
            val done = work[name] ?: continue
            val returned: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
            returned += done.blocks
            val merged = page.blocks.filterTo(mutableListOf()) { it in library.known || library.copies.containsKey(it) || it in returned }
            val placed: MutableSet<TranslationBlock> = Collections.newSetFromMap(IdentityHashMap())
            placed += merged
            done.blocks.filterTo(merged) { it !in placed }
            page.blocks = merged
        }
        library.copies.forEach { (repeat, first) ->
            repeat.translation = first.translation
            if (repeat.kind.isBlank()) repeat.kind = first.kind
        }
    }

    /**
     * MANHWA automatic check: every bubble is checked on the phone (empty, left untranslated, far too long, a
     * glossary name lost); only the flagged ones are translated once more, by the same translator or team.
     */
    private suspend fun recheck(pages: MutableMap<String, PageTranslation>, glossary: Map<String, String>, translation: Translation) {
        val latin = translation.toLang.code !in NON_LATIN_TARGETS
        val flagged = pages.mapNotNull { (name, page) ->
            // Sound effects are left as drawn on purpose: never "fixed" here
            val bad = page.blocks.filterNot { it.kind.equals(PageOcr.SFX, ignoreCase = true) }.filter {
                QualityCheck.check(it.text, it.translation, glossary, latin) != null ||
                    // Far more than fits its (real, detected) bubble: asked again, with the size it has
                    (it.hasBubble && it.translation.length > bubbleCharBudget(it) * OVERFLOW_FACTOR)
            }
            if (bad.isEmpty()) null else name to page.copy(blocks = bad.toMutableList())
        }.toMap(mutableMapOf())
        if (flagged.isEmpty()) return
        logcat { "Automatic check: ${flagged.values.sumOf { it.blocks.size }} bubbles translated again" }
        // The chapter's plan stays the one shown, not this small second pass
        (textTranslator as? TeamTranslator)?.onPlan = null
        val before = flagged.values.flatMap { it.blocks }.associateWith { it.translation }
        val ai = textTranslator.aiPart
        ai?.fitHints = true
        try {
            runCatching { withWatchdog(translation) { textTranslator.translate(flagged) } }
                .onFailure { if (it is CancellationException) throw it }
        } finally {
            ai?.fitHints = false
        }
        // MANHWA: the second try is kept only when it is better: it passes the check where the first failed, or
        // fails no worse and fits its bubble better
        before.forEach { (block, old) ->
            val new = block.translation
            val newBad = new.isBlank() || QualityCheck.check(block.text, new, glossary, latin) != null
            val oldBad = QualityCheck.check(block.text, old, glossary, latin) != null
            val budget = if (block.hasBubble) bubbleCharBudget(block) * OVERFLOW_FACTOR else Int.MAX_VALUE.toFloat()
            val better = when {
                new.isBlank() -> false
                oldBad && !newBad -> true
                !oldBad && newBad -> false
                else -> (new.length <= budget) || new.length < old.length
            }
            if (!better) block.translation = old
        }
    }

    /**
     * MANHWA: the bubbles worth a second look by the editor when the whole chapter is not reviewed: still failing the
     * automatic check, read with low OCR confidence, carrying a name found in this chapter, or longer than their
     * bubble. Each with why, so the editor looks at the right thing. At most [MAX_DOUBTS].
     */
    /** MANHWA work brain: the facts known up to [chapter], for the translator (empty when it is off). */
    private fun brainContext(mangaId: Long, title: String, chapter: Double): String {
        if (!translationPreferences.translationSeriesBrain().get()) return ""
        val facts = runCatching { context.appGraph.seriesBrain.contextFor(mangaId, title, chapter) }.getOrDefault("")
        return if (facts.isBlank()) "" else "\nStory bible (who is who now, what happened):\n$facts"
    }

    private fun doubtful(
        pages: Map<String, PageTranslation>,
        glossary: Map<String, String>,
        translation: Translation,
        newTerms: Set<String>,
    ): Map<TranslationBlock, String> {
        val latin = translation.toLang.code !in NON_LATIN_TARGETS
        val result = LinkedHashMap<TranslationBlock, String>()
        for (page in pages.values) {
            for (block in page.blocks) {
                if (block.translation.isBlank() || block.kind.equals(PageOcr.SFX, ignoreCase = true)) continue
                val why = when {
                    QualityCheck.check(block.text, block.translation, glossary, latin) != null -> "failed the automatic check"
                    block.ocrConfidence < AiTranslator.UNSURE_OCR -> "OCR reading uncertain"
                    block.hasBubble && block.translation.length > bubbleCharBudget(block) * OVERFLOW_FACTOR -> "too long for its bubble"
                    newTerms.any { term -> term.length >= 2 && block.text.contains(term, ignoreCase = true) } -> "has a name new in this chapter"
                    else -> null
                }
                if (why != null) result[block] = why
                if (result.size >= MAX_DOUBTS) return result
            }
        }
        return result
    }

    /**
     * MANHWA editor (accurate mode): the team's lead reads the chapter and corrects only what is wrong. Returns
     * the corrected lines, for the collection.
     */
    private suspend fun edit(
        ai: AiTranslator,
        pages: Map<String, PageTranslation>,
        mangaId: Long,
        title: String,
        only: Map<TranslationBlock, String>? = null,
    ): List<TranslationCollection.Line> {
        ai.glossary = glossaryStore.asMap(mangaId)
        ai.glossaryNotes = glossaryStore.notes(mangaId)
        ai.storySoFar = glossaryStore.storySoFar(mangaId)
        ai.lessons = lessonStore.forPrompt(mangaId)
        try {
            val all = pages.entries.flatMap { (name, page) -> page.blocks.mapIndexed { i, block -> "$name#$i" to block } }
            val blocks = all
                .filter { it.second.translation.isNotBlank() && !it.second.kind.equals(PageOcr.SFX, ignoreCase = true) }
                .filter { only == null || it.second in only }
                .toMap()
            // Each line with its kind and the two bubbles before it, so tone and pronouns are judged in context
            val position = all.withIndex().associate { (i, entry) -> entry.first to i }
            val context = blocks.mapValues { (id, block) ->
                val at = position.getValue(id)
                val before = (maxOf(0, at - 2) until at).map { all[it].second }.filter { it.translation.isNotBlank() }
                    .map { "${it.text.take(80)} => ${it.translation.take(80)}" }
                AiTranslator.ReviewContext(kind = block.kind, before = before, why = only?.get(block).orEmpty())
            }
            val fixes = ai.review(blocks.mapValues { (_, block) -> block.text to block.translation }, context)
            return fixes.mapNotNull { (id, text) ->
                val block = blocks[id] ?: return@mapNotNull null
                // Every fix is a lesson for the next chapters
                lessonStore.add(mangaId, LessonStore.Lesson(block.text, block.translation, text, LessonStore.Origin.EDITOR.name), title)
                block.translation = text
                TranslationCollection.Line(block.text, text, block.kind)
            }
        } finally {
            ai.glossaryNotes = emptyMap()
            ai.storySoFar = ""
            ai.lessons = ""
        }
    }

    /** A step that failed for a known reason, shown to the reader as it is. */
    private class StepFailure(val note: Translation.Note, val detail: String? = null) : Exception(note.name)

    private fun noteFor(error: Throwable): Translation.Note = when {
        error is StepFailure -> error.note
        error is AiTranslator.ApiException && (error.code == 401 || error.code == 403) -> Translation.Note.KEY_INVALID
        error is AiTranslator.ApiException && error.code == 429 -> Translation.Note.KEY_LIMIT
        error is java.net.UnknownHostException || error is java.net.ConnectException -> Translation.Note.NO_INTERNET
        error is java.io.IOException -> Translation.Note.RETRY
        else -> Translation.Note.OTHER
    }

    /**
     * Runs [block], but gives up when the chapter has not moved forward for [STALL_MS]: a call that hangs ends
     * as a clear failure the reader can retry, never as a spinner that turns forever.
     */
    private suspend fun <T> withWatchdog(translation: Translation, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        while (!work.isCompleted) {
            withTimeoutOrNull(WATCH_MS) { work.join() }
            if (!work.isCompleted && System.currentTimeMillis() - translation.progress.value.updatedAt > STALL_MS) {
                work.cancel()
                throw StepFailure(Translation.Note.STALLED)
            }
        }
        work.await()
    }

    /** Shows the chapter's real percentage in a notification, at most once a second, while it translates. */
    private fun progressNotifier(translation: Translation, id: Int): () -> Unit {
        var last = 0L
        return {
            val now = System.currentTimeMillis()
            if (now - last >= 1_000L) {
                last = now
                val percent = translation.progress.value.percent
                context.notify(id, Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
                    setSmallIcon(R.drawable.ic_translate)
                    setContentTitle(translation.chapter.name)
                    setContentText("${translation.manga.title} · $percent%")
                    setProgress(100, percent, false)
                    setOngoing(true)
                    setOnlyAlertOnce(true)
                    // Opens the manga screen, where every chapter shows its ring
                    setContentIntent(NotificationReceiver.openEntryPendingActivity(context, translation.manga.id))
                }
            }
        }
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            val reader = chapterPath.archiveReader(context)
            return reader.useEntries { entries ->
                entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                        Pair(entry.name) { reader.getInputStream(entry.name)!! }
                    }.toList()
            }
        } else {
            return chapterPath.listFiles()!!.filter { ImageUtil.isImage(it.name) }
                .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }.map { entry ->
                Pair(entry.name!!) { entry.openInputStream() }
            }.toList()
        }
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        // Checked and added in one step: two callers at once never queue the same chapter twice
        _queueState.update { queue ->
            if (queue.any { it.chapter.id == translation.chapter.id && it.status != Translation.State.ERROR }) {
                queue
            } else {
                translation.status = Translation.State.QUEUE
                queue.filterNot { it.chapter.id == translation.chapter.id } + translation
            }
        }
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter { predicate(it) }
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
    }

    companion object {
        /** How often the watchdog looks, and how long without any progress counts as stuck. */
        const val WATCH_MS = 5_000L
        const val STALL_MS = 180_000L
        const val JUDGE_BUDGET_MS = 45_000L
        private const val READ_CACHE_BYTES = 24 * 1024 * 1024
        private const val READ_PAGE_TEXT_BYTES = 4 * 1024L

        /** Lines up to this long that repeat inside a chapter are translated once. */
        const val REPEAT_MAX_LENGTH = 40

        /** Sentences kept as a chapter's summary for the next chapters. */
        const val SUMMARY_LINES = 3

        /** Below this many lines the chapter is too short to scout. */
        const val SCOUT_MIN_LINES = 8

        /** A translation this many times longer than its bubble holds is asked again. */
        const val OVERFLOW_FACTOR = 1.8f

        /** Most bubbles the editor looks at again when the whole chapter is not reviewed. */
        const val MAX_DOUBTS = 40

        /** Most unsure bubbles read again from their image per chapter. */
        const val MAX_REREAD = 30

        /** Target languages not written in Latin letters (English words left in them are a mistake). */
        val NON_LATIN_TARGETS = setOf("ar", "fa", "ur", "he", "ru", "uk", "bg", "el", "hi", "bn", "th", "zh", "ja", "ko")
    }
}
