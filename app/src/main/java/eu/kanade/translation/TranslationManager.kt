package eu.kanade.translation

import java.util.concurrent.ConcurrentHashMap
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
) {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Put back once per app start, before the queue is saved again (an empty queue would erase it). */
    private val restored = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val restoring = java.util.concurrent.atomic.AtomicBoolean(false)

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
                }
                .launchIn(this)
        }
    }

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
            translationPreferences.translationPendingQueue().set("${restores + 1}|$saved")
            saved.split(',').mapNotNull { entry ->
                val (mangaId, chapterId) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                (mangaId.toLongOrNull() ?: return@mapNotNull null) to (chapterId.toLongOrNull() ?: return@mapNotNull null)
            }.forEach { (mangaId, chapterId) ->
                if (queueState.value.any { it.chapter.id == chapterId }) return@forEach
                val manga = getManga.await(mangaId) ?: return@forEach
                val chapter = getChapter.await(chapterId) ?: return@forEach
                runCatching { translator.queueChapter(manga, chapter) }
            }
            if (hasWork()) TranslationJob.start(context)
        } finally {
            restored.complete(Unit)
        }
    }

    /** Chapters still waiting or being translated. */
    fun hasWork(): Boolean = translator.queueState.value.any { TranslationJob.active(it.status) }

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
        if (translator.isRunning) return
        translator.start()
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
        if (hasWork()) TranslationJob.start(context)
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
            return Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            file.delete()
        }
        return emptyMap()
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
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
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
