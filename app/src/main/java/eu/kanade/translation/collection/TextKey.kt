package eu.kanade.translation.collection

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * MANHWA: how a line is recognised when it comes back, in any manhwa.
 *
 * Every line is cleaned first (case, spaces, quotes, dots), so "What ?!" and "what?!" are the same line.
 * Numbers become {n0}, {n1}… ("[Level Up! Lv.23]" is the line "[level up! lv.{n0}]"), and names from the
 * series glossary become {name0}… ("Jin-woo, run!" is "{name0}, run!"), so one saved line fits every number and
 * every character. The translation is saved with the same placeholders, and filled back in when it is reused.
 */
object TextKey {

    /** A line ready to look up: its key, and the numbers and names that were taken out of it, in order. */
    class Keyed(
        /** Cleaned line, numbers and names kept as they are. */
        val exact: String,
        /** Cleaned line with numbers as {nX} and names as {nameX}; equal to [exact] when it has neither. */
        val template: String,
        val numbers: List<String>,
        /** Glossary source terms found in the line, in the order of their placeholders. */
        val names: List<String>,
    ) {
        val isTemplate: Boolean get() = template != exact
    }

    private val spaces = Regex("\\s+")
    private val beforePunctuation = Regex("\\s+([?!.,:;~])")
    private val numberPattern = Regex("\\d+(?:[.,:/]\\d+)*")

    fun normalize(text: String): String = text
        .replace("…", "...")
        .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace('－', '-').replace('—', '-').replace('–', '-')
        .lowercase(Locale.ROOT)
        .replace(spaces, " ")
        .replace(beforePunctuation, "$1")
        .trim()

    /** Arabic-Indic and Persian digits as 0–9, so numbers in a translation can be matched. */
    fun westernDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                },
            )
        }
    }

    private fun usesArabicDigits(text: String) = text.any { it in '٠'..'٩' }

    private fun arabicDigits(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in '0'..'9') '٠' + (c - '0') else c)
    }

    /** Glossary terms worth a placeholder: real words, longest first so "Sung Jin-woo" wins over "Jin-woo". */
    private fun nameTerms(glossary: Map<String, String>): List<String> =
        glossary.keys.filter { it.trim().length >= 2 && it.any(Char::isLetter) }.sortedByDescending { it.length }

    private fun nameRegex(term: String) = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(normalize(term)) + "(?![\\p{L}\\p{N}])")

    fun key(text: String, glossary: Map<String, String> = emptyMap()): Keyed {
        val exact = normalize(text)
        // Numbers first: the name placeholders carry digits of their own
        val numbers = mutableListOf<String>()
        var template = numberPattern.replace(exact) { match ->
            numbers += match.value
            "{n${numbers.size - 1}}"
        }
        val names = mutableListOf<String>()
        for (term in nameTerms(glossary)) {
            val regex = nameRegex(term)
            if (!regex.containsMatchIn(template)) continue
            template = regex.replace(template) { "{name${names.size}}" }
            names += term
        }
        return Keyed(exact, template, numbers, names)
    }

    /**
     * The translation with the same placeholders as [keyed], or null when a number or a name of the line
     * cannot be found in it (then the line is only reused word for word).
     */
    fun templateOf(translation: String, keyed: Keyed, glossary: Map<String, String>): String? {
        if (!keyed.isTemplate) return translation
        var out = westernDigits(translation)
        // Numbers first, as in [key]
        var from = 0
        for ((i, number) in keyed.numbers.withIndex()) {
            val at = out.indexOf(number, from)
            if (at < 0) return null
            out = out.replaceRange(at, at + number.length, "{n$i}")
            from = at + "{n$i}".length
        }
        for ((i, term) in keyed.names.withIndex()) {
            val translated = glossary[term]?.takeIf { it.isNotBlank() } ?: return null
            val at = out.indexOf(translated, ignoreCase = true)
            if (at < 0) return null
            out = out.replaceRange(at, at + translated.length, "{name$i}")
        }
        return out
    }

    /** Puts the line's own numbers and names back into a saved [template] translation. */
    fun fill(template: String, keyed: Keyed, glossary: Map<String, String>, arabic: Boolean): String? {
        var out = template
        for ((i, term) in keyed.names.withIndex()) {
            val translated = glossary[term]?.takeIf { it.isNotBlank() } ?: return null
            out = out.replace("{name$i}", translated)
        }
        for ((i, number) in keyed.numbers.withIndex()) {
            out = out.replace("{n$i}", if (arabic) arabicDigits(number) else number)
        }
        // A placeholder left over means the saved line had more numbers or names than this one
        return out.takeUnless { it.contains("{n") }
    }

    /** Whether a translation writes its numbers in Arabic-Indic digits, to write new numbers the same way. */
    fun arabicStyle(translation: String) = usesArabicDigits(translation)

    /** 0–1, how alike two cleaned lines are (1 = the same), for lines the OCR read a little differently. */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        val longest = max(a.length, b.length)
        if (longest == 0) return 1f
        if (min(a.length, b.length) < longest * 0.8f) return 0f
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1f - previous[b.length].toFloat() / longest
    }
}
