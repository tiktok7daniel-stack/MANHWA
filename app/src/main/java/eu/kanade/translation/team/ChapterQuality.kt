package eu.kanade.translation.team

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference

/**
 * MANHWA chapter quality badge: for every translated chapter, how many lines came from the translation
 * library, how many the AI wrote, how many a second key reviewed (and changed), and which lines stayed
 * doubtful, so the reader can open them and correct them by hand.
 */
class ChapterQuality(private val pref: Preference<String>) {

    @Serializable
    data class Report(
        val fromLibrary: Int = 0,
        val byAi: Int = 0,
        val reviewed: Int = 0,
        val changed: Int = 0,
        /** Source text of the lines still doubtful after the review. */
        val doubtful: List<String> = emptyList(),
        val at: Long = System.currentTimeMillis(),
    ) {
        val total: Int get() = fromLibrary + byAi
        val libraryPercent: Int get() = if (total == 0) 0 else fromLibrary * 100 / total

        /** 0–100: library lines count fully, reviewed ones well, doubtful ones pull it down. */
        val score: Int
            get() {
                if (total == 0) return 0
                val good = fromLibrary + byAi - doubtful.size.coerceAtMost(byAi)
                return (good * 100 / total).coerceIn(0, 100)
            }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private fun all(): Map<String, Report> = runCatching { json.decodeFromString<Map<String, Report>>(pref.get()) }.getOrDefault(emptyMap())

    operator fun get(chapterId: Long): Report? = all()["$chapterId"]

    fun save(chapterId: Long, report: Report) {
        synchronized(lock) {
            val next = (all() + ("$chapterId" to report)).entries.sortedBy { it.value.at }.takeLast(KEEP).associate { it.key to it.value }
            pref.set(json.encodeToString(next))
        }
    }

    /** A doubtful line the reader corrected is no longer doubtful. */
    fun resolve(chapterId: Long, source: String) {
        synchronized(lock) {
            val old = all()["$chapterId"] ?: return
            save(chapterId, old.copy(doubtful = old.doubtful - source))
        }
    }

    private companion object {
        const val KEEP = 400
    }
}
