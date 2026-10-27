package eu.kanade.tachiyomi.ui.reader

import eu.kanade.translation.model.PageTranslation

/**
 * MANHWA: a fight page, from what its translation found on it: sound effects, shouts, or the short all-caps blasts
 * (BOOM!, KWANG!) action scenes are drawn with. The reader shakes and buzzes a little when one comes up.
 */
object FightPages {

    private val sounds = listOf(
        "boom", "bang", "crash", "slash", "kwang", "kwa", "kugugu", "pow", "wham", "thud", "bam", "smash", "clang",
        "swish", "zap", "shing", "puk", "kaboom", "rumble", "crack", "bzz", "dodo", "kung", "tak", "chak",
        "쾅", "콰", "퍽", "쿵", "슉", "챙", "轰", "砰", "嘭", "ドン", "バン", "ドカ",
        "بوم", "طاخ", "بانغ", "كراش",
    )

    fun isFight(translation: PageTranslation?): Boolean {
        val blocks = translation?.blocks ?: return false
        var hits = 0
        for (b in blocks) {
            val kind = b.kind.lowercase()
            val text = b.text.trim()
            val lower = text.lowercase()
            val blast = text.length in 2..14 && text.any { it.isLetter() } && text == text.uppercase() && text.count { it == '!' } >= 1
            if (kind == "sfx" || kind == "shout" || blast || sounds.any { lower.contains(it) }) hits++
            if (hits >= 2) return true
        }
        return false
    }
}
