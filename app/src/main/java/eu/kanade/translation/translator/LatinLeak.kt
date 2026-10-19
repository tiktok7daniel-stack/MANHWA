package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation

/**
 * MANHWA: only Arabic on the page. The OCR sometimes reads a few letters wrong ("yo0", "S / tuation", a stray
 * "I" from the bubble's edge) and the engines pass what they cannot read through as it is, so bits of Latin end up
 * inside an Arabic bubble ("قبض IW عليه"). Those bits are taken out; a hunter's rank ("S", "SS", "A+") and numbers
 * stay, they are meant to be read as they are.
 */
object LatinLeak {

    private val token = Regex("[\\p{IsLatin}0-9][\\p{IsLatin}0-9'’.+\\-]*")
    private val rank = Regex("^(SSS|SS|S|[A-F])[+\\-]?$")
    private val arabic = Regex("[\\u0600-\\u06FF]")

    /** [translation] without the Latin bits the source's misread letters left in it (only when it is Arabic). */
    fun strip(translation: String, source: String): String {
        if (!arabic.containsMatchIn(translation) || translation.contains(AiTranslator.WATERMARK)) return translation
        val sourceTokens = token.findAll(source).map { it.value.trimEnd('.', '\'', '’') }.toSet()
        val out = token.replace(translation) { m ->
            val t = m.value.trimEnd('.')
            val kept = t.all { it.isDigit() } || (rank.matches(t) && t in sourceTokens)
            if (kept) m.value else if (m.value.endsWith(".")) "." else ""
        }
        if (out == translation) return translation
        return tidy(out).ifBlank { translation }
    }

    /** Every bubble of [pages] going into Arabic, cleaned of Latin bits; how many changed. */
    fun stripAll(pages: Map<String, PageTranslation>): Int {
        var changed = 0
        pages.values.forEach { page ->
            page.blocks.forEach { block ->
                val clean = strip(block.translation, block.text)
                if (clean != block.translation) {
                    block.translation = clean
                    changed++
                }
            }
        }
        return changed
    }

    /** Spaces and punctuation left behind by what was taken out. */
    private fun tidy(text: String): String = text
        .replace(Regex("\\(\\s*\\)"), "")
        .let { t -> if ('(' in t) t else t.replace(")", "") }
        .replace(Regex("(?<=\\s)[/\\\\|]+(?=\\s)"), "")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("[ \\t]+([.,!?؟،؛:)])"), "$1")
        .replace(Regex("(?m)^[ \\t]+|[ \\t]+$"), "")
        .replace(Regex("(?m)^[.,،؟?!]+\\s*"), "")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}
