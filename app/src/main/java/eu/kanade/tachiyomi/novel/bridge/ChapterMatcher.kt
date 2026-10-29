package eu.kanade.tachiyomi.novel.bridge

import kotlin.math.ln
import kotlin.math.max

/**
 * MANHWA novel bridge: finds the novel chapter a manhwa chapter tells, from what both say. A manhwa adapts its
 * novel freely (lines are shortened, scenes merged), but the same names, places, techniques and rare words come
 * back; ordinary words do not tell chapters apart. So every word is weighed by how rare it is across the novel
 * chapters looked at (inverse document frequency), and a chapter scores by the weight of the manhwa's words it
 * also has, as a share of all of them.
 *
 * Only a clear winner is an answer: its share must reach [MIN_SHARE], and it must beat the next best chapter
 * (that is not its own neighbour) by [MIN_LEAD]. Otherwise there is no answer, never a guess.
 *
 * Pure Kotlin: tested without a phone.
 */
object ChapterMatcher {

    data class Match(
        /** Index of the best chapter in the list given. */
        val index: Int,
        /** Its share of the manhwa's word weight, 0 to 1. */
        val share: Double,
        /** The best share away from it (not one of its two neighbours). */
        val runnerUp: Double,
    ) {
        val confident: Boolean get() = share >= MIN_SHARE && share >= runnerUp * MIN_LEAD
    }

    const val MIN_SHARE = 0.16
    const val MIN_LEAD = 1.35

    private val wordRegex = Regex("[\\p{L}][\\p{L}'’-]{2,}")

    /** Words common in any English text, which say nothing about where a chapter is. */
    private val stop = setOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "this", "have", "from", "one", "had",
        "not", "but", "what", "all", "were", "when", "your", "can", "said", "there", "use", "each", "which", "she",
        "how", "their", "will", "other", "about", "out", "many", "then", "them", "these", "some", "her", "would",
        "make", "like", "him", "into", "time", "has", "look", "two", "more", "write", "see", "way", "could",
        "people", "than", "first", "been", "call", "who", "its", "now", "find", "long", "down", "day", "did", "get",
        "come", "made", "may", "part", "over", "new", "after", "just", "where", "most", "know", "take", "only",
        "little", "work", "back", "good", "well", "very", "even", "here", "why", "ask", "went", "men", "read",
        "need", "too", "any", "same", "tell", "does", "set", "three", "want", "also", "must", "own", "still",
        "should", "never", "because", "while", "before", "again", "off", "through", "being", "those", "such",
        "don't", "dont", "it's", "i'm", "can't", "won't", "that's", "what's", "let's", "you're", "i'll", "he's",
        "there's", "didn't", "isn't", "wasn't", "yes", "okay", "huh", "hmm", "ugh", "really", "right", "think",
        "going", "got", "something", "nothing", "anything", "everything", "someone", "anyone", "thing", "things",
        "yeah", "please", "sorry", "thank", "thanks", "much", "our", "out", "yet", "both", "around", "might",
        "without", "every", "though", "until", "upon", "under", "against", "between", "into", "onto", "seems",
    )

    /** The words of [text] that may tell a chapter: lowercase, at least 3 letters, not common. */
    fun words(text: String): Set<String> =
        wordRegex.findAll(text).map { it.value.lowercase().replace('’', '\'').trim('\'', '-') }
            .filter { it.length >= 3 && it !in stop }
            .toSet()

    /**
     * The novel chapter (of [chapters], their texts in order) that [manhwa] tells, or null when there is too
     * little to go on (no chapters, or the manhwa text has under [MIN_WORDS] telling words).
     */
    fun best(manhwa: String, chapters: List<String>): Match? {
        if (chapters.isEmpty()) return null
        val mine = words(manhwa)
        if (mine.size < MIN_WORDS) return null
        val sets = chapters.map { words(it) }
        val n = sets.size.toDouble()
        // How rare each of the manhwa's words is among these chapters (+1: a word no chapter has still weighs)
        val weight = mine.associateWith { w ->
            val df = sets.count { w in it }
            ln((n + 1.0) / (df + 0.5))
        }
        val total = weight.values.sum().takeIf { it > 0.0 } ?: return null
        val shares = sets.map { set -> weight.entries.sumOf { (w, v) -> if (w in set) v else 0.0 } / total }
        val bestIndex = shares.indices.maxByOrNull { shares[it] } ?: return null
        // The runner-up away from the winner: a scene split over two chapters makes neighbours score alike
        val runnerUp = shares.indices.filter { kotlin.math.abs(it - bestIndex) > 1 }.maxOfOrNull { shares[it] } ?: 0.0
        return Match(bestIndex, shares[bestIndex], max(runnerUp, 1e-9))
    }

    const val MIN_WORDS = 8
}
