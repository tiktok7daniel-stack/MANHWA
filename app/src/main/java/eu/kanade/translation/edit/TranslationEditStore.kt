package eu.kanade.translation.edit

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The reader's own adjustments of translated bubbles (move, box size, text size), saved per chapter so
 * they come back every time the chapter is opened, for live and for downloaded translations alike.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationEditStore(private val context: Context) {

    @Serializable
    data class BlockEdit(val i: Int, val t: Int, val dx: Float, val dy: Float, val s: Float, val f: Float)

    /** Whether the bubble edit mode is on in the reader. */
    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    fun setEditing(on: Boolean) {
        _editing.value = on
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val chapters = ConcurrentHashMap<String, MutableMap<String, List<BlockEdit>>>()

    private fun dir() = File(context.filesDir, "translation_edits").apply { mkdirs() }

    private fun file(chapter: String) = File(dir(), chapter.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun chapter(chapter: String): MutableMap<String, List<BlockEdit>> = chapters.getOrPut(chapter) {
        runCatching {
            file(chapter).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Map<String, List<BlockEdit>>>(it) }
        }.getOrNull().orEmpty().toMutableMap()
    }

    /** Gives [page] its saved place ([chapter] and [page key][pageKey]) and applies the saved adjustments. */
    fun restore(page: PageTranslation, chapter: String, pageKey: String) {
        page.editKey = "$chapter|$pageKey"
        val edits = chapter(chapter)[pageKey] ?: return
        edits.forEach { edit ->
            val block = page.blocks.getOrNull(edit.i) ?: return@forEach
            if (block.text.hashCode() != edit.t) return@forEach
            block.userDx = edit.dx
            block.userDy = edit.dy
            block.userScale = edit.s
            block.userFont = edit.f
        }
    }

    /** Saves the adjustments of [page] after the reader changed a bubble. */
    fun save(page: PageTranslation) {
        val key = page.editKey.takeIf { it.isNotEmpty() } ?: return
        val chapter = key.substringBefore('|')
        val pageKey = key.substringAfter('|')
        val edits = page.blocks.mapIndexedNotNull { i, b ->
            BlockEdit(i, b.text.hashCode(), b.userDx, b.userDy, b.userScale, b.userFont).takeIf { b.edited }
        }
        val map = chapter(chapter)
        if (edits.isEmpty()) map.remove(pageKey) else map[pageKey] = edits
        runCatching { file(chapter).writeText(json.encodeToString(map.toMap())) }
            .onFailure { logcat(LogPriority.ERROR, it) }
    }
}
