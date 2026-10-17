package eu.kanade.translation.unified

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager
import eu.kanade.translation.glossary.WorkIdentity
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.writeTextAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MANHWA smart chapter fusion: a work whose translation stopped on its source (an Arabic site stuck at chapter
 * 25) is joined with the same work on a source that kept going (an English one at chapter 100). Its chapters
 * stay first (they are ready to read), and the other source's chapters past them follow as one list; when this
 * source catches up, its own chapter takes that number's place by itself.
 *
 * Nothing is moved or deleted: the work keeps its source, library entry, categories and history; the other
 * source's copy is an ordinary entry only used for the chapters past this one's.
 */
@Inject
@SingleIn(AppScope::class)
class ChapterFusion(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val sourceHealth: SourceHealthManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getManga: GetManga,
    private val unifiedSources: UnifiedSources,
) {

    /** A work ([baseMangaId], read first) joined with its continuation on another source. */
    @Serializable
    data class Fusion(
        val baseMangaId: Long,
        val baseSourceName: String,
        val otherMangaId: Long,
        val otherSourceId: Long,
        val otherSourceName: String,
        val otherLang: String = "",
        val createdAt: Long = 0,
        // Where the continuation's entry is on its source, to make it again if it is removed (clearing the database)
        val otherUrl: String = "",
        val otherTitle: String = "",
        // MANHWA: chapters of earlier continuations that were translated there; they keep their place when the
        // continuation moves to another source, which only gives the chapters they do not have
        val kept: List<Kept> = emptyList(),
    ) {
        /** Every entry that gives this work chapters past its own: the continuation and the kept ones. */
        fun continuationIds(): List<Long> = listOf(otherMangaId) + kept.map { it.mangaId }

        /** The name of the source [mangaId]'s chapters come from in this fusion. */
        fun sourceNameOf(mangaId: Long): String = when (mangaId) {
            baseMangaId -> baseSourceName
            otherMangaId -> otherSourceName
            else -> kept.firstOrNull { it.mangaId == mangaId }?.sourceName ?: otherSourceName
        }
    }

    /** An earlier continuation's translated chapters, kept when the continuation moved to another source. */
    @Serializable
    data class Kept(
        val mangaId: Long,
        val sourceId: Long,
        val sourceName: String,
        val lang: String = "",
        val chapters: List<KeptChapter> = emptyList(),
    )

    @Serializable
    data class KeptChapter(val number: Double, val chapterId: Long)

    @Serializable
    private data class Saved(val fusions: List<Fusion> = emptyList(), val dismissed: Set<Long> = emptySet())

    /** A continuation found for a work, offered to the reader. */
    data class Offer(
        val baseMangaId: Long,
        val baseSourceName: String,
        val oursMax: Double,
        val other: Manga,
        val otherSourceId: Long,
        val otherSourceName: String,
        val otherLang: String,
        val theirsMax: Double,
        // The title matches this work's exactly (else a close result the reader decides on)
        val exact: Boolean = true,
    )

    /** A chapter of the continuation, shown past this work's own chapters, with the source it comes from. */
    data class Continuation(val number: Double, val chapter: Chapter, val sourceName: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "chapter_fusions.json")
    private val lock = Any()

    private val saved = MutableStateFlow(load())
    private val _fusions = MutableStateFlow(saved.value.fusions.associateBy { it.baseMangaId })
    val fusions: StateFlow<Map<Long, Fusion>> = _fusions.asStateFlow()

    private val offers = ConcurrentHashMap<Long, Pair<Long, List<Offer>>>()

    /** An offer kept on the disk, so the banner shows at once the next time the work is opened. */
    @Serializable
    private data class SavedOffer(
        val otherMangaId: Long,
        val otherSourceId: Long,
        val otherSourceName: String,
        val otherLang: String,
        val theirsMax: Double,
        val exact: Boolean,
    )

    @Serializable
    private data class SavedOffers(val at: Long = 0, val offers: List<SavedOffer> = emptyList())

    private val offersFile get() = File(context.filesDir, "fusion_offers.json")
    private val offersLock = Any()

    private fun loadSavedOffers(): Map<String, SavedOffers> = runCatching {
        json.decodeFromString<Map<String, SavedOffers>>(offersFile.readTextAtomic())
    }.getOrDefault(emptyMap())

    private fun keepOffers(baseMangaId: Long, list: List<Offer>) = synchronized(offersLock) {
        runCatching {
            val all = loadSavedOffers().toMutableMap()
            all[baseMangaId.toString()] = SavedOffers(
                System.currentTimeMillis(),
                list.map { SavedOffer(it.other.id, it.otherSourceId, it.otherSourceName, it.otherLang, it.theirsMax, it.exact) },
            )
            // Only the works opened most recently
            val trimmed = all.entries.sortedByDescending { it.value.at }.take(MAX_SAVED_OFFERS).associate { it.key to it.value }
            offersFile.writeTextAtomic(json.encodeToString(trimmed))
        }
        Unit
    }

    /** What was found the last time (on the disk), shown at once while the sources are asked again. */
    suspend fun savedOffers(manga: Manga): List<Offer> = withContext(Dispatchers.IO) {
        val kept = synchronized(offersLock) { loadSavedOffers()[manga.id.toString()] } ?: return@withContext emptyList()
        val oursMax = getChaptersByMangaId.await(manga.id).map { it.chapterNumber }.filter { FusionRules.countable(it) }.maxOrNull() ?: -1.0
        val baseName = sourceManager.get(manga.source)?.name.orEmpty()
        kept.offers.mapNotNull { o ->
            val other = getManga.await(o.otherMangaId) ?: return@mapNotNull null
            Offer(manga.id, baseName, oursMax, other, o.otherSourceId, o.otherSourceName, o.otherLang, o.theirsMax, o.exact)
        }.filter { FusionRules.worthOffering(oursMax, it.theirsMax) }
    }

    private fun load(): Saved = runCatching { json.decodeFromString<Saved>(file.readTextAtomic()) }.getOrDefault(Saved())

    private fun save(next: Saved) {
        saved.value = next
        _fusions.value = next.fusions.associateBy { it.baseMangaId }
        runCatching { file.writeTextAtomic(json.encodeToString(next)) }
            .onFailure { logcat(LogPriority.WARN, it) { "Fusions not saved" } }
    }

    fun fusionOf(baseMangaId: Long): Fusion? = _fusions.value[baseMangaId]

    /** The work [fusion] continues. */
    suspend fun baseManga(fusion: Fusion): Manga? = withContext(Dispatchers.IO) { getManga.await(fusion.baseMangaId) }

    /** The fusion this entry gives chapters to (as its continuation, or kept from an earlier one), when it does. */
    fun continuationOf(otherMangaId: Long): Fusion? =
        _fusions.value.values.firstOrNull { it.otherMangaId == otherMangaId } ?: _fusions.value.values.firstOrNull { f -> f.kept.any { it.mangaId == otherMangaId } }

    fun isDismissed(baseMangaId: Long): Boolean = baseMangaId in saved.value.dismissed

    fun dismiss(baseMangaId: Long) = synchronized(lock) {
        save(saved.value.copy(dismissed = saved.value.dismissed + baseMangaId))
    }

    fun fuse(offer: Offer, kept: List<Kept> = emptyList()) = synchronized(lock) {
        val fusion = Fusion(
            baseMangaId = offer.baseMangaId,
            baseSourceName = offer.baseSourceName,
            otherMangaId = offer.other.id,
            otherSourceId = offer.otherSourceId,
            otherSourceName = offer.otherSourceName,
            otherLang = offer.otherLang,
            createdAt = System.currentTimeMillis(),
            otherUrl = offer.other.url,
            otherTitle = offer.other.title,
            // The new continuation's own chapters need no keeping
            kept = kept.filter { it.mangaId != offer.other.id && it.chapters.isNotEmpty() },
        )
        save(
            saved.value.copy(
                fusions = saved.value.fusions.filter { it.baseMangaId != offer.baseMangaId } + fusion,
                dismissed = saved.value.dismissed - offer.baseMangaId,
            ),
        )
    }

    fun unfuse(baseMangaId: Long) = synchronized(lock) {
        save(
            saved.value.copy(
                fusions = saved.value.fusions.filter { it.baseMangaId != baseMangaId },
                dismissed = saved.value.dismissed + baseMangaId,
            ),
        )
        offers.remove(baseMangaId)
    }

    /** Saves [fusion] in place of the work's current one. */
    private fun replace(fusion: Fusion) = synchronized(lock) {
        if (saved.value.fusions.none { it.baseMangaId == fusion.baseMangaId }) return@synchronized
        save(saved.value.copy(fusions = saved.value.fusions.map { if (it.baseMangaId == fusion.baseMangaId) fusion else it }))
    }

    /** Undoes a fusion whose continuation is gone, without turning the offer down (the banner offers it again). */
    private fun drop(baseMangaId: Long) = synchronized(lock) {
        save(saved.value.copy(fusions = saved.value.fusions.filter { it.baseMangaId != baseMangaId }))
        offers.remove(baseMangaId)
    }

    /**
     * The continuation's entry. When it was removed (clearing the database removes entries outside the library)
     * it is made again from its saved address on the source; a fusion whose entry can't be made again is undone,
     * so the work offers its continuation again instead of showing an empty one.
     */
    suspend fun otherOf(fusion: Fusion): Manga? = withContext(Dispatchers.IO) {
        val found = getManga.await(fusion.otherMangaId)
        if (found != null) {
            // Fusions made before the address was kept learn it now
            if (fusion.otherUrl.isEmpty()) replace(fusion.copy(otherUrl = found.url, otherTitle = found.title))
            return@withContext found
        }
        if (fusion.otherUrl.isEmpty()) {
            drop(fusion.baseMangaId)
            return@withContext null
        }
        val remade = runCatching {
            val entry = SManga.create().apply {
                url = fusion.otherUrl
                title = fusion.otherTitle
            }
            networkToLocalManga(listOf(entry.toDomainManga(fusion.otherSourceId))).first()
        }.onFailure { logcat(LogPriority.WARN, it) { "Continuation of ${fusion.otherSourceName} not made again" } }.getOrNull()
        if (remade == null) {
            drop(fusion.baseMangaId)
            return@withContext null
        }
        replace(fusion.copy(otherMangaId = remade.id))
        remade
    }

    /**
     * Looks (at most every few hours) on the reader's sources in other languages for the same work with more
     * chapters than [manga] has here; the one that goes furthest is offered. Null when none goes further.
     */
    suspend fun findOffer(manga: Manga, force: Boolean = false): Offer? = findOffers(manga, force).firstOrNull()

    /**
     * Every source that goes further than this one, the furthest first. The copies the app already linked to
     * this work (shown as "also on") are only asked for their chapters, every other source is searched at the
     * same time, and [onFound] hears the best so far as each source answers.
     */
    suspend fun findOffers(manga: Manga, force: Boolean = false, onFound: ((List<Offer>) -> Unit)? = null): List<Offer> = withContext(Dispatchers.IO) {
        offers[manga.id]?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < OFFER_TTL_MS) return@withContext list }
        val ours = getChaptersByMangaId.await(manga.id).map { it.chapterNumber }
        val oursMax = ours.filter { FusionRules.countable(it) }.maxOrNull() ?: -1.0
        val base = sourceManager.get(manga.source)
        val baseLang = (base as? CatalogueSource)?.lang.orEmpty()
        val baseName = base?.name.orEmpty()
        val found = java.util.Collections.synchronizedList(mutableListOf<Offer>())
        val report: (List<Offer>) -> Unit = { more ->
            val worth = more.filter { FusionRules.worthOffering(oursMax, it.theirsMax) }
            if (worth.isNotEmpty()) {
                found += worth
                onFound?.invoke(synchronized(found) { found.toList() }.distinctBy { it.other.id }.sortedByDescending { it.theirsMax })
            }
        }
        // Copies already linked to this work: no search, only their chapters
        // The same search as the "also on" card (shared, never run twice), so the copies it found are used here
        val links = runCatching { unifiedSources.discover(manga) }.getOrDefault(unifiedSources.linksOf(manga.id))
        val linked = links.mapNotNull { link ->
            val source = sourceManager.get(link.sourceId) as? CatalogueSource ?: return@mapNotNull null
            if (!FusionRules.otherLanguage(baseLang, source.lang) || sourceHealth.isHidden(source.id)) return@mapNotNull null
            source to link.mangaId
        }
        val linkedIds = linked.map { it.first.id }.toSet()
        val candidates = sourcesFor(manga, baseLang).filter { it.id !in linkedIds }
        val titles = FusionRules.titlesOf(manga.title, manga.description)
        coroutineScope {
            linked.forEach { (source, mangaId) ->
                launch {
                    runCatching {
                        val copy = getManga.await(mangaId) ?: return@runCatching
                        offerFor(source, manga, copy, oursMax, baseName, exact = true)?.let { report(listOf(it)) }
                    }.onFailure { logcat(LogPriority.WARN, it) { "Linked copy on ${source.name} not read" } }
                }
            }
            launch { searchOn(candidates, manga, titles, titles, oursMax, baseName, loose = false, onEach = report) }
        }
        val sorted = synchronized(found) { found.toList() }.distinctBy { it.other.id }.sortedByDescending { it.theirsMax }
        offers[manga.id] = System.currentTimeMillis() to sorted
        keepOffers(manga.id, sorted)
        sorted
    }

    /**
     * For the reader to choose from: on every source in another language, the closest results for this work's
     * names (or for [query], a name the reader typed), each with how far its chapters go. Exact title matches
     * first, then the rest by their last chapter.
     */
    suspend fun searchCandidates(manga: Manga, query: String? = null): List<Offer> = withContext(Dispatchers.IO) {
        val oursMax = getChaptersByMangaId.await(manga.id).map { it.chapterNumber }.filter { FusionRules.countable(it) }.maxOrNull() ?: -1.0
        val base = sourceManager.get(manga.source)
        val candidates = sourcesFor(manga, (base as? CatalogueSource)?.lang.orEmpty())
        val titles = FusionRules.titlesOf(manga.title, manga.description)
        val queries = query?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: titles
        searchOn(candidates, manga, queries, titles + queries, oursMax, base?.name.orEmpty(), loose = true)
            .distinctBy { it.other.id }
            .sortedWith(compareBy<Offer>({ !it.exact }, { -it.theirsMax }))
    }

    private suspend fun sourcesFor(manga: Manga, baseLang: String): List<CatalogueSource> {
        val preferred = (sourcePreferences.homeSources.get() + sourcePreferences.pinnedSources.get()).mapNotNull { it.toLongOrNull() }.toSet()
        return sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id != LOCAL_SOURCE_ID && it.id != manga.source && !sourceHealth.isHidden(it.id) }
            .filter { FusionRules.otherLanguage(baseLang, it.lang) }
            .sortedWith(compareBy({ it.id !in preferred }, { it.lang != "en" }))
            .take(MAX_SOURCES)
    }

    private suspend fun searchOn(
        sources: List<CatalogueSource>,
        manga: Manga,
        queries: List<String>,
        names: List<String>,
        oursMax: Double,
        baseName: String,
        loose: Boolean,
        onEach: ((List<Offer>) -> Unit)? = null,
    ): List<Offer> {
        val gate = Semaphore(PARALLEL)
        return coroutineScope {
            sources.map { source ->
                async {
                    gate.withPermit {
                        runCatching { offersOn(source, manga, queries, names, oursMax, baseName, loose) }
                            .onFailure { logcat(LogPriority.WARN, it) { "Fusion search on ${source.name} failed" } }
                            .getOrDefault(emptyList())
                            .also { if (it.isNotEmpty()) onEach?.invoke(it) }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** [copy] on [source] as an offer: its chapters fetched now, with how far they go. */
    private suspend fun offerFor(source: CatalogueSource, manga: Manga, copy: Manga, oursMax: Double, baseName: String, exact: Boolean): Offer? {
        val chapters = withTimeoutOrNull(CHAPTERS_TIMEOUT_MS) {
            source.getMangaUpdate(copy.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
        }
        if (!chapters.isNullOrEmpty()) runCatching { syncChaptersWithSource.await(chapters, copy, source) }
        val theirsMax = getChaptersByMangaId.await(copy.id).map { it.chapterNumber }.filter { FusionRules.countable(it) }.maxOrNull()
            ?: return null
        return Offer(manga.id, baseName, oursMax, copy, source.id, source.name, source.lang, theirsMax, exact)
    }

    /** The results on [source]: the exact match only, or with [loose] the first few results too. */
    private suspend fun offersOn(
        source: CatalogueSource,
        manga: Manga,
        queries: List<String>,
        names: List<String>,
        oursMax: Double,
        baseName: String,
        loose: Boolean,
    ): List<Offer> {
        for (query in queries) {
            val page = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.getSearchManga(1, query, FilterList()) } ?: continue
            val exact = page.mangas.filter { found -> names.any { FusionRules.sameName(found.title, it) } }
            val picked = if (loose) (exact + page.mangas).distinctBy { it.url }.take(LOOSE_PER_SOURCE) else exact.take(1)
            if (picked.isEmpty()) continue
            // The picked results' chapters are fetched together, not one after the other
            return coroutineScope {
                picked.map { match ->
                    async {
                        runCatching {
                            val local = networkToLocalManga(listOf(match.toDomainManga(source.id))).first()
                            offerFor(source, manga, local, oursMax, baseName, exact = match in exact)
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
        }
        return emptyList()
    }

    /**
     * The continuation of [baseMangaId]: the other source's chapters past this work's last one, newest first.
     * With [refresh], the other source is asked for new chapters first.
     */
    suspend fun continuation(baseMangaId: Long, refresh: Boolean): List<Continuation> = withContext(Dispatchers.IO) {
        val fusion = fusionOf(baseMangaId) ?: return@withContext emptyList()
        val other = otherOf(fusion) ?: return@withContext emptyList()
        if (refresh) {
            runCatching {
                val source = sourceManager.get(fusion.otherSourceId) ?: return@runCatching
                val fresh = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    source.getMangaUpdate(other.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
                }
                if (!fresh.isNullOrEmpty()) syncChaptersWithSource.await(fresh, other, source)
            }.onFailure { logcat(LogPriority.WARN, it) { "Continuation of ${fusion.otherSourceName} not refreshed" } }
        }
        val ours = getChaptersByMangaId.await(baseMangaId).map { it.chapterNumber }
        val theirs = getChaptersByMangaId.await(other.id)
        // Chapters translated on earlier continuations come first: they keep their number's place
        val kept = fusion.kept.flatMap { layer ->
            val ids = layer.chapters.map { it.chapterId }.toSet()
            getChaptersByMangaId.await(layer.mangaId).filter { it.id in ids }.map { it.chapterNumber to it }
        }
        FusionRules.layered(ours, kept, theirs.map { it.chapterNumber to it })
            .map { (number, chapter) -> Continuation(number, chapter, fusion.sourceNameOf(chapter.mangaId)) }
    }

    /**
     * MANHWA: the continuation's chapters (of [layerMangaId]) worth keeping when the continuation moves to another
     * source: those [translated] says are translated or being translated, with what was kept before.
     */
    suspend fun keptOnChange(fusion: Fusion, translated: suspend (Chapter) -> Boolean): List<Kept> = withContext(Dispatchers.IO) {
        val shown = continuation(fusion.baseMangaId, refresh = false)
        val fromOther = shown.filter { it.chapter.mangaId == fusion.otherMangaId && translated(it.chapter) }
        val layer = Kept(
            mangaId = fusion.otherMangaId,
            sourceId = fusion.otherSourceId,
            sourceName = fusion.otherSourceName,
            lang = fusion.otherLang,
            chapters = fromOther.map { KeptChapter(it.number, it.chapter.id) },
        )
        (fusion.kept + layer).filter { it.chapters.isNotEmpty() }
    }

    /** The chapters a reader of [mangaId] (a continuation entry) goes through: its own in the fused list. */
    suspend fun readerChapterIds(mangaId: Long, chapterId: Long? = null): Set<Long>? = withContext(Dispatchers.IO) {
        val fusion = continuationOf(mangaId) ?: return@withContext null
        val fused = continuation(fusion.baseMangaId, refresh = false).map { Triple(it.number, it.chapter.mangaId, it.chapter.id) }
        // A chapter kept from an earlier source in the middle ends this run: the reader hands over to it there
        FusionRules.runAround(fused, mangaId, chapterId)
    }

    /**
     * The chapter to read after (or before) [chapterNumber] of [mangaId] across the fused work, as
     * (manga id, chapter id); null when the reader stays in its own list.
     */
    suspend fun neighbour(mangaId: Long, chapterNumber: Double, forward: Boolean): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val fusion = fusionOf(mangaId) ?: continuationOf(mangaId) ?: return@withContext null
        // The whole fused work in reading order: its own chapters, then the continuation (kept ones included)
        val timeline = getChaptersByMangaId.await(fusion.baseMangaId).map { it.chapterNumber to it } +
            continuation(fusion.baseMangaId, refresh = false).map { it.number to it.chapter }
        val target = if (forward) FusionRules.after(chapterNumber, timeline) else FusionRules.before(chapterNumber, timeline)
        // Within one entry the reader's own list goes on by itself
        target?.takeIf { it.mangaId != mangaId }?.let { it.mangaId to it.id }
    }

    /** The last chapter number this fused work reads from its own source: the other source's copies up to it are hidden. */
    suspend fun replacedUpTo(otherMangaId: Long): Double? = withContext(Dispatchers.IO) {
        val fusion = continuationOf(otherMangaId) ?: return@withContext null
        getChaptersByMangaId.await(fusion.baseMangaId).map { it.chapterNumber }.filter { FusionRules.countable(it) }.maxOrNull()
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val MAX_SOURCES = 8
        // Sources asked at the same time (each mostly waits on the network)
        const val PARALLEL = 6
        const val SEARCH_TIMEOUT_MS = 12_000L
        const val CHAPTERS_TIMEOUT_MS = 15_000L
        const val MAX_SAVED_OFFERS = 200
        const val OFFER_TTL_MS = 6 * 60 * 60 * 1000L
        const val LOOSE_PER_SOURCE = 3
    }
}

/** The pure rules of chapter fusion (tested without a phone). */
object FusionRules {

    /** A chapter number that means a real chapter (unnumbered extras are -1). */
    fun countable(number: Double): Boolean = number >= 0

    /** Only a source in another language continues a work (a second Arabic site is a mirror, not a continuation). */
    fun otherLanguage(baseLang: String, otherLang: String): Boolean {
        val a = baseLang.lowercase().substringBefore('-')
        val b = otherLang.lowercase().substringBefore('-')
        return b.isNotEmpty() && b != "all" && b != a
    }

    /** Whether a source in [sourceLang] is already in [targetLang] (its chapters need no translation). */
    fun sameLanguage(sourceLang: String, targetLang: String): Boolean {
        val a = sourceLang.lowercase().substringBefore('-')
        return a.isNotEmpty() && a == targetLang.lowercase().substringBefore('-')
    }

    /**
     * One line per fused work in a list newest first: [baseOf] gives the work a continuation entry belongs to,
     * and only the newest line of each work stays. Entries that are not fused stay as they are.
     */
    fun <T> mergeHistory(items: List<T>, mangaIdOf: (T) -> Long, baseOf: (Long) -> Long?): List<T> {
        val seen = HashSet<Long>()
        return items.filter { item ->
            val id = mangaIdOf(item)
            seen.add(baseOf(id) ?: id)
        }
    }

    /** A language's short code for badges ("en-US" is "EN"). */
    fun langCode(lang: String): String = lang.substringBefore('-').uppercase().ifBlank { "EN" }

    /** Worth offering when the other source has at least one more chapter. */
    fun worthOffering(oursMax: Double, theirsMax: Double): Boolean = theirsMax >= 0 && theirsMax > oursMax

    /**
     * The other source's chapters past this work's last one (one per number), newest first: this work's own
     * chapters always win their number, so a chapter that comes out here later replaces the other one by itself.
     */
    fun <T> continuation(ours: List<Double>, theirs: List<Pair<Double, T>>): List<Pair<Double, T>> {
        val oursMax = ours.filter { countable(it) }.maxOrNull() ?: -1.0
        return theirs.filter { countable(it.first) && it.first > oursMax }
            .groupBy { it.first }
            .map { (_, list) -> list.first() }
            .sortedByDescending { it.first }
    }

    /**
     * The continuation with chapters [kept] from earlier continuations: a kept chapter keeps its number's place,
     * the current continuation gives the numbers it does not have (both only past this work's last chapter).
     */
    fun <T> layered(ours: List<Double>, kept: List<Pair<Double, T>>, theirs: List<Pair<Double, T>>): List<Pair<Double, T>> =
        continuation(ours, kept + theirs)

    /**
     * The chapters of [mangaId] read in one go from [chapterId] in the fused list of (number, manga id, chapter id):
     * they stop where another entry's chapter (one kept from an earlier source) sits between them, so the reader
     * reaches an edge there and goes on from that entry. Without [chapterId], or when it is not in the list, all of
     * [mangaId]'s chapters.
     */
    fun runAround(fused: List<Triple<Double, Long, Long>>, mangaId: Long, chapterId: Long?): Set<Long> {
        val own = fused.filter { it.second == mangaId }.map { it.third }.toSet()
        val ordered = fused.filter { countable(it.first) }.sortedBy { it.first }
        val index = ordered.indexOfFirst { it.third == chapterId }
        if (index < 0 || ordered[index].second != mangaId) return own
        var from = index
        while (from > 0 && ordered[from - 1].second == mangaId) from--
        var to = index
        while (to < ordered.lastIndex && ordered[to + 1].second == mangaId) to++
        val run = ordered.subList(from, to + 1).map { it.third }.toSet()
        // Unnumbered extras of this entry stay where they are
        return run + fused.filter { it.second == mangaId && !countable(it.first) }.map { it.third }
    }

    /** The chapters in [after] that were not there [before] (by id): what a refresh brought. */
    fun <T> newChapters(before: Set<Long>, after: List<T>, idOf: (T) -> Long): List<T> = after.filter { idOf(it) !in before }

    /** The first chapter after [number]. */
    fun <T> after(number: Double, chapters: List<Pair<Double, T>>): T? =
        chapters.filter { countable(it.first) && it.first > number }.minByOrNull { it.first }?.second

    /** The last chapter before [number]. */
    fun <T> before(number: Double, chapters: List<Pair<Double, T>>): T? =
        chapters.filter { countable(it.first) && it.first < number }.maxByOrNull { it.first }?.second

    /** The other source's chapters the reader skips there: those this work reads from its own source. */
    private val seasonWords = Regex("\\b(season|part|s|vol|volume)\\s*\\d+\\b|\\b(remake|reboot|novel)\\b", RegexOption.IGNORE_CASE)

    /**
     * Two titles of the same work: the same after cleaning ([WorkIdentity]), or the same words in another order
     * or with ":", "-" and "the" in other places ("Solo Leveling: Ragnarok" and "Ragnarok - Solo Leveling").
     * A season or part number never matches a title without it, so a sequel is never taken for the first work.
     */
    fun sameName(a: String, b: String): Boolean {
        if (WorkIdentity.same(a, b)) return true
        val sa = seasonWords.findAll(a.lowercase()).map { it.value.filter(Char::isLetterOrDigit) }.toSet()
        val sb = seasonWords.findAll(b.lowercase()).map { it.value.filter(Char::isLetterOrDigit) }.toSet()
        if (sa != sb) return false
        val wa = words(a)
        val wb = words(b)
        return wa.size >= 2 && wa == wb
    }

    private fun words(title: String): Set<String> =
        title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in STOP_WORDS }.toSet()

    private val STOP_WORDS = setOf("the", "a", "an", "of", "official", "manhwa", "webtoon", "comic")

    fun keepInContinuation(number: Double, replacedUpTo: Double?): Boolean =
        replacedUpTo == null || !countable(number) || number > replacedUpTo

    private val latinRun = Regex("[A-Za-z][A-Za-z0-9'’:!?.,&\\- ]{3,}")

    /**
     * The titles to look a work up by: its own title, then the Latin-letter names in its description (Arabic
     * sites usually list the English name there), at most three.
     */
    fun titlesOf(title: String, description: String?): List<String> {
        val extra = description.orEmpty().lines()
            .flatMap { line -> line.split('،', ',', '|', '/', ';', '؛').mapNotNull { latinRun.find(it)?.value?.trim(' ', '.', ',', ':', '-') } }
            .filter { name -> name.count(Char::isLetter).let { it >= 8 || (it >= 6 && name.contains(' ')) } }
        return (listOf(title) + extra)
            .map { it.trim() }
            .filter { WorkIdentity.key(it).length >= WorkIdentity.MIN_KEY }
            .distinctBy { WorkIdentity.key(it) }
            .take(3)
    }
}
