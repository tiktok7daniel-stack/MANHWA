package eu.kanade.translation.characters

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.roundedfilled.Star
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

/**
 * MANHWA: works like this one (the way Kotatsu suggests), from the recommendations AniList's readers voted for. One
 * public request per work (no key, no account), kept for a week, at most one a second. A tap searches every
 * installed source for that title.
 */
object SimilarWorks {

    @Serializable
    data class Work(val title: String, val cover: String, val score: Int, val country: String)

    @Serializable
    private data class Saved(val at: Long, val works: List<Work>)

    private val json = Json { ignoreUnknownKeys = true }
    private val gate = Mutex()
    private var lastCall = 0L
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    suspend fun load(context: Context, mangaId: Long, title: String): List<Work> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "manhwa_similar/$mangaId.json")
        val old = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()
        if (old != null && System.currentTimeMillis() - old.at < WEEK_MS) return@withContext old.works
        val fresh = runCatching { fetch(context, title) }
            .onFailure { logcat(LogPriority.WARN, it) { "Similar works for $title" } }
            .getOrNull()
        if (fresh != null) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(Saved.serializer(), Saved(System.currentTimeMillis(), fresh)))
            }
        }
        fresh ?: old?.works.orEmpty()
    }

    private suspend fun fetch(context: Context, title: String): List<Work> = gate.withLock {
        val wait = 1_000 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        val body = buildJsonObject {
            put("query", QUERY)
            put("variables", buildJsonObject { put("search", title.take(100)) })
        }
        val request = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = context.appGraph.networkHelper.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("AniList HTTP ${r.code}")
            r.body.string()
        }
        val media = (json.parseToJsonElement(text).jsonObject["data"] as? JsonObject)?.get("Media") as? JsonObject
            ?: return@withLock emptyList()
        val nodes = ((media["recommendations"] as? JsonObject)?.get("nodes") as? JsonArray).orEmpty()
        nodes.mapNotNull { n ->
            val rec = (n as? JsonObject)?.get("mediaRecommendation") as? JsonObject ?: return@mapNotNull null
            val t = rec["title"] as? JsonObject
            val name = t?.str("english") ?: t?.str("romaji") ?: return@mapNotNull null
            Work(
                title = name,
                cover = (rec["coverImage"] as? JsonObject)?.str("large").orEmpty(),
                score = (rec["averageScore"] as? JsonPrimitive)?.intOrNull ?: 0,
                country = rec.str("countryOfOrigin").orEmpty(),
            )
        }.distinctBy { it.title }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private const val QUERY = "query (\$search: String) { Media(search: \$search, type: MANGA) { id " +
        "recommendations(perPage: 15, sort: RATING_DESC) { nodes { mediaRecommendation { " +
        "title { romaji english } coverImage { large } averageScore countryOfOrigin } } } } }"
}

/** MANHWA: the "like this one" row on a work's page. Hidden when AniList has nothing to suggest. */
@Composable
fun SimilarWorksRow(mangaId: Long, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val works by produceState<List<SimilarWorks.Work>?>(null, mangaId) { value = SimilarWorks.load(context, mangaId, title) }
    val list = works.orEmpty()
    if (list.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(ATMR.strings.similar_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.title }) { work ->
                Column(
                    Modifier
                        .width(112.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { navigator.push(GlobalSearchScreen(work.title)) },
                ) {
                    Box {
                        AsyncImage(
                            model = work.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                        )
                        if (work.score > 0) {
                            Row(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.55f))))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(MaterialSymbols.RoundedFilled.Star, contentDescription = null, tint = Color(0xFFFFD54A), modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("${work.score / 10.0}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        work.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}
