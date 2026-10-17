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
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager
import eu.kanade.translation.glossary.WorkIdentity
import eu.kanade.translation.readTextAtomic
import eu.kanade.translation.writeTextAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    )

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
    )

    /** A chapter of the continuation, shown past this work's own chapters. */
    data class Continuation(val number: Double, val chapter: Chapter)

    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "chapter_fusions.json")
    private val lock = Any()

    private val saved = MutableStateFlow(load())
    private val _fusions = MutableStateFlow(saved.value.fusions.associateBy { it.baseMangaId })
    val fusions: StateFlow<Map<Long, Fusion>> = _fusions.asStateFlow()

    private val offers = ConcurrentHashMap<Long, Pair<Long, List<Offer>>>()

    private fun load(): Saved = runCatching { json.decodeFromString<Saved>(file.readTextAtomic()) }.getOrDefault(Saved())

    private fun save(next: Saved) {
        saved.value = next
        _fusions.value = next.fusions.associateBy { it.baseMangaId }
        runCatching { file.writeTextAtomic(json.encodeToString(next)) }
            .onFailure { logcat(LogPriority.WARN, it) { "Fusions not saved" } }
    }

    fun fusionOf(baseMangaId: Long): Fusion? = _fusions.value[baseMangaId]

    /** The fusion this entry is the continuation of, when it is one. */
    fun continuationOf(otherMangaId: Long): Fusion? = _fusions.value.values.firstOrNull { it.otherMangaId == otherMangaId }

    fun isDismissed(baseMangaId: Long): Boolean = baseMangaId in saved.value.dismissed

    fun dismiss(baseMangaId: Long) = synchronized(lock) {
        save(saved.value.copy(dismissed = saved.value.dismissed + baseMangaId))
    }

    fun fuse(offer: Offer) = synchronized(lock) {
        val fusion = Fusion(
            baseMangaId = offer.baseMangaId,
            baseSourceName = offer.baseSourceName,
            otherMangaId = offer.other.id,
            otherSourceId = offer.otherSourceId,
            otherSourceName = offer.otherSourceName,
            otherLang = offer.otherLang,
            createdAt = System.currentTimeMillis(),
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

    /**
     * Looks (at most every few hours) on the reader's sources in other languages for the same work with more
     * chapters than [manga] has here; the one that goes furthest is offered. Null when none goes further.
     */
    suspend fun findOffer(manga: Manga, force: Boolean = false): Offer? = findOffers(manga, force).firstOrNull()

    /** Every source that goes further than this one, the furthest first. */
    suspend fun findOffers(manga: Manga, force: Boolean = false): List<Offer> = withContext(Dispatchers.IO) {
        offers[manga.id]?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < OFFER_TTL_MS) return@withContext list }
        val ours = getChaptersByMangaId.await(manga.id).map { it.chapterNumber }
        val oursMax = ours.filter { FusionRules.countable(it) }.maxOrNull() ?: -1.0
        val base = sourceManager.get(manga.source)
        val baseLang = (base as? CatalogueSource)?.lang.orEmpty()
        val preferred = (sourcePreferences.homeSources.get() + sourcePreferences.pinnedSources.get()).mapNotNull { it.toLongOrNull() }.toSet()
        val candidates = sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id != LOCAL_SOURCE_ID && it.id != manga.source && !sourceHealth.isHidden(it.id) }
            .filter { FusionRules.otherLanguage(baseLang, it.lang) }
            .sortedWith(compareBy({ it.id !in preferred }, { it.lang != "en" }))
            .take(MAX_SOURCES)
        val titles = FusionRules.titlesOf(manga.title, manga.description)
        val gate = Semaphore(PARALLEL)
        val found = coroutineScope {
            candidates.map { source ->
                async {
                    gate.withPermit {
                        runCatching { offerOn(source, manga, titles, oursMax, base?.name.orEmpty()) }
                            .onFailure { logcat(LogPriority.WARN, it) { "Fusion search on ${source.name} failed" } }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val sorted = found.sortedByDescending { it.theirsMax }
        offers[manga.id] = System.currentTimeMillis() to sorted
        sorted
    }

    private suspend fun offerOn(source: CatalogueSource, manga: Manga, titles: List<String>, oursMax: Double, baseName: String): Offer? {
        for (title in titles) {
            val page = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { source.getSearchManga(1, title, FilterList()) } ?: continue
            val match = page.mangas.firstOrNull { found -> titles.any { WorkIdentity.same(found.title, it) } } ?: continue
            val local = networkToLocalManga(listOf(match.toDomainManga(source.id))).first()
            val chapters = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                source.getMangaUpdate(local.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
            } ?: return null
            if (chapters.isNotEmpty()) runCatching { syncChaptersWithSource.await(chapters, local, source) }
            val theirsMax = getChaptersByMangaId.await(local.id).map { it.chapterNumber }.filter { FusionRules.countable(it) }.maxOrNull() ?: return null
            if (!FusionRules.worthOffering(oursMax, theirsMax)) return null
            return Offer(manga.id, baseName, oursMax, local, source.id, source.name, source.lang, theirsMax)
        }
        return null
    }

    /**
     * The continuation of [baseMangaId]: the other source's chapters past this work's last one, newest first.
     * With [refresh], the other source is asked for new chapters first.
     */
    suspend fun continuation(baseMangaId: Long, refresh: Boolean): List<Continuation> = withContext(Dispatchers.IO) {
        val fusion = fusionOf(baseMangaId) ?: return@withContext emptyList()
        if (refresh) {
            runCatching {
                val source = sourceManager.get(fusion.otherSourceId) ?: return@runCatching
                val other = getManga.await(fusion.otherMangaId) ?: return@runCatching
                val fresh = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    source.getMangaUpdate(other.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
                }
                if (!fresh.isNullOrEmpty()) syncChaptersWithSource.await(fresh, other, source)
            }.onFailure { logcat(LogPriority.WARN, it) { "Continuation of ${fusion.otherSourceName} not refreshed" } }
        }
        val ours = getChaptersByMangaId.await(baseMangaId).map { it.chapterNumber }
        val theirs = getChaptersByMangaId.await(fusion.otherMangaId)
        FusionRules.continuation(ours, theirs.map { it.chapterNumber to it })
            .map { (number, chapter) -> Continuation(number, chapter) }
    }

    /**
     * The chapter to read after (or before) [chapterNumber] of [mangaId] across the fused work, as
     * (manga id, chapter id); null when the reader stays in its own list.
     */
    suspend fun neighbour(mangaId: Long, chapterNumber: Double, forward: Boolean): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val asBase = fusionOf(mangaId)
        val asOther = continuationOf(mangaId)
        when {
            asBase != null && forward -> {
                val ours = getChaptersByMangaId.await(mangaId).map { it.chapterNumber }
                val theirs = getChaptersByMangaId.await(asBase.otherMangaId)
                FusionRules.continuation(ours, theirs.map { it.chapterNumber to it })
                    .map { it.first to it.second }
                    .let { FusionRules.after(chapterNumber, it) }
                    ?.let { asBase.otherMangaId to it.id }
            }
            asOther != null && !forward -> {
                val ours = getChaptersByMangaId.await(asOther.baseMangaId)
                FusionRules.before(chapterNumber, ours.map { it.chapterNumber to it })
                    ?.let { asOther.baseMangaId to it.id }
            }
            else -> null
        }
    }

    /** The last chapter number this fused work reads from its own source: the other source's copies up to it are hidden. */
    suspend fun replacedUpTo(otherMangaId: Long): Double? = withContext(Dispatchers.IO) {
        val fusion = continuationOf(otherMangaId) ?: return@withContext null
        getChaptersByMangaId.await(fusion.baseMangaId).map { it.chapterNumber }.filter { FusionRules.countable(it) }.maxOrNull()
    }

    private companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val MAX_SOURCES = 8
        const val PARALLEL = 3
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val OFFER_TTL_MS = 6 * 60 * 60 * 1000L
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

    /** The first chapter after [number]. */
    fun <T> after(number: Double, chapters: List<Pair<Double, T>>): T? =
        chapters.filter { countable(it.first) && it.first > number }.minByOrNull { it.first }?.second

    /** The last chapter before [number]. */
    fun <T> before(number: Double, chapters: List<Pair<Double, T>>): T? =
        chapters.filter { countable(it.first) && it.first < number }.maxByOrNull { it.first }?.second

    /** The other source's chapters the reader skips there: those this work reads from its own source. */
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
