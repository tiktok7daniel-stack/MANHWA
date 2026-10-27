package eu.kanade.translation.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.app.di.appGraph
import java.io.ByteArrayOutputStream
import java.util.Locale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * MANHWA "who is this?": the characters on a page, named. The page goes to a key whose model reads images, with the
 * work's cast (AniList and the glossary) and the story so far, so it names who is drawn and where, only from what is
 * known up to this chapter.
 */
object WhoIsThis {

    data class Seen(val name: String, val where: String, val about: String, val image: String?)

    suspend fun ask(context: Context, page: ByteArray, mangaId: Long, title: String): List<Seen> {
        val graph = context.appGraph
        val jpeg = withContext(Dispatchers.Default) { shrink(page) } ?: return emptyList()
        val cast = graph.characterStore.cached(title)?.characters.orEmpty()
        val notes = graph.glossaryStore.notes(mangaId)
        val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        val analysis = graph.teamStore.analysis.value
        val reply = appAsk(AiRouter.Lane.CHAT, prefer = { analysis[it.id]?.vision == true }) { ai ->
            ai.askJsonWithImage(
                system = """
                    You recognise manhwa characters on a page. Name each character drawn on it, using the cast list and notes
                    (their looks, hair, clothes, weapons, who speaks to whom). If you are not sure who someone is, say what is
                    known of them without guessing a name. Never tell anything that happens later in the story.
                    Reply in $language with ONLY: {"seen":[{"name":"...","where":"where on the page (top, middle, bottom, left, right)","about":"one sentence: who they are"}]}
                """.trimIndent(),
                prompt = buildString {
                    appendLine("Work: $title")
                    if (cast.isNotEmpty()) {
                        appendLine("Cast:")
                        cast.take(25).forEach { appendLine("- ${it.name} (${it.role.lowercase()}, ${it.gender.lowercase()}): ${it.about.take(160)}") }
                    }
                    if (notes.isNotEmpty()) {
                        appendLine("Notes:")
                        notes.entries.take(20).forEach { (n, v) -> appendLine("- $n: $v") }
                    }
                    graph.glossaryStore.storySoFar(mangaId).takeIf { it.isNotBlank() }?.let { appendLine("Story so far: $it") }
                },
                jpeg = jpeg,
            )
        }
        return (reply["seen"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.s("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val match = cast.firstOrNull { c -> c.name.equals(name, true) || c.alternative.any { it.equals(name, true) } || name.contains(c.name, true) }
            Seen(name, o.s("where"), o.s("about"), match?.image?.takeIf { it.isNotBlank() })
        }
    }

    /** The page small enough to send: at most 1600 pixels on its long side, as JPEG. */
    private fun shrink(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val long = maxOf(bitmap.width, bitmap.height)
        val scaled = if (long > 1600) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * 1600 / long, bitmap.height * 1600 / long, true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}

/** MANHWA: the names found on a page, or a wait while they are looked for. */
@androidx.compose.runtime.Composable
fun WhoIsThisDialog(seen: List<WhoIsThis.Seen>, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.at.ATMR.strings.who_title)) },
        text = {
            if (seen.isEmpty()) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(androidx.compose.ui.Modifier.size(22.dp))
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
                    androidx.compose.material3.Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.at.ATMR.strings.who_looking))
                }
            } else {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    seen.forEach { s ->
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (s.image != null) {
                                coil3.compose.AsyncImage(
                                    model = s.image,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = androidx.compose.ui.Modifier
                                        .size(48.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape),
                                )
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
                            }
                            androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.Text(s.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                if (s.where.isNotBlank()) {
                                    androidx.compose.material3.Text(
                                        s.where,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (s.about.isNotBlank()) {
                                    androidx.compose.material3.Text(s.about, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(tachiyomi.presentation.core.i18n.stringResource(tachiyomi.i18n.MR.strings.action_ok))
            }
        },
    )
}
