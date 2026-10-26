package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.translation.glossary.WorkIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.domain.manga.model.toDomainManga
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.ln
import tachiyomi.domain.manga.model.MangaCover as CoverData

/**
 * MANHWA: the best series of one source. Its popular pages are read, the series already in the library are left
 * out, and the rest are ranked by their AniList score and popularity (public data, one batch request for ten
 * titles); a series AniList does not know keeps the source's own order after the ranked ones.
 */
class SourceBestScreen(private val sourceId: Long, private val sourceName: String) : Screen() {

    data class Best(val manga: SManga, val score: Int?, val popularity: Int?)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val scope = rememberCoroutineScope()
        var list by remember { mutableStateOf<List<Best>?>(cache[sourceId]) }
        var failed by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            if (list != null) return@LaunchedEffect
            try {
                list = withContext(Dispatchers.IO) { rank(graph) }.also { cache[sourceId] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.source_best_title), subtitle = sourceName, onBack = navigator::pop)
                val items = list
                when {
                    failed != null -> Text(failed!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(stringResource(ATMR.strings.source_best_loading), Modifier.padding(12.dp), color = ManhwaInk.muted)
                        }
                    }
                    items.isEmpty() -> Text(stringResource(ATMR.strings.source_best_empty), Modifier.padding(24.dp), color = ManhwaInk.muted)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(items, key = { i, b -> "${b.manga.url}#$i" }) { i, best ->
                            Column(
                                Modifier.pressClickable {
                                    scope.launch {
                                        val local = withContext(Dispatchers.IO) {
                                            graph.networkToLocalManga(best.manga.toDomainManga(sourceId))
                                        }
                                        navigator.push(MangaScreen(local.id, true))
                                    }
                                },
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box {
                                    MangaCover.Book(CoverData(-1L, sourceId, false, best.manga.thumbnail_url, null), Modifier.fillMaxWidth())
                                    Text(
                                        "#${i + 1}" + (best.score?.let { " · ★ ${"%.1f".format(it / 10f)}" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ManhwaInk.text,
                                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                            .glass(RoundedCornerShape(8.dp), accent = i < 3).padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Text(best.manga.title, style = MaterialTheme.typography.labelMedium, color = ManhwaInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                best.popularity?.let {
                                    Text(stringResource(ATMR.strings.source_best_fans, compact(it)), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    internal suspend fun rank(graph: mihon.app.di.AppGraph): List<Best> {
        val source = graph.sourceManager.get(sourceId) as? CatalogueSource ?: return emptyList()
        // The library (from any source) is left out: those series are already kept
        val kept = graph.getFavorites.await().map { WorkIdentity.key(it.title) }.toSet()
        val keptUrls = graph.getFavorites.await().filter { it.source == sourceId }.map { it.url }.toSet()
        val popular = buildList {
            for (page in 1..PAGES) {
                val result = runCatching { source.getPopularManga(page) }.getOrNull() ?: break
                addAll(result.mangas)
                if (!result.hasNextPage) break
            }
        }.distinctBy { it.url }
            .filter { it.url !in keptUrls && WorkIdentity.key(it.title) !in kept }
        val scores = popular.chunked(BATCH).flatMap { chunk ->
            runCatching { aniList(graph, chunk.map { it.title }) }
                .onFailure { logcat(LogPriority.WARN, it) { "Best of source: AniList not read" } }
                .getOrDefault(chunk.map { null })
        }
        val ranked = popular.zip(scores) { m, s -> Best(m, s?.first, s?.second) }
        val known = ranked.filter { it.score != null || it.popularity != null }
            .sortedByDescending { (it.score ?: DEFAULT_SCORE) * ln((it.popularity ?: 0) + 10.0) }
        return known + ranked.filter { it.score == null && it.popularity == null }
    }

    /** Score (0-100) and popularity of each title on AniList, in one request (null when not found for sure). */
    private fun aniList(graph: mihon.app.di.AppGraph, titles: List<String>): List<Pair<Int?, Int?>?> {
        val fields = titles.indices.joinToString(" ") { i ->
            "m$i: Media(search: \$s$i, type: MANGA) { averageScore popularity synonyms title { romaji english native } }"
        }
        val params = titles.indices.joinToString(", ") { "\$s$it: String" }
        val body = buildJsonObject {
            put("query", "query($params) { $fields }")
            put("variables", buildJsonObject { titles.forEachIndexed { i, t -> put("s$i", t.take(100)) } })
        }
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = graph.networkHelper.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("AniList HTTP ${r.code}")
            r.body.string()
        }
        val data = Json.parseToJsonElement(text).jsonObject["data"] as? JsonObject ?: return titles.map { null }
        return titles.mapIndexed { i, title ->
            val m = data["m$i"] as? JsonObject ?: return@mapIndexed null
            val t = m["title"] as? JsonObject
            val names = listOfNotNull(t?.str("romaji"), t?.str("english"), t?.str("native")) +
                (m["synonyms"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            // Only a sure match counts (the search returns its nearest title even for another work)
            if (names.none { WorkIdentity.same(it, title) }) return@mapIndexed null
            (m["averageScore"] as? JsonPrimitive)?.intOrNull to (m["popularity"] as? JsonPrimitive)?.intOrNull
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun compact(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }

    companion object {
        private const val PAGES = 3
        private const val BATCH = 10
        private const val DEFAULT_SCORE = 60

        // The ranking of each source for this run of the app (AniList is asked once)
        private val cache = java.util.concurrent.ConcurrentHashMap<Long, List<Best>>()
    }
}
