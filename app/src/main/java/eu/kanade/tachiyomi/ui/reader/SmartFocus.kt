package eu.kanade.tachiyomi.ui.reader

import android.content.Context

/**
 * MANHWA smart focus: where exactly the reader stopped in a long webtoon page (the page at the middle of the
 * screen and how far into it), per chapter, so reopening lands on the same line, not the top of the page.
 */
class SmartFocus(context: Context) {

    data class Point(val page: Int, val fraction: Float, val lastRead: Long)

    private val prefs = context.getSharedPreferences("manhwa_smart_focus", Context.MODE_PRIVATE)

    fun save(chapterId: Long, point: Point) {
        val edit = prefs.edit().putString(chapterId.toString(), "${point.page};${point.fraction};${point.lastRead};${System.currentTimeMillis()}")
        // Keep the store small: the oldest go once there are too many
        val all = prefs.all
        if (all.size > MAX_CHAPTERS) {
            all.entries.sortedBy { (it.value as? String)?.split(';')?.getOrNull(3)?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_CHAPTERS)
                .forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    fun get(chapterId: Long): Point? {
        val parts = prefs.getString(chapterId.toString(), null)?.split(';') ?: return null
        val page = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val fraction = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        val lastRead = parts.getOrNull(2)?.toLongOrNull() ?: return null
        return Point(page, fraction.coerceIn(0f, 1f), lastRead)
    }

    private companion object {
        const val MAX_CHAPTERS = 300
    }
}
