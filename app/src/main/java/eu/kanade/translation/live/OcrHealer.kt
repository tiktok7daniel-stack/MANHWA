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
 * - in an all-caps line, digits read where letters are drawn at the start of a word are letters ("50ME" to
 *   "SOME": 5 to S, 0 to O, 8 to B, 1 to I), never in ordinals or units (5TH, 10PM, 50KG); a 6 can be a G or an
 *   S, so it is left for the AI;
 * - runs of punctuation are tidied ("!!!!!" to "!!!", ". . ." to "...", "? !" to "?!") and spaces before them go.
 */
object OcrHealer {

    private val hyphenSplit = Regex("(\\p{L})-\\s+(\\p{Ll})")
    private val zeroInWord = Regex("(?<=\\p{L})0(?=\\p{L})")
    private val oneInWord = Regex("(?<=\\p{L})[1|](?=\\p{L})")
    private val lonePipe = Regex("(?<![\\p{L}\\p{N}])\\|(?![\\p{L}\\p{N}])")

    // MANHWA: an "i" read as a slash between a capital and the rest of the word ("S / tuation" to "Situation")
    private val slashForI = Regex("(?<![\\p{L}])([A-HJ-VX-Z])\\s?[/|]\\s?([a-z]{3,})")

    // A 0 at the end or start of a lowercase word is an o ("yo0", "0kay"); a 5 before lowercase is an S ("5orry")
    private val zeroEnd = Regex("(?<=\\p{Ll}{2})0(?!\\p{N})")
    private val unit = "(?!(?:th|st|nd|rd|am|pm|kg|km|cm|mm|ft|min|mins|sec|secs|hr|hrs|x)(?!\\p{L}))"
    private val zeroStart = Regex("(?<![\\p{L}\\p{N}])0$unit(?=\\p{Ll}{2})")
    private val fiveStart = Regex("(?<![\\p{L}\\p{N}])5$unit(?=\\p{Ll}{2,})")
    private val spacedDots = Regex("\\.\\s\\.\\s\\.")
    private val manyBangs = Regex("!{4,}")
    private val manyQuestions = Regex("\\?{4,}")
    private val spacedMix = Regex("([?!])\\s+([?!])")
    private val spaceBefore = Regex("\\s+([?!.,;:])")
    private val spaces = Regex("[ \\t]{2,}")
    private val stutterOne = Regex("(?<![\\p{L}\\p{N}])[1I|]-[1I|](?![\\p{L}\\p{N}])")

    /** A word that starts with letter-like digits and goes on in at least two capitals, not an ordinal or a unit. */
    private val leadingDigits = Regex(
        "(?<![\\p{L}\\p{N}])[0158]{1,2}(?=[A-Z]{2,})(?!(?:ST|ND|RD|TH|AM|PM|KG|KM|CM|MM|MB|GB|HP|FPS|MPH)(?![A-Z]))(?!MIN|SEC|HOUR|HRS|DAY|WEEK|MONTH|YEAR|YRS|METER|TIME|LEVEL|PERCENT|KILO)",
    )

    private fun lettersFor(digits: String): String = digits.map {
        when (it) {
            '0' -> 'O'
            '1' -> 'I'
            '5' -> 'S'
            '8' -> 'B'
            else -> it
        }
    }.joinToString("")

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
        out = slashForI.replace(out, "$1i$2")
        out = zeroEnd.replace(out, "o")
        out = zeroStart.replace(out, "o")
        out = fiveStart.replace(out, "S")
        out = lonePipe.replace(out, "I")
        if (upper) out = Regex("(?<=\\s|^)l(?=\\s|$)").replace(out, "I")
        if (upper) out = leadingDigits.replace(out) { m -> lettersFor(m.value) }
        // MANHWA: a stuttered "I-I" read as digits ("1-1 DON'T KNOW", "I-1", "1-I") in a capitals line
        if (upper) out = stutterOne.replace(out, "I-I")
        out = spacedDots.replace(out, "...")
        out = manyBangs.replace(out, "!!!")
        out = manyQuestions.replace(out, "???")
        out = spacedMix.replace(out, "$1$2")
        out = spaceBefore.replace(out, "$1")
        out = spaces.replace(out, " ")
        return out.trim()
    }
}
