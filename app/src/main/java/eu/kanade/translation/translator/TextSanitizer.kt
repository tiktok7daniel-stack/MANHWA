package eu.kanade.translation.translator

/**
 * MANHWA: cleans what a model wrote before it is drawn in a bubble: Markdown marks (**bold**, `code`, # titles,
 * list dashes), a "Translation:" label, quotes wrapped around the whole line when the source had none, and
 * Chinese, Japanese or Korean letters left over in a translation into a language that does not use them.
 * Latin words are kept: names, ranks and stats ("S-Rank", "HP") are often written that way on purpose.
 */
object TextSanitizer {

    private val markdown = Regex("""\*\*|__|(?<!\w)\*(?!\s)|(?<!\s)\*(?!\w)|`+""")
    private val heading = Regex("""^\s{0,3}#{1,6}\s+""", RegexOption.MULTILINE)
    private val bullet = Regex("""^\s*[-•]\s+(?=\S)""", RegexOption.MULTILINE)
    private val label = Regex("""^\s*(translation|translated|ترجمة|الترجمة)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
    private val cjk = Regex("""[぀-ヿ㐀-䶿一-鿿가-힯ᄀ-ᇿ]+""")
    private val spaces = Regex("""[ \t]{2,}""")
    private val quotePairs = listOf('"' to '"', '“' to '”', '«' to '»', '「' to '」', '『' to '』')

    fun clean(translation: String, source: String, to: TextTranslatorLanguage): String {
        if (translation.isBlank()) return translation
        var text = translation
            .replace(markdown, "")
            .replace(heading, "")
            .replace(bullet, "")
            .replace(label, "")
        // Quotes around the whole line that the original did not have
        val trimmed = text.trim()
        quotePairs.firstOrNull { (open, close) -> trimmed.length > 2 && trimmed.first() == open && trimmed.last() == close }
            ?.let { (open, _) -> if (!source.trim().startsWith(open)) text = trimmed.substring(1, trimmed.length - 1) }
        if (to !in CJK_TARGETS) text = text.replace(cjk, " ")
        // MANHWA: a model's tatweel ("لهـــاروتو") stretches a word across the bubble
        text = dropTatweel(text)
        val cleaned = text.replace(spaces, " ").trim()
        // Never turn a translation into nothing: the original model output stays then
        return cleaned.ifBlank { translation.trim() }
    }

    /** Removes the Arabic stretching mark (tatweel, U+0640) between letters. */
    fun dropTatweel(text: String): String = if ('\u0640' in text) text.replace("\u0640", "") else text

    private val CJK_TARGETS = setOf(
        TextTranslatorLanguage.CHINESESIM,
        TextTranslatorLanguage.CHINESETRAD,
        TextTranslatorLanguage.JAPANESE,
        TextTranslatorLanguage.KOREAN,
    )
}
