package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * MANHWA: no bubble stays in English. After any engine (the laptop, a key, the phone model) a line whose translation
 * has no Arabic letter while its source has words is translated again by Google, and kept only when it comes back
 * in Arabic. Sound effects left as drawn (no translation) and watermarks are not touched. A line left word for word
 * inside a bubble whose other line is in Arabic was joined into that translation by the AI: the renderer erases it,
 * so Google does not write it a second time.
 */
object EnglishGuard {

    /** Whether [block] still shows its source language where Arabic was asked. */
    fun leftInSource(block: TranslationBlock): Boolean = leftInSource(block.text, block.translation)

    fun leftInSource(source: String, translation: String): Boolean {
        val t = translation.trim()
        if (t.isEmpty() || t.contains(AiTranslator.WATERMARK)) return false
        if (source.count { it.isLetter() } < MIN_LETTERS) return false
        return t.none { it in '\u0600'..'\u06FF' }
    }

    /** Puts Google's Arabic on the lines of [pages] left in the source language; returns how many it fixed. */
    suspend fun fix(pages: Map<String, PageTranslation>, google: TextTranslator): Int {
        val left = pages.values.flatMap { page -> page.blocks.filter { leftInSource(it) && !joined(it, page.blocks) } }
        if (left.isEmpty()) return 0
        val copies = left.map { it.copy(translation = "") }
        val page = PageTranslation(blocks = copies.toMutableList())
        runCatching { google.translate(mutableMapOf(KEY to page)) }
            .onFailure {
                if (it is CancellationException) throw it
                logcat(LogPriority.WARN, it) { "Google could not translate the ${left.size} lines left in English" }
            }
        var fixed = 0
        left.zip(copies).forEach { (block, copy) ->
            if (copy.translation.isNotBlank() && !leftInSource(block.text, copy.translation)) {
                block.translation = copy.translation
                fixed++
            }
        }
        if (fixed > 0) logcat(LogPriority.WARN) { "$fixed lines left in English were translated by Google" }
        return fixed
    }

    /**
     * Whether [block], left exactly as its source, shares a bubble with a line translated into Arabic (both lie in
     * the same bubble found on the page, or touch within one line height), so its words are in that translation.
     */
    fun joined(block: TranslationBlock, blocks: List<TranslationBlock>): Boolean {
        if (!block.translation.trim().equals(block.text.trim(), ignoreCase = true)) return false
        val lines = block.lineBoxes
        val lineH = lines.map { it.h }.average().toFloat().takeIf { it > 0f } ?: return false
        val left = lines.minOf { it.x }
        val top = lines.minOf { it.y }
        val right = lines.maxOf { it.right }
        val bottom = lines.maxOf { it.bottom }
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return blocks.any { other ->
            if (other === block || other.translation.none { it in '\u0600'..'\u06FF' }) return@any false
            if (other.hasBubble && block.hasBubble) {
                return@any cx in other.bubbleX..(other.bubbleX + other.bubbleW) &&
                    cy in other.bubbleY..(other.bubbleY + other.bubbleH)
            }
            val o = other.lineBoxes
            val gapY = max(o.minOf { it.y } - bottom, top - o.maxOf { it.bottom })
            val overlapX = min(right, o.maxOf { it.right }) - max(left, o.minOf { it.x })
            gapY <= lineH && overlapX > 0f
        }
    }

    private const val KEY = "english-guard"
    private const val MIN_LETTERS = 2
}
