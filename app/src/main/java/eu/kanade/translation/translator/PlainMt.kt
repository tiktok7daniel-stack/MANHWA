package eu.kanade.translation.translator

import eu.kanade.translation.live.OcrSanity

/**
 * MANHWA: what Google Translate and the offline ML Kit model get, made the most of. Neither has a stronger model to
 * pick, so the text they get is made easier: the voiced sounds every manhwa repeats (HUH, UGH, TSK...) are written
 * in Arabic directly, without being sent (Google spells them out or translates them as words), and a line the OCR
 * misread on decorative letters is mended first (one look-alike letter swap that gives an English word).
 */
object PlainMt {

    /** Voiced sounds and interjections, a whole bubble of them, in Arabic. */
    private val sounds = mapOf(
        "huh" to "هاه", "hah" to "هاه", "ha" to "ها", "haha" to "هاها", "hahaha" to "هاهاها", "heh" to "هه",
        "hehe" to "هيهي", "ugh" to "آه", "argh" to "آآه", "agh" to "آه", "ah" to "آه", "aah" to "آآه", "oh" to "أوه",
        "uh" to "أه", "um" to "امم", "hm" to "همم", "hmm" to "همم", "tsk" to "تسك", "tch" to "تش", "tut" to "تسك",
        "hmph" to "همف", "humph" to "همف", "huff" to "هاه", "hff" to "هاه", "pant" to "هاه", "gasp" to "هاه",
        "sigh" to "آه", "phew" to "فيو", "wow" to "واو", "whoa" to "واو", "oops" to "أوبس", "eh" to "إيه",
        "ow" to "آي", "ouch" to "آي", "eek" to "آآه", "hyuk" to "هئ", "heuk" to "هئ", "keugh" to "كح", "cough" to "كح",
        "kuh" to "كح", "sniff" to "شهق", "sob" to "هئ", "gulp" to "غلب", "yawn" to "هااه",
    )

    private val wrapped = Regex("^[\\s(*~\\[]*([A-Za-z]+(?:[\\s-]+[A-Za-z]+){0,2})[\\s)*~\\]]*([.!?…]*)[\\s)*~\\]]*$")

    /**
     * The Arabic of [text] when the whole bubble is a voiced sound ("HUFF...", "(TSK)", "UGH!", "HA HA"), with its
     * own punctuation; null for anything else.
     */
    fun sound(text: String): String? {
        val m = wrapped.matchEntire(text.trim()) ?: return null
        val words = m.groupValues[1].lowercase().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        val arabic = words.map { w -> sounds[w] ?: sounds[squeeze(w)] ?: return null }
        val marks = m.groupValues[2].replace('?', '؟').replace("…", "...")
        return arabic.joinToString(" ") + marks
    }

    /** "UGHHH" read as "ugh", "HUUUH" as "huh": a letter said longer is the same sound. */
    private fun squeeze(word: String): String {
        val out = StringBuilder()
        for (c in word) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    /** [text] with words the OCR misread on decorative letters mended, when the English word list is loaded. */
    fun preEdit(text: String): String = OcrSanity.repairIfLoaded(text)
}
