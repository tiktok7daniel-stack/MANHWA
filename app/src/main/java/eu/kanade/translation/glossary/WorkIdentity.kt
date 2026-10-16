package eu.kanade.translation.glossary

import java.text.Normalizer

/**
 * MANHWA work identity: the same work read from two sources ("Solo Leveling" on one site, "Solo Leveling
 * (Official)" on another) gets one key, so its dictionary, names, characters and story are shared. The key is
 * strict on purpose: only titles that are the same after cleaning are one work, never titles that only look
 * alike, so two different works are never mixed.
 */
object WorkIdentity {

    private val brackets = Regex("[\\(\\[\\{【「『].*?[\\)\\]\\}】」』]")
    private val leading = Regex("^(the|a|an)\\s+")
    private val tails = Regex("\\s+(official|webtoon|manhwa|manga|manhua|novel|raw|mtl|colored|colour|color)$")
    private val notWord = Regex("[^\\p{L}\\p{N}]+")

    fun key(title: String): String {
        var t = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        t = t.lowercase().replace(brackets, " ").replace('’', '\'').trim()
        t = t.replace(notWord, " ").trim()
        repeat(2) { t = t.replace(tails, "").trim() }
        t = t.replace(leading, "")
        return t.replace(" ", "")
    }

    /** Whether two titles are one work. Titles too short to tell (under 4 letters) never match another. */
    fun same(a: String, b: String): Boolean {
        val ka = key(a)
        return ka.length >= MIN_KEY && ka == key(b)
    }

    const val MIN_KEY = 4
}
