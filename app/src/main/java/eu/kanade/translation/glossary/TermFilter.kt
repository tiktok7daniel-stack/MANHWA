package eu.kanade.translation.glossary

/**
 * MANHWA: whether a term the AI found may join the series glossary. A small model (the home server) often lists
 * whole lines ("..WOW. ARE YOU THE SCHOOL BOSS?"), site credits ("12 Nations (Read at HIVETDON.COM)") or answers
 * in English or with a label ("Question", "Confused"); none of these is a name, and an Arabic glossary needs Arabic.
 * - the source is a name or a short term: a few words at most, not a question or a cry, no site, URL or handle;
 * - for an Arabic target the translation has Arabic letters.
 */
object TermFilter {

    private const val MAX_WORDS = 4
    private const val MAX_CHARS = 40

    private val site = Regex("""(?i)https?://|www\.|\.(com|net|org|io|me|co)\b|@|\bread at\b|\bupdates?\b""")

    fun accepts(source: String, translation: String, arabicTarget: Boolean): Boolean {
        val s = source.trim()
        val t = translation.trim()
        if (s.isEmpty() || t.isEmpty() || s.none(Char::isLetter)) return false
        if (s.length > MAX_CHARS || s.split(Regex("\\s+")).size > MAX_WORDS) return false
        if (s.endsWith('?') || s.endsWith('!') || s.startsWith("..") || s.startsWith('…') || s.endsWith("...") || s.endsWith('…')) return false
        if (site.containsMatchIn(s)) return false
        if (arabicTarget && t.none { it in '؀'..'ۿ' }) return false
        return true
    }
}
