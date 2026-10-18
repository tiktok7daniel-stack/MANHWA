package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA: no bubble stays in English. After any engine (the laptop, a key, the phone model) a line whose translation
 * has no Arabic letter while its source has words is translated again by Google, and kept only when it comes back
 * in Arabic. Sound effects left as drawn (no translation) and watermarks are not touched.
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
        val left = pages.values.flatMap { it.blocks }.filter(::leftInSource)
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

    private const val KEY = "english-guard"
    private const val MIN_LETTERS = 2
}
