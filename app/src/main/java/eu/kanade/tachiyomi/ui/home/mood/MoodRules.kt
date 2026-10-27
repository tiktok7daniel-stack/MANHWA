package eu.kanade.tachiyomi.ui.home.mood

import eu.kanade.tachiyomi.source.model.SManga

/**
 * MANHWA: what each mood asks of a work, from its genres (in English and Arabic, as sources write them), its length
 * and its status. Pure, so it is tested on its own.
 */
object MoodRules {

    enum class Mood { LIGHT_ACTION, SAD, ONE_DAY, ROMANCE, MYSTERY, POWER, FUNNY, DARK }

    data class Work(val genres: List<String>, val chapters: Long, val status: Long, val unread: Long)

    private val words = mapOf(
        Mood.LIGHT_ACTION to listOf("action", "adventure", "أكشن", "اكشن", "مغامرة", "مغامرات"),
        Mood.SAD to listOf("tragedy", "drama", "دراما", "مأساة", "مأساوي"),
        Mood.ROMANCE to listOf("romance", "رومانسي", "رومانسية", "شوجو", "shoujo", "josei"),
        Mood.MYSTERY to listOf("mystery", "thriller", "psychological", "غموض", "إثارة", "اثارة", "نفسي", "تشويق"),
        Mood.POWER to listOf("fantasy", "isekai", "system", "regression", "reincarnation", "murim", "martial arts", "cultivation",
            "خيال", "فانتازيا", "ايسكاي", "إيسيكاي", "نظام", "عودة", "تناسخ", "فنون قتالية"),
        Mood.FUNNY to listOf("comedy", "slice of life", "كوميدي", "كوميديا", "شريحة من الحياة"),
        Mood.DARK to listOf("horror", "gore", "dark", "رعب", "دموي", "مظلم"),
    )

    private val heavy = listOf("tragedy", "horror", "gore", "مأساة", "رعب", "دموي")

    fun matches(mood: Mood, work: Work): Boolean {
        val genres = work.genres.map { it.lowercase().trim() }
        fun has(list: List<String>) = list.filter { it.isNotEmpty() }.any { w -> genres.any { it.contains(w) } }
        return when (mood) {
            Mood.ONE_DAY -> work.chapters in 1..70 &&
                (work.status == SManga.COMPLETED.toLong() || work.status == SManga.PUBLISHING_FINISHED.toLong() || work.chapters <= 40)
            Mood.LIGHT_ACTION -> has(words.getValue(Mood.LIGHT_ACTION)) && !has(heavy)
            else -> has(words.getValue(mood))
        }
    }

    /** Works for [mood], the ones you have not finished first, then the shortest. */
    fun <T> pick(mood: Mood, items: List<T>, work: (T) -> Work): List<T> =
        items.filter { matches(mood, work(it)) }.sortedWith(compareBy({ work(it).unread == 0L }, { work(it).chapters }))
}
