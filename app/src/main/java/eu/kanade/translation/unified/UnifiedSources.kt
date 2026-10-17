package eu.kanade.translation.unified

import eu.kanade.translation.writeTextAtomic
import eu.kanade.translation.readTextAtomic
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager
import eu.kanade.translation.glossary.WorkIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.File

/**
 * MANHWA one work, all sources: a series of the library is no longer tied to the one site it was added from.
 * The same work is found on the reader's other installed sources (strict title match, [WorkIdentity]) and kept
 * linked; its chapters there fill the chapters missing here, a dead source is read from a working one, and a
 * chapter read on any of them counts as read on all of them.
 *
 * Nothing is moved or deleted: the series keeps its own source, categories and history; the other sources'
 * copies are ordinary entries the reader can open, not added to the library.
 */
@Inject
@SingleIn(AppScope::class)
class UnifiedSources(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val sourceHealth: SourceHealthManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
    private val updateChapter: UpdateChapter,
) {

    @Serializable
    data class Link(val sourceId: Long, val sourceName: String, val mangaId: Long, val title: String)

    @Serializable
    data class Links(val links: List<Link> = emptyList(), val checkedAt: Long = 0)

    /** A chapter of the work found on another source. */
    data class AltChapter(val number: Double, val name: String, val sourceName: String, val mangaId: Long, val chapterId: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "unified_sources.json")
    private val lock = Any()

    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<Long, Links>> = _all.asStateFlow()

    private fun load(): Map<Long, Links> = runCatching {
        json.decodeFromString<Map<String, Links>>(file.readTextAtomic()).mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(map: Map<Long, Links>) {
        _all.value = map
        runCatching { file.writeTextAtomic(json.encodeToString(map.mapKeys { it.key.toString() })) }
    }

    fun linksOf(mangaId: Long): List<Link> = _all.value[mangaId]?.links.orEmpty()

    /** The series this entry is another source's copy of, when it is one. */
    fun mainOf(mangaId: Long): Long? = _all.value.entries.firstOrNull { (_, v) -> v.links.any { it.mangaId == mangaId } }?.key

    /**
     * Finds the work on the reader's other sources (home and pinned ones first, then the same language), at most
     * [MAX_SOURCES], and saves the links. Checked again after [RECHECK_MS] or with [force].
     */
    // One search per work at a time: a second caller (the fusion banner) waits for it and takes its links
    private val discovering = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()

    suspend fun discover(manga: Manga, force: Boolean = false): List<Link> = withContext(Dispatchers.IO) {
        discovering.getOrPut(manga.id) { kotlinx.coroutines.sync.Mutex() }.withLock { discoverNow(manga, force) }
    }

    private suspend fun discoverNow(manga: Manga, force: Boolean): List<Link> {
        val old = _all.value[manga.id]
        if (!force && old != null && System.currentTimeMillis() - old.checkedAt < RECHECK_MS) return old.links
        val ours = sourceManager.get(manga.source) as? CatalogueSource
        val preferred = (sourcePreferences.homeSources.get() + sourcePreferences.pinnedSources.get()).mapNotNull { it.toLongOrNull() }.toSet()
        val candidates = sourceManager.getAll().filterIsInstance<CatalogueSource>()
            // Not the local folder, not this series' own source, not a source hidden for not working
            .filter { it.id != LOCAL_SOURCE_ID && it.id != manga.source && !sourceHealth.isHidden(it.id) }
            .sortedWith(compareBy({ it.id !in preferred }, { ours != null && it.lang != ours.lang }))
            .take(MAX_SOURCES)
        val gate = Semaphore(PARALLEL)
        val found = coroutineScope {
            candidates.map { source ->
                async {
                    gate.withPermit {
                        runCatching {
                            val page = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.getSearchManga(1, manga.title, FilterList()) } ?: return@runCatching null
                            val match = page.mangas.firstOrNull { UnifiedRules.sameWork(it.title, manga.title) } ?: return@runCatching null
                            val local = networkToLocalManga(listOf(match.toDomainManga(source.id))).first()
                            Link(source.id, source.name, local.id, local.title)
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        synchronized(lock) { save(_all.value + (manga.id to Links(found, System.currentTimeMillis()))) }
        return found
    }

    /** The chapters of every linked copy, refreshed from their sources. */
    suspend fun alternatives(mainId: Long): List<AltChapter> = withContext(Dispatchers.IO) {
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            linksOf(mainId).map { link ->
                async {
                    gate.withPermit {
                        runCatching {
                            val source = sourceManager.get(link.sourceId) ?: return@runCatching emptyList()
                            val copy = getManga.await(link.mangaId) ?: return@runCatching emptyList()
                            val fresh = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                source.getMangaUpdate(copy.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
                            }
                            if (!fresh.isNullOrEmpty()) runCatching { syncChaptersWithSource.await(fresh, copy, source) }
                            getChaptersByMangaId.await(copy.id).map { AltChapter(it.chapterNumber, it.name, link.sourceName, copy.id, it.id) }
                        }.onFailure { logcat(LogPriority.WARN, it) { "Linked source ${link.sourceName} not read" } }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** Chapters other sources have that this series does not (missing ones and newer ones), one per number. */
    suspend fun missingHere(mainId: Long): List<AltChapter> {
        val ours = getChaptersByMangaId.await(mainId).map { it.chapterNumber }
        return UnifiedRules.missing(ours, alternatives(mainId))
    }

    /** A chapter read on one source counts as read on the others too. */
    suspend fun syncRead(mangaId: Long, number: Double) {
        if (number < 0 || !UnifiedRules.countable(number)) return
        val group = buildSet {
            val main = mainOf(mangaId) ?: mangaId
            add(main)
            linksOf(main).forEach { add(it.mangaId) }
        } - mangaId
        if (group.isEmpty()) return
        group.forEach { id ->
            val updates = runCatching { getChaptersByMangaId.await(id) }.getOrDefault(emptyList())
                .filter { !it.read && it.chapterNumber == number }
                .map { ChapterUpdate(it.id) { read = true } }
            if (updates.isNotEmpty()) runCatching { updateChapter.awaitAll(updates) }
        }
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val MAX_SOURCES = 6
        const val PARALLEL = 3
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val RECHECK_MS = 3 * 24 * 60 * 60 * 1000L
    }
}

/** The pure rules of one work across sources (tested without a phone). */
object UnifiedRules {

    /** Two titles are one work only when they are the same after cleaning. */
    fun sameWork(a: String, b: String): Boolean = WorkIdentity.same(a, b)

    /** A chapter number that means a real chapter (not "0" extras without a number). */
    fun countable(number: Double): Boolean = number >= 0

    /**
     * Chapters the other sources have whose number this series lacks: gaps and newer chapters, the first
     * source per number, in reading order.
     */
    fun missing(ours: List<Double>, theirs: List<UnifiedSources.AltChapter>): List<UnifiedSources.AltChapter> {
        val have = ours.filter { it >= 0 }.toSet()
        return theirs.filter { it.number >= 0 && it.number !in have }
            .groupBy { it.number }
            .map { (_, list) -> list.first() }
            .sortedBy { it.number }
    }
}
