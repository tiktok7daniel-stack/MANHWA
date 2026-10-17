package eu.kanade.translation

import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import java.util.concurrent.ConcurrentHashMap
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR
import mihon.app.di.appGraph
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences

@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider,
    private val sourceManager: SourceManager,
    private val translationPreferences: TranslationPreferences,
    private val translator: ChapterTranslator,
    private val getManga: tachiyomi.domain.manga.interactor.GetManga,
    private val getChapter: tachiyomi.domain.chapter.interactor.GetChapter,
    private val collection: eu.kanade.translation.collection.TranslationCollection,
    private val glossaryStore: eu.kanade.translation.glossary.GlossaryStore,
    private val downloadManager: Lazy<eu.kanade.tachiyomi.data.download.DownloadManager>,
) {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Put back once per app start, before the queue is saved again (an empty queue would erase it). */
    private val restored = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val restoring = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var resuming: Boolean? = null

    init {
        scope.launch {
            restorePending()
            // From now on the queue is saved on every change (chapters waiting or being translated); a change means
            // progress, so the count of restores starts again from 0
            translator.queueState
                .drop(1)
                .onEach { queue ->
                    val pending = queue.filter { TranslationJob.active(it.status) }.joinToString(",") { "${it.manga.id}:${it.chapter.id}" }
                    translationPreferences.translationPendingQueue().set(if (pending.isEmpty()) "" else "0|$pending")
                    // MANHWA: chapters waiting are carried on even after Android closed the app
                    val waiting = pending.isNotEmpty()
                    // Whatever queued the chapter (a button, a download, the reader, a fused work), it runs as a
                    // foreground job with its notification: without it Android freezes the app in the background
                    if (waiting && !translator.isPaused) runCatching { TranslationJob.start(context) }
                    if (waiting != resuming) {
                        resuming = waiting
                        runCatching { TranslationJob.keepResuming(context, waiting) }
                    }
                }
                .launchIn(this)
        }
    }

    /** Why Android ended the app's last run (ApplicationExitInfo.REASON_*), when it can tell (Android 11+). */
    private fun lastExitReason(): Int? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.reason
    }.getOrNull()

    /** Puts the saved queue back after the app was closed (once), and keeps translating it in the background. */
    suspend fun restorePending() {
        if (!restoring.compareAndSet(false, true)) return restored.await()
        try {
            val stored = translationPreferences.translationPendingQueue().get()
            if (stored.isBlank()) return
            val restores = stored.substringBefore('|', "0").toIntOrNull() ?: 0
            val saved = stored.substringAfter('|')
            // Put back this often without any chapter finishing: something in it brings the app down, let it go
            if (restores >= MAX_RESTORES) {
                translationPreferences.translationPendingQueue().set("")
                return
            }
            // MANHWA: only a crash counts against the queue; Android or the phone maker closing the app to free
            // memory (with the model on the phone, the app is the biggest one) is not the queue's fault
            val crashed = RestoreRules.crashed(lastExitReason())
            translationPreferences.translationPendingQueue().set("${if (crashed) restores + 1 else restores}|$saved")
            saved.split(',').mapNotNull { entry ->
                val (mangaId, chapterId) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                (mangaId.toLongOrNull() ?: return@mapNotNull null) to (chapterId.toLongOrNull() ?: return@mapNotNull null)
            }.forEach { (mangaId, chapterId) ->
                if (queueState.value.any { it.chapter.id == chapterId }) return@forEach
                // One chapter that can't be read back never stops the others from coming back
                try {
                    val manga = getManga.await(mangaId) ?: return@forEach
                    val chapter = getChapter.await(chapterId) ?: return@forEach
                    translator.queueChapter(manga, chapter)
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logcat(LogPriority.WARN, e) { "Saved chapter $chapterId not put back in the queue" }
                }
            }
            if (hasWork()) {
                TranslationJob.start(context)
                runCatching { TranslationJob.keepResuming(context, true) }
            }
        } finally {
            restored.complete(Unit)
        }
    }

    /** Chapters still waiting or being translated. */
    fun hasWork(): Boolean = translator.queueState.value.any { TranslationJob.active(it.status) }

    /** How many times each chapter that failed for a passing reason was put back in the queue. */
    private val retries = ConcurrentHashMap<Long, Int>()

    /**
     * MANHWA: chapters that failed for a reason that passes (no internet for a moment, a stalled page, a key out
     * of quota for now) are queued again, a few times; true when one was. The ones given up on are told to the
     * reader in a notification, once.
     */
    suspend fun retryFailed(): Boolean {
        val failed = queueState.value.filter { it.status == Translation.State.ERROR }
        val again = failed.filter { RetryRules.retry(it.progress.value.note, retries[it.chapter.id] ?: 0) }
        again.forEach { t ->
            retries.merge(t.chapter.id, 1, Int::plus)
            runCatching { translator.queueChapter(t.manga, t.chapter) }
                .onFailure { logcat(LogPriority.WARN, it) { "Failed chapter ${t.chapter.id} not queued again" } }
        }
        val givenUp = failed.filter { it !in again && reported.add(it.chapter.id) }
        if (givenUp.isNotEmpty()) notifyFailed(givenUp)
        return again.isNotEmpty()
    }

    private val reported: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private fun notifyFailed(chapters: List<Translation>) {
        runCatching {
            val text = chapters.joinToString("\n") { "${it.manga.title} · ${it.chapter.name}" }
            val notification = context.notificationBuilder(eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_DOWNLOADER_ERROR) {
                setContentTitle(context.stringResource(ATMR.strings.tj_failed, chapters.size))
                setContentText(text)
                setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                setSmallIcon(eu.kanade.tachiyomi.R.drawable.ic_translate)
                setAutoCancel(true)
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    setContentIntent(android.app.PendingIntent.getActivity(context, 1, it, android.app.PendingIntent.FLAG_IMMUTABLE))
                }
            }.build()
            context.notify(eu.kanade.tachiyomi.data.notification.Notifications.ID_NEW_CHAPTERS - 3002, notification)
        }.onFailure { logcat(LogPriority.WARN, it) { "Failed chapters not told" } }
    }

    val isPaused: Boolean
        get() = translator.isPaused

    /** Chapters to translate as soon as their download finishes (asked for by MANHWA AI). */
    val translateAfterDownload: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    /** Every chapter's end (translated, failed or turned away), so screens waiting on one always hear of it. */
    val finished
        get() = translator.finished

    /** Pages already translated of chapters still in progress (see ChapterTranslator.partial). */
    val partial
        get() = translator.partial

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null) = translator.stop(reason)

    fun startTranslation() {
        if (!translator.isRunning) translator.start()
        // MANHWA: always with the "translating" notification, so Android keeps it going while other apps are used
        if (hasWork()) TranslationJob.start(context)
    }

    fun pauseTranslation() {
        translator.pause()
        translator.stop()
    }

    fun clearQueue() {
        translator.clearQueue()
        translator.stop()
    }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? {
        return queueState.value.find { it.chapter.id == chapterId }
    }

    suspend fun translateChapter(manga: Manga, chapters: Chapter) {
        translator.queueChapter(manga, chapters)
        startTranslation()
        // Kept alive in the background until the queue is done, even with the app closed
        if (hasWork()) {
            TranslationJob.start(context)
            askToKeepRunningOnce()
        }
    }

    /**
     * MANHWA: the first time a chapter is queued, Android is asked once to let the app run in the background
     * without its battery limits (several phone makers stop a translation otherwise when the app is left).
     * Never asked again, whatever the answer.
     */
    private fun askToKeepRunningOnce() {
        runCatching {
            val prefs = context.getSharedPreferences("manhwa_background", Context.MODE_PRIVATE)
            if (prefs.getBoolean("battery_asked", false)) return
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (power.isIgnoringBatteryOptimizations(context.packageName)) return
            // Only with the app on screen (Android blocks it from the background, and then it would never be asked)
            val onScreen = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (!onScreen) return
            @android.annotation.SuppressLint("BatteryLife")
            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            prefs.edit().putBoolean("battery_asked", true).apply()
        }.onFailure { logcat(LogPriority.WARN, it) { "Battery limits question not shown" } }
    }

    /**
     * MANHWA: translates [chapter], fetching it from its source first when it is not downloaded (it is then
     * translated as soon as it arrives). Returns true when it is fetched first.
     */
    suspend fun translateOrFetch(manga: Manga, chapter: Chapter): Boolean {
        val downloads = downloadManager.value
        if (downloads.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source)) {
            translateChapter(manga, chapter)
            return false
        }
        translateAfterDownload += chapter.id
        downloads.downloadChapters(manga, listOf(chapter))
        // Started even when other chapters wait in the queue with the downloader stopped
        downloads.startDownloads()
        return true
    }

    suspend fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        val translation = getQueuedTranslationOrNull(chapterId)
        if (translation != null) return translation.status
        if (isChapterTranslated(chapterName, scanlator, title, sourceId)) return Translation.State.TRANSLATED
        return Translation.State.NOT_TRANSLATED
    }

    suspend fun isChapterTranslated(
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        val source = sourceManager.get(sourceId)
        if (source == null) return false
        val file = provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)
        return file?.exists() == true
    }
    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        try {
            val file = provider.findTranslationFile(
                chapterName,
                scanlator,
                title,
                source,
            ) ?: return emptyMap()
            return getChapterTranslation(file)
        } catch (_: Exception) {
        }
        return emptyMap()
    }

    fun getChapterTranslation(
        file: UniFile,
    ): Map<String, PageTranslation> {
        try {
            return file.openInputStream().use { Json.decodeFromStream<Map<String, PageTranslation>>(it) }
        } catch (e: kotlinx.serialization.SerializationException) {
            // Only a file that is really broken is removed (a passing read error keeps a good translation)
            file.delete()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Translation file could not be read now" }
        }
        return emptyMap()
    }

    /**
     * MANHWA "save the translation when the chapter is read": every line of the chapter's translation that the
     * translation library does not have yet is saved there, then what is only needed to show this one chapter
     * goes (its translation file, and its downloaded pages when the reader chose so), to free space. The
     * library, the glossary, the series dictionary, lessons, summaries, names and templates are never touched,
     * so opening the chapter again takes its lines from the library and only what is missing goes to the AI.
     * Returns the bytes freed.
     */
    suspend fun archiveOnRead(manga: Manga, chapters: List<Chapter>): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!translationPreferences.translationSaveOnRead().get() && !translationPreferences.translationFreeSpaceOnRead().get()) return@withContext 0L
        val source = sourceManager.get(manga.source) ?: return@withContext 0L
        val src = eu.kanade.translation.recognizer.TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage()).name
        val tgt = eu.kanade.translation.style.StyleProfile.libraryTarget(translationPreferences, eu.kanade.translation.translator.TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage()).name)
        var freed = 0L
        for (chapter in chapters) {
            // Never while it is still being translated
            if (translator.queueState.value.any { it.chapter.id == chapter.id && TranslationJob.active(it.status) }) continue
            val file = runCatching { provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) }.getOrNull() ?: continue
            val pages = runCatching { Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream()) }.getOrNull() ?: continue
            // Its lines stay searchable after the file goes
            runCatching { context.appGraph.translationSearchIndex.index(manga.id, manga.title, chapter.id, chapter.name, pages) }
            var saved = true
            if (translationPreferences.translationSaveOnRead().get() && translationPreferences.translationCollection().get()) {
                val lines = pages.values.flatMap { it.blocks }
                    .filter { it.text.isNotBlank() && it.translation.isNotBlank() }
                    .map { eu.kanade.translation.collection.TranslationCollection.Line(it.text, it.translation, it.kind) }
                saved = runCatching {
                    // Only what is missing: lines the library already gives back are not saved again
                    val glossary = glossaryStore.asMap(manga.id)
                    val missing = lines.filter { collection.lookup(src, tgt, it.text, glossary)?.sure != true }
                    collection.learn(src, tgt, manga.id, glossary, missing, eu.kanade.translation.collection.TranslationCollection.Source.TEAM)
                }.isSuccess
            }
            // The file goes only once its lines are safe in the library
            if (saved && translationPreferences.translationFreeSpaceOnRead().get()) {
                val size = runCatching { file.length() }.getOrDefault(0L)
                if (file.delete()) freed += size
            }
        }
        if (translationPreferences.translationDeleteDownloadOnRead().get()) {
            runCatching {
                val dm = downloadManager.value
                val downloaded = chapters.filter { dm.isChapterDownloaded(it.name, it.scanlator, it.url, manga.title, manga.source) }
                if (downloaded.isNotEmpty()) dm.enqueueChaptersToDelete(downloaded, manga)
            }
        }
        if (freed > 0) translationPreferences.translationFreedBytes().set(translationPreferences.translationFreedBytes().get() + freed)
        freed
    }

    /** [archiveOnRead] in the background, for callers that must not wait (marking chapters read from a list). */
    fun archiveOnReadLater(manga: Manga, chapters: List<Chapter>) {
        scope.launch { runCatching { archiveOnRead(manga, chapters) } }
    }

    fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        launchIO {
            removeFromTranslationQueue(chapter)
            val file = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            file?.delete()
        }
    }

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                translator.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        // Only that chapter's job stops (the queue's flow drops it); the others keep their progress
        translator.removeFromQueue(chapter)
        if (translator.isRunning && queueState.value.isEmpty()) translator.stop()
    }

    /**
     * MANHWA: saves a chapter the reader translated live, as a chapter translated from the manga screen is saved,
     * so it shows as translated and is never translated again.
     */
    fun saveLiveChapter(manga: Manga, chapter: Chapter, source: Source, pages: Map<String, PageTranslation>) {
        if (pages.isEmpty()) return
        val dir = provider.getMangaDir(manga.title, source)
        translator.saveAtomically(dir, provider.getTranslationFileName(chapter.name, chapter.scanlator), pages)
    }

    /** Translation file names that exist for [manga], so many chapters can be checked with one directory listing. */
    fun getTranslatedFileNames(manga: Manga, source: Source): Set<String> {
        return provider.findMangaDir(manga.title, source)?.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name }
    }

    fun getTranslationFileName(chapter: Chapter): String {
        return provider.getTranslationFileName(chapter.name, chapter.scanlator)
    }

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation ->
                    translation.statusFlow.drop(1).map { translation }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { translation -> translation.status == Translation.State.TRANSLATING }.asFlow(),
            )
        }

    private companion object {
        const val MAX_RESTORES = 3
    }
}

/** MANHWA: the pure rule of putting the saved queue back (tested without a phone). */
object RestoreRules {
    /**
     * The last run ended in a crash or a freeze (ApplicationExitInfo REASON_CRASH 4, REASON_CRASH_NATIVE 5,
     * REASON_ANR 6); unknown counts as a crash (older Android), so a queue that keeps bringing the app down is let go.
     * Closed for memory, by the phone maker, by the reader or for an update: not a crash.
     */
    fun crashed(reason: Int?): Boolean = reason == null || reason == 4 || reason == 5 || reason == 6
}

/** MANHWA: which failed chapters are tried again (pure, tested without a phone). */
object RetryRules {
    /** Reasons that pass by themselves: no internet for a moment, a stalled page, a key out of quota for now. */
    private val passing = setOf(
        Translation.Note.NO_INTERNET,
        Translation.Note.RETRY,
        Translation.Note.STALLED,
        Translation.Note.KEY_LIMIT,
        Translation.Note.DOWNLOAD_FAILED,
        Translation.Note.OTHER,
    )

    const val MAX_RETRIES = 3

    fun retry(note: Translation.Note?, tries: Int): Boolean = tries < MAX_RETRIES && (note == null || note in passing)
}
