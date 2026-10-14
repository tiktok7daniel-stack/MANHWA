package eu.kanade.tachiyomi.ui.home.discover

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.translation.search.AiSearchAssistant
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import java.util.Locale
import kotlin.time.Clock

/**
 * The thinking parts of the home screen: AI recommendations with a reason for each one, and alerts when a
 * series of the library already has a newer chapter on another of the reader's sources.
 */
@Inject
@SingleIn(AppScope::class)
class HomeIntelligence(
    private val translationPreferences: TranslationPreferences,
    private val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val getLibraryManga: GetLibraryManga,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
) {

    data class Recommendation(val manga: Manga, val source: Source, val reason: String)

    data class SourceAlert(
        val libraryManga: Manga,
        val otherManga: Manga,
        val source: Source,
        val chapter: SChapter,
        val chapterNumber: Double,
        val ourNumber: Double,
        val daysAgo: Int?,
    )

    @Volatile private var alertsCache: Pair<Long, List<SourceAlert>>? = null

    fun aiConfigured(): Boolean {
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        return engine.isAi && (translationPreferences.translationEngineApiKey().get().isNotBlank() || engine == TextTranslators.OPENAI)
    }

    /** Cached recommendations, restored without any network call. */
    suspend fun cachedRecommendations(): List<Recommendation> {
        val raw = sourcePreferences.manhwaAiRecs.get().ifBlank { return emptyList() }
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val manga = getManga.await((obj["m"] as JsonPrimitive).long) ?: return@mapNotNull null
                val source = sourceManager.get(manga.source) ?: return@mapNotNull null
                Recommendation(manga, source, (obj["r"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
        }.getOrDefault(emptyList())
    }

    fun recommendationsAreFresh(): Boolean =
        System.currentTimeMillis() - sourcePreferences.manhwaAiRecsAt.get() < RECS_TTL_MS

    /**
     * Asks the AI for series that fit the library, then finds each one in [sources].
     * Throws [AiSearchAssistant.NotConfiguredException] when no AI engine is set up.
     */
    suspend fun recommend(sources: List<Source>): List<Recommendation> {
        if (!aiConfigured()) throw AiSearchAssistant.NotConfiguredException()
        val library = getLibraryManga.await()
        val owned = library.map { normalize(it.manga.title) }.toSet()
        val profile = library
            .sortedByDescending { it.lastRead?.toEpochMilliseconds() ?: 0L }
            .take(40)
            .joinToString("\n") { item ->
                "- ${item.manga.title} [${item.manga.genre.orEmpty().take(5).joinToString()}] read ${item.readCount}/${item.totalChapters}"
            }
        val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        val ai = TextTranslators.fromPref(translationPreferences.translationEngine()).build(translationPreferences) as AiTranslator
        val reply = try {
            ai.askJson(
                system = """
                    You are an expert curator of manhwa, manga, manhua and webtoons.
                    From the reader's library, recommend $MAX_RECS series they do NOT have yet and will love.
                    Prefer popular, still-available series. Use the English title most scanlation sites use.
                    For each, give a short reason (max 90 characters) in $language that names what in their
                    reading it matches, e.g. "Because you read leveling-system stories like Solo Leveling".
                    Reply with ONLY: {"items":[{"title":"...","reason":"..."}]}
                """.trimIndent(),
                prompt = "My library (most recent first):\n${profile.ifBlank { "(empty, suggest the best-known hits)" }}",
            )
        } finally {
            ai.close()
        }
        val items = (reply["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            (title to reason).takeIf { title.isNotEmpty() && normalize(title) !in owned }
        }
        val gate = Semaphore(PARALLEL)
        val found = coroutineScope {
            items.map { (title, reason) ->
                async { gate.withPermit { find(title, sources)?.let { (manga, source) -> Recommendation(manga, source, reason) } } }
            }.awaitAll().filterNotNull().distinctBy { it.manga.id }
        }
        sourcePreferences.manhwaAiRecs.set(
            buildJsonArray {
                found.forEach { add(buildJsonObject { put("m", it.manga.id); put("r", it.reason) }) }
            }.toString(),
        )
        sourcePreferences.manhwaAiRecsAt.set(System.currentTimeMillis())
        return found
    }

    /** The first match for [title] across [sources], saved locally so it can be opened. */
    private suspend fun find(title: String, sources: List<Source>): Pair<Manga, Source>? {
        val wanted = normalize(title)
        var fallback: Pair<Manga, Source>? = null
        for (source in sources) {
            val page = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                runCatching { source.getSearchManga(1, title, FilterList()) }.getOrNull()
            } ?: continue
            val exact = page.mangas.firstOrNull { normalize(it.title) == wanted }
            val pick = exact ?: page.mangas.firstOrNull()?.takeIf { fallback == null && normalize(it.title).contains(wanted) }
            if (pick != null) {
                val local = runCatching { networkToLocalManga(listOf(pick.toDomainManga(source.id))).first() }.getOrNull()
                // Stop at the first good match instead of searching every source
                if (local != null) return local to source
            }
        }
        return fallback
    }

    /** Library series whose newest chapter came out on another of [sources] first. Cached for a while. */
    suspend fun alerts(sources: List<Source>, force: Boolean = false): List<SourceAlert> {
        alertsCache?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < ALERTS_TTL_MS) return list }
        if (sources.size < 2) return emptyList()
        val candidates = getLibraryManga.await()
            .filter { it.lastRead != null }
            .sortedByDescending { it.lastRead!!.toEpochMilliseconds() }
            .take(MAX_ALERT_CANDIDATES)
        val gate = Semaphore(PARALLEL)
        val alerts = coroutineScope {
            candidates.map { item ->
                async {
                    gate.withPermit {
                        val manga = item.manga
                        val ourNumber = getChaptersByMangaId.await(manga.id).maxOfOrNull { it.chapterNumber } ?: return@withPermit null
                        sources.filter { it.id != manga.source }.firstNotNullOfOrNull { source ->
                            newerOn(source, manga, ourNumber)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        alertsCache = System.currentTimeMillis() to alerts
        return alerts
    }

    private suspend fun newerOn(source: Source, manga: Manga, ourNumber: Double): SourceAlert? {
        val wanted = normalize(manga.title)
        val match = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            runCatching { source.getSearchManga(1, manga.title, FilterList()) }.getOrNull()
        }?.mangas?.firstOrNull { normalize(it.title) == wanted } ?: return null
        val chapters = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            runCatching { source.getMangaUpdate(match, emptyList(), fetchDetails = false, fetchChapters = true).chapters }.getOrNull()
        } ?: return null
        val newest = chapters.maxByOrNull { number(manga.title, it) } ?: return null
        val newestNumber = number(manga.title, newest)
        if (newestNumber <= ourNumber) return null
        val local = runCatching { networkToLocalManga(listOf(match.toDomainManga(source.id))).first() }.getOrNull() ?: return null
        val daysAgo = newest.date_upload.takeIf { it > 0 }?.let {
            ((Clock.System.now().toEpochMilliseconds() - it) / 86_400_000L).toInt().coerceAtLeast(0)
        }
        return SourceAlert(manga, local, source, newest, newestNumber, ourNumber, daysAgo)
    }

    /** Saves the other source's chapters and returns the ids to open the new chapter in the reader. */
    suspend fun openAlert(alert: SourceAlert): Pair<Long, Long>? {
        val source = alert.source
        val chapters = runCatching {
            source.getMangaUpdate(alert.otherManga.toSManga(), emptyList(), fetchDetails = false, fetchChapters = true).chapters
        }.getOrNull() ?: return null
        runCatching { syncChaptersWithSource.await(chapters, alert.otherManga, source) }
        val chapter = getChapterByUrlAndMangaId.await(alert.chapter.url, alert.otherManga.id) ?: return null
        return alert.otherManga.id to chapter.id
    }

    private fun number(title: String, chapter: SChapter): Double =
        ChapterRecognition.parseChapterNumber(title, chapter.name, chapter.chapter_number.toDouble())

    private fun normalize(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        const val MAX_RECS = 10
        const val PARALLEL = 3
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val MAX_ALERT_CANDIDATES = 10
        const val RECS_TTL_MS = 24 * 60 * 60 * 1000L
        const val ALERTS_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
