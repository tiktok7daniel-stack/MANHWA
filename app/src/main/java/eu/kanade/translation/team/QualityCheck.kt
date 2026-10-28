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

        /** Far shorter than the source: most of the line was dropped ("How is the shadow fighter ... ?" as "المقاتل؟"). */
        TOO_SHORT,
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
        // MANHWA zero English leak: an English word left in the middle of a non-Latin translation
        if (!targetLatin && leaksEnglish(tr, glossary)) return Problem.UNTRANSLATED
        if (tr.length > src.length * LONG_FACTOR + LONG_SLACK) return Problem.TOO_LONG
        // MANHWA: a long line that comes back as a word or two lost most of its meaning
        val trLetters = tr.count { it.isLetter() }
        if (letters >= SHORT_MIN_LETTERS && trLetters < letters * SHORT_FACTOR) return Problem.TOO_SHORT
        for ((term, translated) in glossary) {
            if (term.length < 2 || translated.isBlank()) continue
            val inSource = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(src)
            if (inSource && !tr.contains(translated, ignoreCase = true)) return Problem.NAME_NOT_KEPT
        }
        return null
    }

    private val latinWord = Regex("[A-Za-z][A-Za-z'-]{3,}")

    /**
     * A Latin word of 4 letters or more in a translation written in another script, that is neither an
     * abbreviation in capitals (HP, EXP, S-RANK) nor a term the glossary keeps in Latin letters.
     */
    private fun leaksEnglish(translation: String, glossary: Map<String, String>): Boolean {
        val kept = glossary.values.filter { v -> v.any { it in 'A'..'Z' || it in 'a'..'z' } }
        return latinWord.findAll(translation).any { match ->
            val word = match.value
            word.any { it.isLowerCase() } && kept.none { it.contains(word, ignoreCase = true) }
        }
    }

    private fun isEastAsian(c: Char): Boolean = c in '가'..'힯' || // Hangul syllables
        c in 'ᄀ'..'ᇿ' || // Hangul jamo
        c in '぀'..'ヿ' || // Hiragana, Katakana
        c in '一'..'鿿' // CJK ideographs

    private const val LONG_FACTOR = 3f
    private const val LONG_SLACK = 25

    // MANHWA: Arabic takes about two thirds of the English letters; under a fifth of them, the line was cut
    private const val SHORT_MIN_LETTERS = 30
    private const val SHORT_FACTOR = 0.22f
}
