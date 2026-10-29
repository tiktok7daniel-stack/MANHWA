package eu.kanade.tachiyomi.novel.bridge

import android.content.Context
import eu.kanade.tachiyomi.novel.NovelStore
import eu.kanade.tachiyomi.novel.searchFilters
import eu.kanade.translation.glossary.WorkIdentity
import ireader.core.source.CatalogSource
import ireader.core.source.model.MangaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.app.di.appGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * MANHWA novel bridge: a manhwa and the novel it was drawn from, side by side on its page.
 *
 * The novel is found from facts, never from a guess: AniList (public, no key) says which novel a manhwa adapts
 * (its "source" relation) and every title that novel goes by; the installed novel sources are searched for those
 * titles, and only a result whose title is the same work ([WorkIdentity.same]) is offered as that novel. Anything
 * else is listed as "maybe" for the reader to pick by hand. The pick is kept per manhwa.
 */
object NovelBridge {

    /** The novel a manhwa is linked to, in one installed novel source. */
    @Serializable
    data class Link(
        val sourceId: Long,
        val url: String,
        val title: String,
        val cover: String = "",
        /** Picked by the reader rather than found as the same title. */
        val manual: Boolean = false,
        val at: Long = 0L,
    ) {
        val novelKey: String get() = NovelStore.key(sourceId, url)
    }

    /** Where a manhwa chapter is told in the novel, found from what both say. */
    @Serializable
    data class Place(
        val manhwaNumber: Double,
        val novelIndex: Int,
        val novelUrl: String,
        val novelName: String,
        val share: Double,
        val at: Long = 0L,
    )

    @Serializable
    private data class Saved(
        val link: Link? = null,
        /** The novel's titles from AniList ("" when it has none), and when they were asked for. */
        val titles: List<String> = emptyList(),
        val titlesAt: Long = 0L,
        /** By manhwa chapter id. */
        val places: Map<String, Place> = emptyMap(),
    )

    /** A novel a source has under one of the titles looked for. */
    data class Candidate(
        val sourceId: Long,
        val sourceName: String,
        val lang: String,
        val info: MangaInfo,
        /** Its title is the same work as one of the titles (not just a search hit). */
        val exact: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val fileLock = Mutex()
    private val anilistGate = Mutex()
    private var lastAnilist = 0L
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    private const val SEARCH_TIMEOUT_MS = 20_000L
    private const val PARALLEL_SOURCES = 4

    private val _links = MutableStateFlow<Map<Long, Link?>>(emptyMap())

    /** Links already read, by manga id (missing = not read yet). */
    val links: StateFlow<Map<Long, Link?>> = _links.asStateFlow()

    private fun file(context: Context, mangaId: Long) = File(context.filesDir, "novel_bridge/$mangaId.json")

    private suspend fun read(context: Context, mangaId: Long): Saved = withContext(Dispatchers.IO) {
        fileLock.withLock {
            runCatching { json.decodeFromString(Saved.serializer(), file(context, mangaId).readText()) }.getOrNull() ?: Saved()
        }
    }

    private suspend fun change(context: Context, mangaId: Long, edit: (Saved) -> Saved): Saved = withContext(Dispatchers.IO) {
        fileLock.withLock {
            val f = file(context, mangaId)
            val old = runCatching { json.decodeFromString(Saved.serializer(), f.readText()) }.getOrNull() ?: Saved()
            val new = edit(old)
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.encodeToString(Saved.serializer(), new))
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }.onFailure { logcat(LogPriority.ERROR, it) { "Novel bridge not saved" } }
            new
        }
    }

    suspend fun link(context: Context, mangaId: Long): Link? =
        read(context, mangaId).link.also { l -> _links.update { it + (mangaId to l) } }

    suspend fun setLink(context: Context, mangaId: Long, candidate: Candidate) {
        val link = Link(
            sourceId = candidate.sourceId,
            url = candidate.info.key,
            title = candidate.info.title,
            cover = candidate.info.cover,
            manual = !candidate.exact,
            at = System.currentTimeMillis(),
        )
        // Places found in another novel do not hold for this one
        change(context, mangaId) { it.copy(link = link, places = emptyMap()) }
        _links.update { it + (mangaId to link) }
    }

    suspend fun unlink(context: Context, mangaId: Long) {
        change(context, mangaId) { it.copy(link = null, places = emptyMap()) }
        _links.update { it + (mangaId to null) }
    }

    suspend fun places(context: Context, mangaId: Long): Map<Long, Place> =
        read(context, mangaId).places.mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v } }.toMap()

    suspend fun savePlace(context: Context, mangaId: Long, chapterId: Long, place: Place) {
        change(context, mangaId) { it.copy(places = it.places + (chapterId.toString() to place)) }
    }

    // ---------------------------------------------------------------- Finding the novel

    /**
     * The titles the manhwa's novel goes by, from AniList's "source" relation of the manhwa whose own title is
     * [mangaTitle] (the same work, not a look-alike). Empty when AniList has no such manhwa or no novel for it.
     */
    suspend fun novelTitles(context: Context, mangaId: Long, mangaTitle: String): List<String> {
        val saved = read(context, mangaId)
        if (saved.titlesAt > 0 && System.currentTimeMillis() - saved.titlesAt < WEEK_MS) return saved.titles
        val fresh = runCatching { fetchNovelTitles(context, mangaTitle) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { logcat(LogPriority.WARN, it) { "Novel titles for $mangaTitle" } }
            .getOrNull() ?: return saved.titles
        change(context, mangaId) { it.copy(titles = fresh, titlesAt = System.currentTimeMillis()) }
        return fresh
    }

    private suspend fun fetchNovelTitles(context: Context, mangaTitle: String): List<String> = anilistGate.withLock {
        val wait = 1_000 - (System.currentTimeMillis() - lastAnilist)
        if (wait > 0) delay(wait)
        lastAnilist = System.currentTimeMillis()
        val body = buildJsonObject {
            put("query", QUERY)
            put("variables", buildJsonObject { put("search", mangaTitle.take(100)) })
        }
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = withContext(Dispatchers.IO) {
            context.appGraph.networkHelper.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("AniList HTTP ${r.code}")
                r.body.string()
            }
        }
        val page = (json.parseToJsonElement(text).jsonObject["data"] as? JsonObject)?.get("Page") as? JsonObject
        val media = (page?.get("media") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Only the manhwa that is this work: AniList's first search hit can be another one
        val mine = media.firstOrNull { m -> titlesOf(m).any { WorkIdentity.same(it, mangaTitle) } } ?: return@withLock emptyList()
        val edges = ((mine["relations"] as? JsonObject)?.get("edges") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        edges.filter { e ->
            val node = e["node"] as? JsonObject
            e.str("relationType") == "SOURCE" && node?.str("format") == "NOVEL"
        }.flatMap { titlesOf(it["node"] as JsonObject) }.distinctBy { WorkIdentity.key(it) }
    }

    private fun titlesOf(m: JsonObject): List<String> {
        val t = m["title"] as? JsonObject
        val names = listOfNotNull(t?.str("english"), t?.str("romaji"))
        val synonyms = (m["synonyms"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            // Latin names only: the novel sources are searched in English
            .filter { s -> s.any { it.isLetter() } && s.count { it in 'a'..'z' || it in 'A'..'Z' } * 2 >= s.count { it.isLetter() } }
        return (names + synonyms).filter { it.isNotBlank() }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * Searches every installed novel source for the manhwa's novel: its AniList titles, and the manhwa's own title
     * (a novel often has the same name). [onProgress] gets how many sources answered of how many.
     */
    suspend fun find(
        context: Context,
        mangaId: Long,
        mangaTitle: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Candidate> = coroutineScope {
        val graph = context.appGraph
        withContext(Dispatchers.IO) { eu.kanade.tachiyomi.novel.NovelSourceHealth.load(context) }
        // Sources known not to search are skipped: they would only run out the clock
        val sources = graph.novelExtensionManager.sources().filter { eu.kanade.tachiyomi.novel.NovelSourceHealth.searchable(it.id) }
        if (sources.isEmpty()) return@coroutineScope emptyList()
        val titles = (novelTitles(context, mangaId, mangaTitle) + mangaTitle).distinctBy { WorkIdentity.key(it) }
        val queries = titles.take(3)
        val gate = Semaphore(PARALLEL_SOURCES)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, sources.size)
        sources.map { source ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val found = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchOne(source, queries) }.orEmpty()
                    onProgress(done.incrementAndGet(), sources.size)
                    found.map { info ->
                        Candidate(
                            sourceId = source.id,
                            sourceName = source.name,
                            lang = source.lang,
                            info = info,
                            exact = titles.any { WorkIdentity.same(it, info.title) },
                        )
                    }
                }
            }
        }.awaitAll().flatten()
            .distinctBy { it.sourceId to it.info.key }
            // Same works first, English before other languages
            .sortedWith(compareByDescending<Candidate> { it.exact }.thenByDescending { it.lang == "en" })
    }

    private suspend fun searchOne(source: CatalogSource, queries: List<String>): List<MangaInfo> {
        val out = mutableListOf<MangaInfo>()
        for (q in queries) {
            val page = try {
                source.getMangaList(searchFilters(source, q), 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN) { "Novel search on ${source.name} failed: ${e.message}" }
                continue
            }
            out += page.mangas.filter { it.key.isNotBlank() && it.title.isNotBlank() }.take(8)
            // A same-title hit is what we came for; the other queries would only add noise
            if (out.any { WorkIdentity.same(it.title, q) }) break
        }
        return out
    }

    private const val QUERY = "query (\$search: String) { Page(perPage: 6) { media(search: \$search, type: MANGA) { " +
        "title { romaji english } synonyms relations { edges { relationType node { type format " +
        "title { romaji english } synonyms } } } } } }"
}
