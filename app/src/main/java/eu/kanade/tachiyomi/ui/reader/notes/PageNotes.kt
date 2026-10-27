package eu.kanade.tachiyomi.ui.reader.notes

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** MANHWA: a note the reader left on a page. [page] is the page's index in its chapter. */
data class PageNote(
    val id: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val page: Int,
    val text: String,
)

/**
 * MANHWA: the reader's notes on pages, kept in one JSON file (written whole, through a temporary file, so a crash
 * mid-write never loses the old notes). Opening a note sets a one-time jump the reader takes to its page.
 */
object PageNotes {

    private const val FILE = "manhwa_page_notes.json"

    private val _notes = MutableStateFlow<List<PageNote>?>(null)

    @Volatile
    private var jump: Pair<Long, Int>? = null

    fun notes(context: Context): StateFlow<List<PageNote>?> {
        if (_notes.value == null) synchronized(this) { if (_notes.value == null) _notes.value = read(context) }
        return _notes.asStateFlow()
    }

    fun on(context: Context, chapterId: Long, page: Int): List<PageNote> =
        notes(context).value.orEmpty().filter { it.chapterId == chapterId && it.page == page }

    fun add(context: Context, note: PageNote) = change(context) { it + note }

    fun delete(context: Context, id: Long) = change(context) { list -> list.filterNot { it.id == id } }

    /** The reader opens [chapterId] at [page] next time it loads that chapter. */
    fun jumpTo(chapterId: Long, page: Int) {
        jump = chapterId to page
    }

    fun takeJump(chapterId: Long): Int? {
        val pending = jump ?: return null
        if (pending.first != chapterId) return null
        jump = null
        return pending.second
    }

    private fun change(context: Context, edit: (List<PageNote>) -> List<PageNote>) = synchronized(this) {
        val updated = edit(notes(context).value.orEmpty())
        _notes.value = updated
        write(context, updated)
    }

    private fun read(context: Context): List<PageNote> = runCatching {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        List(array.length()) { i ->
            array.getJSONObject(i).let {
                PageNote(
                    id = it.getLong("id"),
                    mangaId = it.getLong("mangaId"),
                    mangaTitle = it.optString("mangaTitle"),
                    chapterId = it.getLong("chapterId"),
                    chapterName = it.optString("chapterName"),
                    page = it.optInt("page"),
                    text = it.optString("text"),
                )
            }
        }
    }.getOrElse {
        logcat(LogPriority.ERROR, it) { "Could not read the page notes" }
        emptyList()
    }

    private fun write(context: Context, notes: List<PageNote>) {
        runCatching {
            val array = JSONArray()
            notes.forEach {
                array.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("mangaId", it.mangaId)
                        .put("mangaTitle", it.mangaTitle)
                        .put("chapterId", it.chapterId)
                        .put("chapterName", it.chapterName)
                        .put("page", it.page)
                        .put("text", it.text),
                )
            }
            val file = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        }.onFailure { logcat(LogPriority.ERROR, it) { "Could not save the page notes" } }
    }
}
