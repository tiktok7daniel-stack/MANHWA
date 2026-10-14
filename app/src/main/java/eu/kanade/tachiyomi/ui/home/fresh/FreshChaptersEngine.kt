package eu.kanade.tachiyomi.ui.home.fresh

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MANHWA global chapter sync: the newest chapters of every series on the reader's home sources, whether
 * the series is in the library or not. Each source's "latest" list is read, the chapter list of every
 * series in it is checked, and the newest chapter is indexed with the time it was first seen. Runs in the
 * background every hour and when the home screen opens with old data.
 */
@Inject
@SingleIn(AppScope::class)
class FreshChaptersEngine(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId,
    private val getManga: GetManga,
) {

    @Serializable
    data class Item(
        val mangaId: Long,
        val sourceId: Long,
        val title: String,
        val thumbnail: String? = null,
        val chapterName: String,
        val chapterNumber: Double = -1.0,
        val chapterUrl: String,
        val uploadedAt: Long = 0L,
        val foundAt: Long = 0L,
    ) {
        /** When it came out: the source's date, or when MANHWA first saw it. */
        val sortTime: Long get() = if (uploadedAt > 0L) uploadedAt else foundAt
    }

    data class Snapshot(val items: List<Item> = emptyList(), val updatedAt: Long = 0L, val running: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "fresh_chapters.json")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @Serializable
    private data class Stored(val items: List<Item>, val updatedAt: Long)

    init {
        runCatching {
            file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Stored>(it) }
        }.getOrNull()?.let { _state.value = Snapshot(it.items, it.updatedAt) }
    }

    fun isStale(): Boolean = System.currentTimeMillis() - _state.value.updatedAt > STALE_MS

    /** Sources the feed follows: the reader's home sources, in their order. */
    suspend fun sources(): List<Source> {
        val ids = sourcePreferences.homeSources.get()
        val order = sourcePreferences.homeSourcesOrder.get().split(',').withIndex().associate { (i, v) -> v to i }
        return ids.mapNotNull { id -> id.toLongOrNull()?.let { sourceManager.get(it) } }
            .sortedBy { order["${it.id}"] ?: Int.MAX_VALUE }
            .take(MAX_SOURCES)
    }

    /** Checks every home source for new chapters. Returns how many new chapters were found. */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        if (mutex.isLocked) return@withContext 0
        mutex.withLock {
            _state.update { it.copy(running = true) }
            try {
                val previous = _state.value.items.associateBy { it.key() }
                val now = System.currentTimeMillis()
                val gate = Semaphore(PARALLEL)
                val found = coroutineScope {
                    sources().map { source -> async { gate.withPermit { scan(source) } } }.awaitAll()
                }.flatten()
                var fresh = 0
                val merged = found.map { item ->
                    val old = previous[item.key()]
                    if (old == null) fresh++
                    item.copy(foundAt = old?.foundAt ?: now)
                }
                // Keep what other sources found before, so one failing source does not empty the feed
                val scanned = found.map { it.sourceId }.toSet()
                val kept = previous.values.filter { it.sourceId !in scanned && now - it.foundAt < KEEP_MS }
                val items = (merged + kept).distinctBy { it.key() }.sortedByDescending { it.sortTime }.take(MAX_ITEMS)
                _state.value = Snapshot(items, now)
                runCatching { file.writeText(json.encodeToString(Stored(items, now))) }
                fresh
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e)
                0
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun scan(source: Source): List<Item> {
        val page = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { source.getLatestUpdates(1) }.getOrNull()
        } ?: return emptyList()
        val gate = Semaphore(PARALLEL)
        return coroutineScope {
            page.mangas.take(PER_SOURCE).map { sManga ->
                async {
                    gate.withPermit {
                        runCatching {
                            val chapters = withTimeoutOrNull(TIMEOUT_MS) {
                                source.getMangaUpdate(sManga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
                            } ?: return@runCatching null
                            val newest = chapters.maxWithOrNull(
                                compareBy<SChapter>({ it.date_upload }, { number(sManga.title, it) }),
                            ) ?: return@runCatching null
                            val local = networkToLocalManga(listOf(sManga.toDomainManga(source.id))).firstOrNull()
                                ?: return@runCatching null
                            Item(
                                mangaId = local.id,
                                sourceId = source.id,
                                title = local.title,
                                thumbnail = local.thumbnailUrl ?: sManga.thumbnail_url,
                                chapterName = newest.name,
                                chapterNumber = number(sManga.title, newest),
                                chapterUrl = newest.url,
                                uploadedAt = newest.date_upload,
                            )
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Saves the series' chapters and returns the ids to open this chapter in the reader. */
    suspend fun open(item: Item): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            getChapterByUrlAndMangaId.await(item.chapterUrl, item.mangaId)?.let { return@runCatching item.mangaId to it.id }
            val source = sourceManager.get(item.sourceId) ?: return@runCatching null
            val manga = getManga.await(item.mangaId) ?: return@runCatching null
            val chapters = source.getMangaUpdate(manga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
            syncChaptersWithSource.await(chapters, manga, source)
            getChapterByUrlAndMangaId.await(item.chapterUrl, item.mangaId)?.let { item.mangaId to it.id }
        }.getOrNull()
    }

    private fun Item.key() = "$sourceId|$chapterUrl"

    private fun number(title: String, chapter: SChapter): Double =
        ChapterRecognition.parseChapterNumber(title, chapter.name, chapter.chapter_number.toDouble())

    companion object {
        private const val MAX_SOURCES = 12
        private const val PER_SOURCE = 10
        private const val PARALLEL = 4
        private const val TIMEOUT_MS = 20_000L
        private const val MAX_ITEMS = 300
        private const val STALE_MS = 30 * 60 * 1000L
        private const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        private const val WORK_NAME = "manhwa_fresh_chapters"

        /** Hourly background check while the phone is online. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FreshChaptersWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class FreshChaptersWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.appGraph.freshChaptersEngine.refresh()
        return Result.success()
    }
}
