package eu.kanade.translation.covers

import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.AdultFilter
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.translation.unified.UnifiedRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import java.io.File

/** A cover another source has for a series. */
data class CoverOption(val sourceId: Long, val sourceName: String, val title: String, val url: String, val match: Boolean)

/**
 * MANHWA covers: finds the covers the reader's own sources have for a series (the home and pinned sources and the
 * ones the library reads from, never a random one), measures them without downloading them whole, and puts one
 * as the series' cover. It also changes all the covers of one source at once ([startBulk]): each series gets the
 * sharpest cover of the same work found on the other sources, after a look or straight away.
 */
@Inject
@SingleIn(AppScope::class)
class CoverFinder(
    private val sourceManager: tachiyomi.domain.source.service.SourceManager,
    private val sourceHealth: SourceHealthManager,
    private val adultFilter: AdultFilter,
    private val sourcePreferences: SourcePreferences,
    private val getFavorites: GetFavorites,
    private val coverCache: CoverCache,
    private val coverManager: LocalCoverManager,
    private val updateManga: UpdateManga,
    private val network: NetworkHelper,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The sources to look in for [manga]: the reader's own ones, or every installed one with [all]. */
    suspend fun sourcesFor(manga: Manga, all: Boolean): List<CatalogueSource> = sourcesBesides(manga.source, all)

    private suspend fun sourcesBesides(sourceId: Long, all: Boolean): List<CatalogueSource> {
        val installed = sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id != LocalSource.ID && it.id != sourceId && !sourceHealth.isHidden(it.id) && !adultFilter.hides(it.id, it.name) }
        if (all) return installed
        val mine = mySourceIds()
        return installed.filter { it.id in mine }.ifEmpty { installed }
    }

    private suspend fun mySourceIds(): Set<Long> {
        val chosen = (sourcePreferences.homeSources.get() + sourcePreferences.pinnedSources.get()).mapNotNull { it.toLongOrNull() }
        val library = getFavorites.await().map { it.source }
        return (chosen + library).toSet()
    }

    /**
     * The covers [sources] give for [query]: the same work as [manga] first. With [onlySame] only the same work
     * (for changing covers without a look), else each source's first [PER_SOURCE] results.
     */
    suspend fun search(
        manga: Manga,
        query: String,
        sources: List<CatalogueSource>,
        onlySame: Boolean,
        onFound: (List<CoverOption>) -> Unit = {},
    ): List<CoverOption> = coroutineScope {
        val gate = Semaphore(SEARCH_PARALLEL)
        sources.map { source ->
            async {
                gate.withPermit {
                    val found = try {
                        withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.getSearchManga(1, query, FilterList()) }
                            ?.mangas.orEmpty()
                            .filter { !it.thumbnail_url.isNullOrBlank() }
                            .map {
                                val match = UnifiedRules.sameWork(it.title, manga.title) || UnifiedRules.sameWork(it.title, query)
                                CoverOption(source.id, source.name, it.title, it.thumbnail_url!!, match)
                            }
                            .filter { !onlySame || it.match }
                            .take(PER_SOURCE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (found.isNotEmpty()) onFound(found)
                    found
                }
            }
        }.awaitAll().flatten().distinctBy { it.sourceId to it.url }.sortedByDescending { it.match }
    }

    /** Width and height of the picture at [url], read from its first bytes (the rest is not downloaded). */
    suspend fun measure(sourceId: Long, url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(MEASURE_TIMEOUT_MS) {
            try {
                val source = sourceManager.get(sourceId) as? HttpSource
                val client = source?.client ?: network.client
                val request = if (source != null) GET(url, source.headers) else GET(url)
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(response.body.byteStream(), null, bounds)
                    (bounds.outWidth to bounds.outHeight).takeIf { it.first > 0 && it.second > 0 }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The size of [manga]'s cover now: from the file the app keeps, else from its source. */
    suspend fun currentSize(manga: Manga): Pair<Int, Int>? {
        val file = coverCache.getCustomCoverFile(manga.id).takeIf { it.exists() }
            ?: coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
        if (file != null) return fileSize(file)
        val url = manga.thumbnailUrl ?: return null
        return measure(manga.source, url)
    }

    private fun fileSize(file: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return (bounds.outWidth to bounds.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }

    /** Puts [option]'s picture as [manga]'s cover, fetched with its source's headers. */
    suspend fun apply(manga: Manga, option: CoverOption) = withContext(Dispatchers.IO) {
        val source = sourceManager.get(option.sourceId) as? HttpSource ?: error("Source ${option.sourceId} not installed")
        source.client.newCall(GET(option.url, source.headers)).await().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.byteStream().use { manga.editCover(coverManager, it, updateManga, coverCache) }
        }
    }

    // ---- All the covers of one source at once ----

    enum class Status { WAITING, LOOKING, FOUND, NONE, SAME, APPLIED, FAILED }

    data class Item(
        val manga: Manga,
        val status: Status = Status.WAITING,
        val option: CoverOption? = null,
        val size: Pair<Int, Int>? = null,
        val current: Pair<Int, Int>? = null,
        val selected: Boolean = false,
    )

    data class Bulk(
        val sourceId: Long,
        val sourceName: String,
        val items: List<Item>,
        val running: Boolean,
        val auto: Boolean,
    ) {
        val done: Int get() = items.count { it.status != Status.WAITING && it.status != Status.LOOKING }
        val selectedCount: Int get() = items.count { it.selected && it.option != null && it.status == Status.FOUND }
    }

    private val _bulk = MutableStateFlow<Bulk?>(null)
    val bulk: StateFlow<Bulk?> = _bulk.asStateFlow()
    private var bulkJob: Job? = null

    /**
     * Looks for a sharper cover for every series of [sourceId] in the library, on the reader's own sources, the same
     * work only. With [auto] each one found is put at once; else they wait to be looked at ([applySelected]).
     * A cover is offered only when it is bigger than the one there now by [SHARPER] (or there is none to compare).
     */
    fun startBulk(sourceId: Long, auto: Boolean) {
        // The previous run (a look or the covers being put) ends before this one starts
        val old = bulkJob
        bulkJob = scope.launch {
            old?.cancelAndJoin()
            val source = sourceManager.get(sourceId)
            val mangas = getFavorites.subscribe(sourceId).first()
            _bulk.value = Bulk(sourceId, source?.name ?: sourceId.toString(), mangas.map { Item(it) }, running = true, auto = auto)
            // The same sources for every series of this one source
            val sources = sourcesBesides(sourceId, all = false)
            val gate = Semaphore(BULK_PARALLEL)
            try {
                coroutineScope {
                    mangas.forEach { manga ->
                        launch { gate.withPermit { bulkOne(manga, sources, auto) } }
                    }
                }
            } finally {
                _bulk.update { it?.copy(running = false) }
            }
        }
    }

    private suspend fun bulkOne(manga: Manga, sources: List<CatalogueSource>, auto: Boolean) {
        set(manga.id) { it.copy(status = Status.LOOKING) }
        try {
            val options = search(manga, manga.title, sources, onlySame = true)
            val measured = options.mapNotNull { option -> measure(option.sourceId, option.url)?.let { option to it } }
            val best = measured.maxByOrNull { (_, size) -> size.first.toLong() * size.second }
            if (best == null) {
                set(manga.id) { it.copy(status = Status.NONE) }
                return
            }
            val current = currentSize(manga)
            val sharper = current == null || best.second.first.toLong() * best.second.second > current.first.toLong() * current.second * SHARPER
            if (!sharper) {
                set(manga.id) { it.copy(status = Status.SAME, option = best.first, size = best.second, current = current) }
                return
            }
            set(manga.id) { it.copy(status = Status.FOUND, option = best.first, size = best.second, current = current, selected = true) }
            if (auto) applyOne(manga.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Covers: ${manga.title} not looked up" }
            set(manga.id) { it.copy(status = Status.FAILED) }
        }
    }

    private suspend fun applyOne(mangaId: Long) {
        val item = _bulk.value?.items?.firstOrNull { it.manga.id == mangaId } ?: return
        val option = item.option ?: return
        try {
            apply(item.manga, option)
            set(mangaId) { it.copy(status = Status.APPLIED) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Covers: ${item.manga.title} not changed" }
            set(mangaId) { it.copy(status = Status.FAILED) }
        }
    }

    /** Puts every selected cover found (after a look). */
    fun applySelected() {
        val ids = _bulk.value?.items?.filter { it.selected && it.status == Status.FOUND }?.map { it.manga.id }.orEmpty()
        if (ids.isEmpty()) return
        val old = bulkJob
        bulkJob = scope.launch {
            old?.cancelAndJoin()
            _bulk.update { it?.copy(running = true) }
            val gate = Semaphore(BULK_PARALLEL)
            try {
                coroutineScope { ids.forEach { id -> launch { gate.withPermit { applyOne(id) } } } }
            } finally {
                _bulk.update { it?.copy(running = false) }
            }
        }
    }

    fun toggle(mangaId: Long) = set(mangaId) { if (it.status == Status.FOUND || it.status == Status.SAME) it.copy(selected = !it.selected, status = Status.FOUND) else it }

    fun stopBulk() {
        bulkJob?.cancel()
        _bulk.update { it?.copy(running = false) }
    }

    fun clearBulk() {
        bulkJob?.cancel()
        _bulk.value = null
    }

    private fun set(mangaId: Long, change: (Item) -> Item) {
        _bulk.update { bulk -> bulk?.copy(items = bulk.items.map { if (it.manga.id == mangaId) change(it) else it }) }
    }

    companion object {
        private const val SEARCH_PARALLEL = 4
        private const val SEARCH_TIMEOUT_MS = 15_000L
        private const val MEASURE_TIMEOUT_MS = 10_000L
        private const val PER_SOURCE = 3
        private const val BULK_PARALLEL = 2

        /** How much bigger (in pixels) a cover must be to count as sharper than the one there now. */
        const val SHARPER = 1.2
    }
}
