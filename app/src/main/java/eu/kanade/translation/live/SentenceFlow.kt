package eu.kanade.translation.live

/**
 * MANHWA: whether one line of text runs on into the next: the first does not end its sentence, and the next
 * starts with a word (not a new speaker's dash or a number). Pure Kotlin: tested without a phone.
 */
object SentenceFlow {

    private val enders = setOf('.', '!', '?', '…', '"', '”', '»', ')', ']', '~', '♪', '♥')

    fun continues(upper: String, lower: String): Boolean {
        val top = upper.trimEnd()
        val next = lower.trimStart()
        if (top.isEmpty() || next.isEmpty()) return false
        // An English line that ends its sentence, or Latin ellipsis "..." (a pause, often the end of a bubble)
        if (top.last() in enders) return false
        // Too little to be a sentence: a lone letter or a sound
        if (top.count { it.isLetter() } < 3) return false
        val first = next.first()
        return first.isLetter() || first == '\'' || first == '’'
    }
}
