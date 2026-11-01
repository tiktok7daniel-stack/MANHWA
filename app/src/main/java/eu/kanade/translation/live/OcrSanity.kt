package eu.kanade.translation.live

import android.content.Context
import java.io.DataInputStream

/**
 * MANHWA: tells a line the OCR misread on stylised lettering ("Nimgng flmlvs impti shord fokm", "Why miist ico")
 * from real English, so the bubble is read again from its image before it is translated, instead of the garbled
 * letters being passed on (and drawn) as they are.
 *
 * The English words are a Bloom filter in the app's assets (370 000 words in 430 KB, built by
 * tools/build_word_filter.py from a public-domain list): a word is known or, rarely (1 in 100), wrongly taken as
 * known; a real word is never taken as unknown.
 */
object OcrSanity {

    class WordFilter(private val bits: ByteArray, private val m: Long, private val k: Int) {
        fun has(word: String): Boolean {
            val h1 = fnv(word, 0L)
            val h2 = fnv(word, SEED2) or 1L
            for (i in 0 until k) {
                val idx = java.lang.Long.remainderUnsigned(h1 + i * h2, m)
                if ((bits[(idx ushr 3).toInt()].toInt() and (1 shl (idx and 7).toInt())) == 0) return false
            }
            return true
        }

        companion object {
            fun read(bytes: ByteArray): WordFilter? = runCatching {
                DataInputStream(bytes.inputStream()).use { input ->
                    val magic = ByteArray(4).also { input.readFully(it) }
                    if (String(magic) != "MWBF") return null
                    val m = input.readInt().toLong() and 0xFFFFFFFFL
                    val k = input.readInt()
                    val bits = ByteArray((m / 8).toInt()).also { input.readFully(it) }
                    WordFilter(bits, m, k)
                }
            }.getOrNull()

            private const val SEED2 = -0x61c8864680b583ebL

            /** FNV-1a 64 over the word's UTF-8 bytes, started from the offset basis xor [seed]. */
            fun fnv(word: String, seed: Long): Long {
                var h = -0x340d631b7bdddcdbL xor seed
                for (b in word.toByteArray(Charsets.UTF_8)) {
                    h = h xor (b.toLong() and 0xFF)
                    h *= 0x100000001b3L
                }
                return h
            }
        }
    }

    @Volatile private var filter: WordFilter? = null
    @Volatile private var tried = false

    private fun filter(context: Context): WordFilter? {
        if (!tried) {
            synchronized(this) {
                if (!tried) {
                    filter = runCatching { context.assets.open(ASSET).use { it.readBytes() } }.getOrNull()?.let(WordFilter::read)
                    tried = true
                }
            }
        }
        return filter
    }

    private val word = Regex("[A-Za-z][A-Za-z']{2,}")

    /**
     * Whether [text] (a Latin line) reads as garbled: at least two of its words, and at least half of them, are
     * not English. Names fail too, which only costs one more look at the bubble's image.
     */
    fun garbled(text: String, known: (String) -> Boolean): Boolean {
        val words = word.findAll(text).map { it.value.replace("'", "").lowercase() }.filter { it.length >= 3 }.toList()
        if (words.size < 2) return false
        val unknown = words.count { !known(it) }
        return unknown >= 2 && unknown * 2 >= words.size
    }

    /** [garbled] with the bundled English words, or [strayDigits]; read again from the image either way. */
    fun garbled(context: Context, text: String): Boolean {
        if (strayDigits(text)) return true
        val f = filter(context) ?: return false
        return garbled(text, f::has)
    }

    private val token = Regex("[^\\s]+")
    private val latinWord = Regex("^[A-Za-z][A-Za-z'’-]*[.,!?]*$")
    private val numberLike = Regex("^[#(]?\\d+([.,:/x-]\\d+)*(%|st|nd|rd|th|s|m|km|kg|cm|k|am|pm)?[).,!?]*$", RegexOption.IGNORE_CASE)

    /**
     * MANHWA: digits where letters were drawn ("THAT A BOY WHO APPEARED 1 68 A SERVANT", "T0 THE R00M"): in a line
     * of English words, a token that mixes digits and letters, or a bare number standing between two words with
     * no word around it that numbers count ("CHAPTER", "AGE", "YEARS"...). Decorative lettering makes the scanner
     * read I as 1, O as 0, B as 8 and G as 6.
     */
    fun strayDigits(text: String): Boolean {
        val tokens = token.findAll(text).map { it.value }.toList()
        val words = tokens.count { latinWord.matches(it) && it.count(Char::isLetter) >= 2 }
        if (words < 3) return false
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.none(Char::isDigit)) {
                i++
                continue
            }
            // Letters and digits in one word: "R00M", "T0", "8OY" (but "10TH", "3RD", "2ND" are real)
            if (t.any(Char::isLetter) && !numberLike.matches(t)) return true
            // A run of bare numbers ("1 68") read as one
            var end = i
            while (end + 1 < tokens.size && numberLike.matches(tokens[end + 1])) end++
            val before = tokens.getOrNull(i - 1)?.lowercase()?.trimEnd('.', ',', '!', '?', ':')
            val after = tokens.getOrNull(end + 1)?.lowercase()?.trimEnd('.', ',', '!', '?', ':')
            val beforeWord = before != null && latinWord.matches(before) && !tokens[i - 1].endsWith(".")
            val afterWord = after != null && latinWord.matches(after)
            // A real number names what it counts after it, or is labelled before it
            // ... or carries its own ending ("10TH", "5KM", "30%")
            val marked = (i..end).any { k -> tokens[k].any { c -> c.isLetter() || c == '%' } }
            val real = marked || (after != null && after in units) || (before != null && before in labels)
            val short = (i..end).all { tokens[it].count(Char::isDigit) <= 3 }
            if (beforeWord && afterWord && !real && short) return true
            i = end + 1
        }
        return false
    }

    /** What a number counts, said right after it. */
    private val units = setOf(
        "year", "years", "day", "days", "hour", "hours", "minute", "minutes", "second", "seconds", "month",
        "months", "week", "weeks", "time", "times", "people", "men", "women", "of", "points", "gold", "coins",
        "won", "dollars", "silver", "meters", "metres", "km", "kg", "percent", "floors", "levels", "steps",
        "soldiers", "kids", "children", "more", "or", "and", "to", "out", "in", "o'clock", "am", "pm",
    )

    /** What a number is the label of, said right before it. */
    private val labels = setOf(
        "chapter", "episode", "page", "floor", "level", "lv", "lvl", "rank", "age", "aged", "stage", "round",
        "class", "grade", "room", "no", "number", "vol", "volume", "season", "part", "day", "year", "unit", "squad",
        "team", "gate", "area", "zone", "block", "sector", "top",
    )

    /** Letters the scanner mixes up on decorative lettering (each way): what one may really be. */
    private val swaps = listOf(
        "rn" to "m", "m" to "rn", "cl" to "d", "d" to "cl", "vv" to "w", "w" to "vv", "li" to "h", "ii" to "u",
        "u" to "ii", "c" to "e", "e" to "c", "k" to "r", "r" to "k", "n" to "h", "h" to "n", "i" to "l", "l" to "i",
        "u" to "v", "v" to "u", "o" to "a", "a" to "o", "f" to "t", "t" to "f", "g" to "q", "q" to "g", "b" to "h",
    )

    /**
     * Mends words of a garbled line the scanner misread on decorative letters, when one swap of look-alike
     * letters gives exactly one English word ("FOKM" to "FORM"); words that give none, or several, stay as read.
     * Used when no model could read the bubble from its image.
     */
    fun repair(text: String, known: (String) -> Boolean): String = word.replace(text) { m ->
        val raw = m.value
        if (raw.contains('\'') || raw.length < 4) return@replace raw
        val lower = raw.lowercase()
        if (known(lower)) return@replace raw
        val found = HashSet<String>()
        for ((from, to) in swaps) {
            var at = lower.indexOf(from)
            while (at >= 0) {
                val candidate = lower.substring(0, at) + to + lower.substring(at + from.length)
                if (candidate.length >= 3 && known(candidate)) found += candidate
                at = lower.indexOf(from, at + 1)
            }
        }
        val only = found.singleOrNull() ?: return@replace raw
        when {
            raw.all { !it.isLetter() || it.isUpperCase() } -> only.uppercase()
            raw.first().isUpperCase() -> only.replaceFirstChar { it.uppercase() }
            else -> only
        }
    }

    /**
     * [repair] for a garbled line, with the English words when something already loaded them (the OCR check does,
     * before any translation); the line as it is otherwise. Needs no context, for the translators.
     */
    fun repairIfLoaded(text: String): String {
        val f = filter ?: return text
        return if (garbled(text, f::has)) repair(text, f::has) else text
    }

    /** [repair] with the bundled English words, only for a line that reads as garbled. */
    fun repair(context: Context, text: String): String {
        val f = filter(context) ?: return text
        return if (garbled(text, f::has)) repair(text, f::has) else text
    }

    private const val ASSET = "english_words.bloom"
}
