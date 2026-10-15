package eu.kanade.translation.translator

import eu.kanade.translation.data.PageImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Hybrid Turbo: Google Translate gives every bubble a fast draft, then the AI reads the whole page with those
 * drafts, the series glossary and the scene, and writes the final text (context, tone, gender, names).
 * If the AI is busy or fails, the Google draft stays, so the reader always gets a translation.
 */
class HybridTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val google: GoogleTranslator,
    /** Null when no AI key is set: then this is plain Google Translate. */
    val ai: AiTranslator?,
) : TextTranslator {

    /** Called with the Google drafts before the AI pass, so they can be shown right away. */
    var onDraft: ((MutableMap<String, PageTranslation>) -> Unit)? = null

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = translate(pages, emptyMap())

    override suspend fun translate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) {
        // 1. Fast draft
        runCatching { google.translate(pages) }
            .onFailure { if (it is CancellationException) throw it }
        onDraft?.invoke(pages)
        val ai = ai ?: return
        // 2. AI correction with full page context; drafts are kept if it fails
        val drafts = pages.mapValues { (_, page) -> page.blocks.map { it.copy() } }
        try {
            ai.useDrafts = true
            ai.translate(pages, images)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "AI pass failed, keeping the Google draft" }
            drafts.forEach { (name, blocks) -> pages[name]?.blocks = blocks.toMutableList() }
        } finally {
            ai.useDrafts = false
        }
    }

    override fun close() {
        ai?.close()
    }
}

/** The AI part of an engine, if any: the engine itself, or the AI pass of the hybrid engine. */
val TextTranslator.aiPart: AiTranslator?
    get() = this as? AiTranslator ?: (this as? HybridTranslator)?.ai ?: (this as? TeamTranslator)?.lead
