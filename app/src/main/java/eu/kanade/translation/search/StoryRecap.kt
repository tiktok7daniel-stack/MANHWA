package eu.kanade.translation.search

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.glass
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.Group
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.ConcurrentHashMap

/**
 * MANHWA "Remind me of the story": a recap of the chapters the reader has already read, where they stopped and
 * who matters, written by the team's AI from what the app knows (the summaries kept while translating, the
 * glossary's characters, the description) and told never to go past the last chapter read.
 */
object StoryRecap {

    class Recap(val text: String, val stoppedAt: String, val characters: List<Pair<String, String>>)

    sealed interface State {
        data object Loading : State
        data object NothingRead : State
        data class Failed(val message: String) : State
        data class Ready(val recap: Recap) : State
    }

    /** One recap per manhwa and last chapter read: asked again only when the reader read further. */
    private val cache = ConcurrentHashMap<String, Recap>()

    suspend fun load(context: Context, mangaId: Long, title: String, description: String?): State = withContext(Dispatchers.IO) {
        val graph = context.appGraph
        val read = graph.getChaptersByMangaId.await(mangaId).filter { it.read }
        if (read.isEmpty()) return@withContext State.NothingRead
        val last = read.maxBy { it.chapterNumber }
        val key = "$mangaId:${last.chapterNumber}"
        cache[key]?.let { return@withContext State.Ready(it) }
        val readNames = read.map { it.name }.toSet()
        val summaries = graph.glossaryStore.story(mangaId).filter { summary ->
            if (summary.number >= 0) summary.number <= last.chapterNumber else summary.chapter in readNames
        }
        val characters = graph.glossaryStore.get(mangaId)
            .filter { it.kind == "character" || it.note.isNotBlank() }
            .take(MAX_CHARACTERS)
        val language = TextTranslatorLanguage.fromPref(graph.translationPreferences.translateToLanguage()).label
        val prompt = buildString {
            appendLine("Series: $title")
            if (!description.isNullOrBlank()) appendLine("Description: ${description.take(1200)}")
            appendLine("The reader has read up to: ${last.name} (chapter ${last.chapterNumber}).")
            if (summaries.isNotEmpty()) {
                appendLine("What happened in chapters they read:")
                summaries.forEach { appendLine("- ${it.chapter}: ${it.summary}") }
            }
            if (characters.isNotEmpty()) {
                appendLine("Characters met so far:")
                characters.forEach { appendLine("- ${it.source} (${it.translation}) ${it.note}".trim()) }
            }
        }
        try {
            val reply = appAsk(AiRouter.Lane.CHAT) { ai ->
                ai.askJson(
                    "You recap comics for a returning reader, in $language. Use ONLY what happened up to the chapter " +
                        "they reached: never reveal, hint at or foreshadow anything after it, even if you know the series. " +
                        "Make it gripping, like a \"previously on\" scene, 6 to 10 sentences. Reply with ONLY: " +
                        "{\"recap\": \"...\", \"stopped\": \"one sentence: where the story stands now\", " +
                        "\"characters\": [{\"name\": \"...\", \"role\": \"one short line\"}]}",
                    prompt,
                )
            }
            val recap = Recap(
                text = reply.str("recap").orEmpty(),
                stoppedAt = reply.str("stopped").orEmpty(),
                characters = (reply["characters"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    val name = o.str("name") ?: return@mapNotNull null
                    name to o.str("role").orEmpty()
                }.take(MAX_CHARACTERS),
            )
            if (recap.text.isBlank()) return@withContext State.Failed("Empty answer")
            cache[key] = recap
            State.Ready(recap)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private const val MAX_CHARACTERS = 12
}

/** The "Remind me of the story" button of a manhwa, with its sheet. */
@Composable
fun StoryRecapButton(mangaId: Long, title: String, description: String?, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = modifier
            .glass(RoundedCornerShape(50), alpha = 0.4f)
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(ATMR.strings.recap_button), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
    }
    if (open) RecapSheet(mangaId, title, description) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecapSheet(mangaId: Long, title: String, description: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<StoryRecap.State>(StoryRecap.State.Loading) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        state = StoryRecap.State.Loading
        state = StoryRecap.load(context, mangaId, title, description)
    }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0B0B10)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(ATMR.strings.recap_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Color.White)
            }
            when (val current = state) {
                StoryRecap.State.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(ATMR.strings.recap_loading), color = Color.White.copy(alpha = 0.7f))
                }
                StoryRecap.State.NothingRead -> Text(stringResource(ATMR.strings.recap_none_read), color = Color.White.copy(alpha = 0.7f))
                is StoryRecap.State.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(ATMR.strings.recap_failed, current.message.take(160)), color = colors.error)
                    Text(
                        stringResource(ATMR.strings.recap_retry),
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { attempt++ }.padding(vertical = 6.dp),
                    )
                }
                is StoryRecap.State.Ready -> {
                    Text(current.recap.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    if (current.recap.stoppedAt.isNotBlank()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(16.dp), accent = true, alpha = 0.45f)
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(MaterialSymbols.Rounded.Bookmark, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(stringResource(ATMR.strings.recap_stopped), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                                    Text(current.recap.stoppedAt, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                }
                            }
                        }
                    }
                    if (current.recap.characters.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(MaterialSymbols.Rounded.Group, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(ATMR.strings.recap_characters), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        }
                        current.recap.characters.forEach { (name, role) ->
                            Row {
                                Text(name, fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                if (role.isNotBlank()) {
                                    Text(" · $role", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
