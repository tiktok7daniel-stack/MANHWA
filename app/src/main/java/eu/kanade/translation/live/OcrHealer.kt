package eu.kanade.translation.live

/**
 * MANHWA OCR healer: mends the slips the text recogniser makes on comic lettering, before the collection is
 * searched and before the AI reads the line. A cleaner line is found in the collection more often, and the AI
 * does not have to guess.
 *
 * Only Latin text is touched, and only in ways that cannot change a real word:
 * - a word split over two lines with a hyphen is joined ("some- thing" to "something");
 * - a 0 between letters is an O, a 1 or | between letters is an I or an l ("C0ME" to "COME", "he1p" to "help");
 * - a lone | is the word I; "l" alone between spaces in an all-caps line is I;
 * - runs of punctuation are tidied ("!!!!!" to "!!!", ". . ." to "...", "? !" to "?!") and spaces before them go.
 */
object OcrHealer {

    private val hyphenSplit = Regex("(\\p{L})-\\s+(\\p{Ll})")
    private val zeroInWord = Regex("(?<=\\p{L})0(?=\\p{L})")
    private val oneInWord = Regex("(?<=\\p{L})[1|](?=\\p{L})")
    private val lonePipe = Regex("(?<![\\p{L}\\p{N}])\\|(?![\\p{L}\\p{N}])")
    private val spacedDots = Regex("\\.\\s\\.\\s\\.")
    private val manyBangs = Regex("!{4,}")
    private val manyQuestions = Regex("\\?{4,}")
    private val spacedMix = Regex("([?!])\\s+([?!])")
    private val spaceBefore = Regex("\\s+([?!.,;:])")
    private val spaces = Regex("[ \\t]{2,}")

    fun heal(text: String): String {
        if (text.isBlank()) return text
        val letters = text.count { it.isLetter() }
        if (letters == 0) return text
        // Only Latin lines: Korean, Japanese, Chinese and Arabic have none of these slips
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (latin < letters * 0.8f) return text
        val upper = text.count { it in 'A'..'Z' } > latin * 0.7f
        var out = text
        out = hyphenSplit.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}" }
        out = zeroInWord.replace(out, if (upper) "O" else "o")
        out = oneInWord.replace(out) { match ->
            val before = out.getOrNull(match.range.first - 1)
            if (upper || before?.isUpperCase() == true) "I" else "l"
        }
        out = lonePipe.replace(out, "I")
        if (upper) out = Regex("(?<=\\s|^)l(?=\\s|$)").replace(out, "I")
        out = spacedDots.replace(out, "...")
        out = manyBangs.replace(out, "!!!")
        out = manyQuestions.replace(out, "???")
        out = spacedMix.replace(out, "$1$2")
        out = spaceBefore.replace(out, "$1")
        out = spaces.replace(out, " ")
        return out.trim()
    }
}
