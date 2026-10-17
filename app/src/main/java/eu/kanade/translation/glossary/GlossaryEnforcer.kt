package eu.kanade.translation.glossary

/**
 * MANHWA: the work's names and terms are written the way its glossary says, checked on the phone after every
 * translation (no new call):
 * - a term of the source line whose glossary Arabic is missing from the translation, but a close spelling of it is
 *   there (سونج جين وو for سونغ جين وو, ال or و in front kept), gets the glossary spelling;
 * - nothing else is touched: a term the translation left out, or wrote very differently, stays for the quality
 *   check, and short terms (fewer than [MIN_LETTERS] letters) are never guessed at.
 */
object GlossaryEnforcer {

    /** The series' own glossary first, then the codex terms it does not have (the prompt keeps the first ones). */
    fun seriesFirst(series: Map<String, String>, codex: Map<String, String>): Map<String, String> =
        series + codex.filterKeys { it !in series }

    fun fix(source: String, translation: String, glossary: Map<String, String>): String {
        if (translation.isBlank() || glossary.isEmpty()) return translation
        var out = translation
        val others = glossary.values.map(::normalize).toSet()
        glossary.entries
            .filter { (term, arabic) -> term.any(Char::isLetter) && isArabic(arabic) && normalize(arabic).count { it != ' ' } >= MIN_LETTERS }
            .sortedByDescending { it.value.length }
            .forEach { (term, arabic) ->
                if (!mentions(source, term)) return@forEach
                if (normalize(out).contains(normalize(arabic))) return@forEach
                out = replaceClose(out, arabic, others)
            }
        return out
    }

    private fun mentions(source: String, term: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(source)

    /** The run of words in [text] closest to [arabic] (same number of words), replaced when it is close enough. */
    private fun replaceClose(text: String, arabic: String, others: Set<String>): String {
        val wanted = normalize(arabic)
        val size = wanted.split(' ').size
        val words = Regex("[\\p{IsArabic}\\u064B-\\u0652]+").findAll(text).toList()
        if (words.size < size) return text
        var best: Triple<IntRange, String, Double>? = null
        for (start in 0..words.size - size) {
            val run = words.subList(start, start + size)
            val first = run.first().value
            // A one-letter joined word (و ف ب ل ك) or the article in front of the name is kept as it is
            val prefix = PREFIXES.firstOrNull { p -> first.length > p.length + 2 && first.startsWith(p) && !wanted.startsWith(normalize(p)) }.orEmpty()
            val candidate = normalize((listOf(first.removePrefix(prefix)) + run.drop(1).map { it.value }).joinToString(" "))
            if (candidate == wanted || candidate in others) continue
            val score = similarity(candidate, wanted)
            // A short name has to be closer: one letter off in four is another word
            val needed = if (wanted.length < 6) CLOSE_SHORT else CLOSE
            if (score >= needed && (best == null || score > best.third)) {
                best = Triple(run.first().range.first + prefix.length..run.last().range.last, candidate, score)
            }
        }
        val hit = best ?: return text
        return text.replaceRange(hit.first, arabic)
    }

    /** Arabic without vowel marks and tatweel, with the letters writers swap made one. */
    fun normalize(text: String): String = text
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .map { c ->
            when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                else -> c
            }
        }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isArabic(text: String) = text.any { it in '؀'..'ۿ' }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
        }
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    private val PREFIXES = listOf("وال", "بال", "فال", "لل", "كال", "ال", "و", "ف", "ب", "ل", "ك")

    /** How close a spelling has to be to be the same name (one or two letters off in an ordinary name). */
    private const val CLOSE = 0.75
    private const val CLOSE_SHORT = 0.8

    const val MIN_LETTERS = 4
}
