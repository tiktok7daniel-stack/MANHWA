package eu.kanade.translation.team

/**
 * MANHWA: checks every translated bubble on the phone, without the AI, for the mistakes that can be seen
 * from the text alone. Only the bubbles it flags are sent to be translated again.
 */
object QualityCheck {

    enum class Problem {
        /** No translation at all. */
        EMPTY,

        /** Still in the source script (Korean, Chinese, Japanese) or left as the source text. */
        UNTRANSLATED,

        /** Far longer than the source: it will not fit its bubble. */
        TOO_LONG,

        /** A glossary name in the source is missing from the translation. */
        NAME_NOT_KEPT,
    }

    /**
     * What is wrong with [translation] of [source], or null when it looks fine. [glossary] is the series
     * glossary (source term to translation); [targetLatin] tells whether the target language is written in
     * Latin letters (then English words left in it are not a mistake).
     */
    fun check(source: String, translation: String, glossary: Map<String, String>, targetLatin: Boolean): Problem? {
        val src = source.trim()
        val tr = translation.trim()
        if (src.isEmpty()) return null
        if (tr.isEmpty()) return Problem.EMPTY
        if (tr.any(::isEastAsian)) return Problem.UNTRANSLATED
        val letters = src.count { it.isLetter() }
        if (!targetLatin && letters >= 3 && tr.equals(src, ignoreCase = true)) return Problem.UNTRANSLATED
        if (!targetLatin && letters >= 8) {
            val latin = tr.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (latin > tr.count { it.isLetter() } * 0.6f) return Problem.UNTRANSLATED
        }
        if (tr.length > src.length * LONG_FACTOR + LONG_SLACK) return Problem.TOO_LONG
        for ((term, translated) in glossary) {
            if (term.length < 2 || translated.isBlank()) continue
            val inSource = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(src)
            if (inSource && !tr.contains(translated, ignoreCase = true)) return Problem.NAME_NOT_KEPT
        }
        return null
    }

    private fun isEastAsian(c: Char): Boolean = c in '가'..'힯' || // Hangul syllables
        c in 'ᄀ'..'ᇿ' || // Hangul jamo
        c in '぀'..'ヿ' || // Hiragana, Katakana
        c in '一'..'鿿' // CJK ideographs

    private const val LONG_FACTOR = 3f
    private const val LONG_SLACK = 25
}
