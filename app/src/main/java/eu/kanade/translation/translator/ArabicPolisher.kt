package eu.kanade.translation.translator

/**
 * MANHWA Arabic polisher: the last touch on every Arabic translation before it is drawn.
 * - Latin punctuation becomes Arabic where it sits in Arabic text: ? to ؟, , to ،, ; to ؛.
 * - Straight double quotes around Arabic words become «guillemets».
 * - Dots are tidied ("...." and ". . ." to "...", "…" kept), spaces before punctuation go, and a space comes
 *   after a comma or full stop that is glued to the next word.
 * - Counted nouns after numbers are put in their right form ([ArabicGrammarEngine]).
 * Numbers, Latin words and placeholders such as {n0} are never touched.
 */
object ArabicPolisher {

    private fun isArabic(c: Char) = c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

    private val manyDots = Regex("\\.{4,}")
    private val spacedDots = Regex("\\.\\s\\.\\s\\.")
    private val spaceBefore = Regex("\\s+([؟?!،,؛;:.…»])")
    private val glued = Regex("([،؛])(?=[\\p{L}])")
    private val quoted = Regex("\"([^\"\\n]{1,200})\"")
    private val spaces = Regex("[ \\t]{2,}")

    fun polish(text: String): String {
        if (text.isBlank() || text.none(::isArabic)) return text
        val chars = text.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            if (c != '?' && c != ',' && c != ';') continue
            // Only next to Arabic: "1,200" and an English word keep their marks
            val prev = (i - 1 downTo 0).firstOrNull { !chars[it].isWhitespace() && chars[it] !in "!?.\"'" }?.let { chars[it] }
            val prevArabic = prev != null && isArabic(prev)
            if (c == ',' && prev != null && prev.isDigit() && chars.getOrNull(i + 1)?.isDigit() == true) continue
            if (!prevArabic) continue
            chars[i] = when (c) {
                '?' -> '؟'
                ',' -> '،'
                else -> '؛'
            }
        }
        var out = String(chars)
        out = manyDots.replace(out, "...")
        out = spacedDots.replace(out, "...")
        out = quoted.replace(out) { m -> if (m.groupValues[1].any(::isArabic)) "«${m.groupValues[1].trim()}»" else m.value }
        out = spaceBefore.replace(out, "$1")
        out = glued.replace(out, "$1 ")
        out = spaces.replace(out, " ")
        out = ArabicGrammarEngine.fixCounts(out)
        return out.trim()
    }
}
