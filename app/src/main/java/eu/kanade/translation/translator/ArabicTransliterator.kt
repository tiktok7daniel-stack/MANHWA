package eu.kanade.translation.translator

/**
 * MANHWA phonetic transliterator: writes a Latin name in Arabic letters the way it sounds, for the names the
 * AI left in English ("Jinwoo", "Grid", "Arthur", "Kang"). Korean romanisation and English names both read
 * well: digraphs first (sh, ch, th, kh, gh, ng, oo, ee, eo, eu...), then letters, with long vowels written and
 * the short ones of a closed syllable left out, like Arabic does ("Arthur" to "آرثر", "Kang" to "كانغ").
 * Hyphenated parts stay apart with a hyphen ("Jin-Woo" to "جين-وو").
 */
object ArabicTransliterator {

    /** Multi-letter sounds, longest first. */
    private val digraphs = listOf(
        "tch" to "تش", "sch" to "ش",
        "sh" to "ش", "ch" to "تش", "th" to "ث", "kh" to "خ", "gh" to "غ", "ph" to "ف", "ck" to "ك", "qu" to "كو", "wh" to "و",
        "ng" to "نغ", "dj" to "ج", "zh" to "ج", "ts" to "تس",
        "oo" to "و", "ou" to "و", "ee" to "ي", "ea" to "ي", "ie" to "ي", "ei" to "ي", "ai" to "اي", "ay" to "اي",
        "ae" to "اي", "eo" to "و", "eu" to "و", "ui" to "وي", "oi" to "وي", "au" to "او", "aw" to "او", "ow" to "او", "ey" to "ي",
    )

    private val letters = mapOf(
        'b' to "ب", 'c' to "ك", 'd' to "د", 'f' to "ف", 'g' to "غ", 'h' to "ه", 'j' to "ج", 'k' to "ك", 'l' to "ل",
        'm' to "م", 'n' to "ن", 'p' to "ب", 'q' to "ك", 'r' to "ر", 's' to "س", 't' to "ت", 'v' to "ف", 'w' to "و",
        'x' to "كس", 'z' to "ز",
    )

    private const val VOWELS = "aeiouy"

    fun transliterate(name: String): String = name.split('-').joinToString("-") { part -> word(part) }

    private fun word(raw: String): String {
        val w = raw.lowercase().filter { it.isLetter() }
        if (w.isEmpty()) return raw
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            val first = i == 0
            val last = i == w.length - 1
            // A vowel opening the word sits on an alif
            if (first && w[0] in VOWELS) {
                val pair = w.take(2)
                when {
                    pair == "ar" || pair == "al" -> out.append("آ")
                    w[0] == 'a' -> out.append("أ")
                    w[0] == 'e' || w[0] == 'i' -> out.append("إي")
                    w[0] == 'o' || w[0] == 'u' -> out.append("أو")
                    else -> out.append("ي")
                }
                i += if (pair == "ar" || pair == "al") 1 else 1
                continue
            }
            val digraph = digraphs.firstOrNull { (from, _) -> w.startsWith(from, i) }
            if (digraph != null) {
                out.append(digraph.second)
                i += digraph.first.length
                continue
            }
            val c = w[i]
            // A doubled consonant is written once
            if (i > 0 && w[i - 1] == c && c !in VOWELS) {
                i++
                continue
            }
            val next = w.getOrNull(i + 1)
            val text = when (c) {
                'c' -> if (next == 'e' || next == 'i' || next == 'y') "س" else "ك"
                'g' -> if (next == 'e' || next == 'i' || next == 'y') "ج" else "غ"
                'a' -> if (last) "ا" else if (closedSyllable(w, i)) "" else "ا"
                'e' -> if (last) "" else if (closedSyllable(w, i)) "" else "ي"
                'i' -> "ي"
                'o' -> "و"
                'u' -> if (closedSyllable(w, i) && i > 1) "" else "و"
                'y' -> if (last) "ي" else "ي"
                else -> letters[c] ?: ""
            }
            out.append(text)
            i++
        }
        return out.toString().ifEmpty { raw }
    }

    /** A short vowel between two consonants late in a longer word, as in "Arthur" or "Jordan": not written. */
    private fun closedSyllable(w: String, i: Int): Boolean {
        if (w.length < 5 || i < 2) return false
        val before = w[i - 1]
        val after = w.getOrNull(i + 1) ?: return false
        val afterAfter = w.getOrNull(i + 2)
        return before !in VOWELS && after !in VOWELS && (afterAfter == null || afterAfter !in VOWELS)
    }
}
