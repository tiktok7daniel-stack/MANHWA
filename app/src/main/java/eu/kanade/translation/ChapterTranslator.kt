package eu.kanade.translation

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
import eu.kanade.translation.data.PageImage
import eu.kanade.translation.data.PageImageProcessor
import eu.kanade.translation.glossary.GlossaryStore
import eu.kanade.translation.live.PageOcr
import eu.kanade.translation.live.PageOcr.rescaled
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.aiPart
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

    private fun finish(manga: Manga, chapter: Chapter, state: Translation.State) {
        _finished.tryEmit(Finished(manga.id, chapter.id, state))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    private var textRecognizer: TextRecognizer
    private var textTranslator: TextTranslator

    init {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        textRecognizer = TextRecognizer(fromLang)
        textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
            .build(translationPreferences, fromLang, toLang)
    }

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

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

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
        try {
            // One chapter at a time: the recognizer, translator and glossary are shared
            translationMutex.withLock { translateChapter(translation) }
            finish(translation.manga, translation.chapter, translation.status)
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
            stop()
        }
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
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        if (engine == TextTranslators.MLKIT && !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) {
            // A toast from a background thread closes the app: it is shown on the main thread
            withContext(Dispatchers.Main) { context.toast(ATMR.strings.error_mlkit_language_unsupported) }
            return finish(manga, chapter, Translation.State.ERROR)
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        addToQueue(translation)
    }

    /**
     * Pages already read (OCR) for chapters that failed later, so "try again" carries on from the translation
     * instead of reading every page again. Kept only for a few chapters.
     */
    private val readCache = object : android.util.LruCache<Long, ReadPages>(4) {}

    private class ReadPages(val pages: Map<String, PageTranslation>, val images: Map<String, PageImage>)

    /** Its own copy of the pages: translating changes the blocks in place, the kept reading must stay as read. */
    private fun Map<String, PageTranslation>.copied(): MutableMap<String, PageTranslation> =
        mapValuesTo(mutableMapOf()) { (_, page) -> page.copy(blocks = page.blocks.map { it.copy() }.toMutableList()) }

    private suspend fun translateChapter(translation: Translation) {
        val notificationId = Notifications.ID_NEW_CHAPTERS - 2000 - (translation.chapter.id % 1000).toInt()
        try {
            translation.status = Translation.State.TRANSLATING
            // Check if recognizer reinitialization is needed
            if (translation.fromLang != textRecognizer.language) {
                textRecognizer.close()
                textRecognizer = TextRecognizer(translation.fromLang)
            }
            // Check if translator reinitialization is needed
            if (translation.fromLang != textTranslator.fromLang || translation.toLang != textTranslator.toLang) {
                withContext(Dispatchers.IO) {
                    textTranslator.close()
                }
                textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
                    .build(translationPreferences, translation.fromLang, translation.toLang)
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
                        // One page at a time through the shared gate: the reader's live translation and drawing go first
                        val pageTranslation = HeavyWork.background<PageTranslation?> {
                            streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }
                            val decoded = PageImageProcessor.decode { context.contentResolver.openInputStream(tmpFile.uri)!! }
                                ?: return@background null
                            try {
                                // OCR in strips on the decoded page, then the colour behind every line
                                PageOcr.recognize(textRecognizer, decoded.bitmap).rescaled(1f / decoded.scale).also {
                                    PageImageProcessor.prepare(it, decoded)
                                    if (vision) images[fileName] = PageImageProcessor.tiles(decoded)
                                }
                            } finally {
                                decoded.bitmap.recycle()
                            }
                        }
                        translation.advance(Translation.Stage.READING, index + 1, streams.size)
                        showProgress()
                        if (pageTranslation == null) continue
                        if (pageTranslation.blocks.isNotEmpty() || fileName in images) pages[fileName] = pageTranslation
                    }
                }
                tmpFile.delete()
                readCache.put(translation.chapter.id, ReadPages(pages.copied(), images.toMap()))
            }

            // 2. Plan (55–60%)
            translation.advance(Translation.Stage.PLANNING)
            showProgress()

            // 3. Translate (60–92%), batch by batch, never waiting silently
            val ai = textTranslator.aiPart
            withContext(Dispatchers.IO) {
                // The series glossary keeps names consistent; new names found are added to it
                ai?.apply {
                    glossary = glossaryStore.asMap(translation.manga.id)
                    learnTerms = true
                    discoveredTerms.clear()
                    onBatchDone = { done, total ->
                        translation.advance(Translation.Stage.TRANSLATING, done, total)
                        showProgress()
                    }
                    onRetry = { error -> translation.note(noteFor(error)) }
                }
                translation.advance(Translation.Stage.TRANSLATING)
                translation.note(if (ai != null) Translation.Note.AI else null)
                showProgress()
                try {
                    // Translate the text in blocks , this mutates the original blocks
                    withWatchdog(translation) { textTranslator.translate(pages, images) }
                } finally {
                    ai?.onBatchDone = null
                    ai?.onRetry = null
                }
                ai?.let { glossaryStore.merge(translation.manga.id, it.discoveredTerms) }

                // 4. Review (92–98%): bubbles added by the vision model placed on their real letters, or dropped
                val toPlace = pages.filterValues { page -> page.blocks.any { it.needsSnap } }
                translation.advance(Translation.Stage.REVIEWING, 0, toPlace.size)
                showProgress()
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
                    translation.advance(Translation.Stage.REVIEWING, i + 1, toPlace.size)
                    showProgress()
                }
                pages.values.forEach(PageOcr::finish)
                pages.entries.removeAll { it.value.blocks.isEmpty() }
            }

            // 5. Save (98–100%) and make sure the file is really there before calling the chapter done
            translation.advance(Translation.Stage.SAVING)
            showProgress()
            try {
                translationMangaDir.createFile(saveFile)!!.openOutputStream().use { Json.encodeToStream(pages, it) }
            } catch (e: Exception) {
                throw StepFailure(Translation.Note.SAVE_FAILED, e.message)
            }
            val saved = provider.findTranslationFile(translation.chapter.name, translation.chapter.scanlator, translation.manga.title, translation.source)
            if (saved == null || saved.length() <= 0L) throw StepFailure(Translation.Note.SAVE_FAILED)
            translation.advance(Translation.Stage.SAVING, 1, 1)
            readCache.remove(translation.chapter.id)
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
            return chapterPath.listFiles()!!.filter { ImageUtil.isImage(it.name) }.map { entry ->
                Pair(entry.name!!) { entry.openInputStream() }
            }.toList()
        }
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update {
            it + translation
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

    private companion object {
        /** How often the watchdog looks, and how long without any progress counts as stuck. */
        const val WATCH_MS = 5_000L
        const val STALL_MS = 180_000L
    }
}
