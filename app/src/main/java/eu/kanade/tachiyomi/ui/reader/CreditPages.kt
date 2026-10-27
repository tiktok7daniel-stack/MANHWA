package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import logcat.LogPriority
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA: tells the scanlation group's own pages (credits, recruitment, Discord and donation ads) from the story at a
 * chapter's two ends, so the reader can pass over them.
 *
 * A page counts as one when:
 * - the reader marked a page that looks the same (kept for every work, as groups put the same pages everywhere);
 * - the same picture sat at the ends of another chapter of this work (groups reuse these pages chapter after
 *   chapter, story pages never repeat); or
 * - its text, once read for the translation, is about the group (two or more words such as Discord, recruit, Ko-fi).
 */
object CreditPages {

    private const val PREFS = "manhwa_credit_pages"
    private const val MANUAL = "manual"

    /** How many pages at the start and at the end are looked at. */
    private const val HEAD = 2
    private const val TAIL = 3

    /** Hashes this close (bits that differ, of 64) are the same picture. */
    private const val SAME = 4

    /** Pictures learned per work; the oldest go first. */
    private const val KEEP = 120

    private val words = listOf(
        "discord", "patreon", "ko-fi", "kofi", "paypal", "recruit", "join us", "join our", "we are looking",
        "translator", "proofreader", "typesetter", "redrawer", "cleaner", "raw provider", "quality checker",
        "scanlation", "scans", "support us", "donate", "telegram", "read on our", "official site", "credits",
        "ديسكورد", "انضم", "مترجم", "مدقق", "تبييض", "ادعمنا", "تليجرام", "قناتنا", "باتريون",
    )

    fun atEdge(page: ReaderPage): Boolean {
        val count = page.chapter.pages?.size ?: return false
        // A short chapter has no room for group pages and story pages could be mistaken for them
        if (count < 6) return false
        return page.index < HEAD || page.index >= count - TAIL
    }

    /** Whether [page] (its image in [image]) is a group page; learns its picture for the chapters to come. */
    fun check(context: Context, page: ReaderPage, image: BufferedSource): Boolean {
        if (!atEdge(page)) return false
        if (textLooksLikeCredit(page.translation?.blocks?.joinToString(" ") { it.text }.orEmpty())) return true
        val hash = hash(image) ?: return false
        val mangaId = page.chapter.chapter.mangaId
        val chapterId = page.chapter.chapter.id
        return synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (readHashes(prefs.getString(MANUAL, null)).any { near(it.first, hash) }) return@synchronized true
            val key = "m$mangaId"
            val seen = readHashes(prefs.getString(key, null)).toMutableList()
            val repeated = seen.any { it.second != chapterId && near(it.first, hash) }
            if (seen.none { it.second == chapterId && it.first == hash }) {
                seen += hash to chapterId
                prefs.edit().putString(key, writeHashes(seen.takeLast(KEEP))).apply()
            }
            repeated
        }
    }

    /** Marks the picture of [image] as a group page everywhere. */
    fun markManual(context: Context, image: BufferedSource): Boolean {
        val hash = hash(image) ?: return false
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manual = readHashes(prefs.getString(MANUAL, null))
            if (manual.none { near(it.first, hash) }) {
                prefs.edit().putString(MANUAL, writeHashes((manual + (hash to 0L)).takeLast(KEEP))).apply()
            }
        }
        return true
    }

    /** Forgets every learned and marked page. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun textLooksLikeCredit(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return words.count { it in lower } >= 2
    }

    /**
     * A difference hash: the picture shrunk to 9x8 grey cells, one bit per cell brighter than its right neighbour.
     * Null for a picture with too little in it (blank or nearly so), which would match any other plain page.
     */
    fun hash(image: BufferedSource): Long? = try {
        val bytes = image.peek().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) sample *= 2
            val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            small?.let { bitmap ->
                val cells = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
                if (cells !== bitmap) bitmap.recycle()
                val grey = IntArray(72) { i ->
                    val c = cells.getPixel(i % 9, i / 9)
                    (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                }
                cells.recycle()
                var bits = 0L
                var spread = 0
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        val left = grey[y * 9 + x]
                        val right = grey[y * 9 + x + 1]
                        if (left > right) bits = bits or (1L shl (y * 8 + x))
                        spread += kotlin.math.abs(left - right)
                    }
                }
                bits.takeIf { spread >= 64 * 6 && java.lang.Long.bitCount(it) in 8..56 }
            }
        }
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "Could not hash a page" }
        null
    }

    private fun near(a: Long, b: Long) = java.lang.Long.bitCount(a xor b) <= SAME

    private fun readHashes(json: String?): List<Pair<Long, Long>> = runCatching {
        val array = JSONArray(json ?: return emptyList())
        List(array.length()) { i -> array.getJSONObject(i).let { it.getLong("h") to it.getLong("c") } }
    }.getOrDefault(emptyList())

    private fun writeHashes(list: List<Pair<Long, Long>>): String =
        JSONArray().apply { list.forEach { (h, c) -> put(JSONObject().put("h", h).put("c", c)) } }.toString()
}
