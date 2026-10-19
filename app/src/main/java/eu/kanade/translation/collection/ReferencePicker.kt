package eu.kanade.translation.collection

/**
 * MANHWA: reviewed lines of the collection that share words and phrases with the lines about to be translated,
 * given to the AI as references. A line does not have to come back word for word: "Shadow Monarch" corrected
 * once is followed wherever it shows up inside another sentence. Pure, tested without a phone.
 */
object ReferencePicker {

    private val word = Regex("[\\p{L}][\\p{L}'’]*")

    /** Words that say nothing on their own. */
    private val STOP = setOf(
        "the", "and", "you", "your", "are", "was", "were", "this", "that", "with", "for", "have", "has", "had", "not",
        "but", "all", "can", "will", "what", "who", "how", "why", "when", "where", "there", "here", "they", "them",
        "his", "her", "him", "she", "its", "our", "out", "from", "into", "just", "now", "then", "than", "too", "very",
        "don't", "it's", "i'm", "that's", "let's", "yes", "hey", "huh", "well", "okay", "even", "only", "been", "being",
        "would", "could", "should", "about", "like", "get", "got", "one", "know", "think", "see", "come", "going",
    )

    fun tokens(text: String): Set<String> =
        word.findAll(text.lowercase()).map { it.value.trim('\'', '’') }.filter { it.length >= MIN_WORD && it !in STOP }.toSet()

    /**
     * Up to [limit] of the [reviewed] (source, translation) pairs that best cover words of [lines]: a reviewed
     * entry counts when most of its own words are in one of the lines (a corrected term inside a new sentence).
     */
    fun pick(lines: List<String>, reviewed: List<Pair<String, String>>, limit: Int): List<Pair<String, String>> {
        if (lines.isEmpty() || reviewed.isEmpty() || limit <= 0) return emptyList()
        val rows = reviewed.map { it to tokens(it.first) }.filter { it.second.isNotEmpty() }
        val df = HashMap<String, Int>()
        rows.forEach { (_, t) -> t.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val index = HashMap<String, MutableList<Int>>()
        rows.forEachIndexed { i, (_, t) -> t.forEach { w -> if ((df[w] ?: 0) <= MAX_DF) index.getOrPut(w) { mutableListOf() } += i } }
        val best = HashMap<Int, Double>()
        for (line in lines) {
            val own = tokens(line)
            if (own.isEmpty()) continue
            val shared = HashMap<Int, Int>()
            own.forEach { w -> index[w]?.forEach { i -> shared[i] = (shared[i] ?: 0) + 1 } }
            shared.forEach { (i, n) ->
                val coverage = n.toDouble() / rows[i].second.size
                if (coverage < MIN_COVERAGE) return@forEach
                val score = coverage + n * WORD_WEIGHT
                if (score > (best[i] ?: 0.0)) best[i] = score
            }
        }
        return best.entries.sortedByDescending { it.value }.take(limit).map { rows[it.key].first }
    }

    /** The references as prompt lines, or "" when there are none. */
    fun prompt(references: List<Pair<String, String>>): String =
        if (references.isEmpty()) {
            ""
        } else {
            "Reviewed translations (use the same wording for these words and phrases wherever they appear):\n" +
                references.joinToString("\n") { (s, t) -> "- \"${s.take(MAX_CHARS)}\" = \"${t.take(MAX_CHARS)}\"" }
        }

    private const val MIN_WORD = 3
    private const val MAX_DF = 300
    private const val MIN_COVERAGE = 0.6
    private const val WORD_WEIGHT = 0.15
    private const val MAX_CHARS = 120
}
