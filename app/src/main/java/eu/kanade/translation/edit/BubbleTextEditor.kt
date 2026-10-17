package eu.kanade.translation.edit

import android.content.Context
import eu.kanade.translation.collection.TranslationCollection
import eu.kanade.translation.learning.LessonStore
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.appAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat

/**
 * MANHWA: a bubble the reader rewrote in the reader. The new wording is a lesson for the team (shown in "What the
 * team learnt" and turned into rules), a line of the collection that beats any other translation of it, and, when
 * the bubble was just one glossary term, the glossary's translation of that term.
 */
object BubbleTextEditor {

    /** Learns [new] (it replaced [old]) for the manhwa open in the reader. Off the main thread; never throws. */
    suspend fun learn(context: Context, block: TranslationBlock, old: String, new: String) = withContext(Dispatchers.IO) {
        runCatching {
            val graph = context.appGraph
            val mangaId = graph.translationEditStore.mangaId() ?: return@runCatching
            if (new.isBlank() || new.trim() == old.trim()) return@runCatching
            graph.lessonStore.add(mangaId, LessonStore.Lesson(block.text, old, new, LessonStore.Origin.USER.name))
            val prefs = graph.translationPreferences
            val glossary = graph.glossaryStore.asMap(mangaId)
            graph.translationCollection.learn(
                // The same keys the chapter and the reader look lines up by (dialect included), or the
                // correction would never be found again
                sourceLanguage(graph.translationEditStore.sourceLang(), TextRecognizerLanguage.fromPref(prefs.translateFromLanguage()).name),
                eu.kanade.translation.style.StyleProfile.libraryTarget(
                    prefs,
                    eu.kanade.translation.translator.TextTranslatorLanguage.fromPref(prefs.translateToLanguage()).name,
                ),
                mangaId,
                glossary,
                listOf(TranslationCollection.Line(block.text, new.trim(), block.kind)),
                TranslationCollection.Source.USER,
            )
            // The whole bubble was one glossary term: the reader's wording becomes the term's translation
            glossary.entries.firstOrNull { (source, translation) ->
                source.trim().equals(block.text.trim(), ignoreCase = true) && translation.trim() == old.trim()
            }?.let { (source, _) -> graph.glossaryStore.put(mangaId, source, new.trim()) }
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not learn the reader's wording" } }
        Unit
    }

    /**
     * Up to three better wordings of [block] from the team (chat lane: it waits behind the page being read), in
     * the voice its kind needs. Throws when no key can answer.
     */
    suspend fun rephrase(context: Context, block: TranslationBlock): List<String> {
        val graph = context.appGraph
        val prefs = graph.translationPreferences
        val target = TextTranslatorLanguage.fromPref(prefs.translateToLanguage())
        val glossary = graph.translationEditStore.mangaId()?.let(graph.glossaryStore::asMap).orEmpty()
        val prompt = buildString {
            appendLine("Original (${block.kind.ifBlank { "speech" }} bubble): ${block.text}")
            appendLine("Current translation: ${block.translation}")
            if (glossary.isNotEmpty()) {
                appendLine("Glossary (keep these exactly):")
                glossary.entries.take(MAX_GLOSSARY).forEach { (source, translation) -> appendLine("- $source => $translation") }
            }
            append("Write 3 better translations into ${target.label}.")
        }
        val reply = appAsk(AiRouter.Lane.CHAT) { ai ->
            ai.askJson(
                "You are a top comic localizer. Rewrite one speech bubble so it reads naturally, keeps the meaning, " +
                    "the speaker's tone and the bubble kind's style (shouts short and forceful, system windows precise, " +
                    "narration polished), and fits a speech bubble. Reply with ONLY: {\"options\": [\"...\", \"...\", \"...\"]}",
                prompt,
            )
        }
        return (reply["options"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() && it != block.translation.trim() }
            .distinct()
            .take(3)
    }

    /** The OCR language of the source, as live translation picks it, so the collection finds the line again. */
    /** The collection's source key for a work in [sourceLang] (the reader's choice when the source has none). */
    fun sourceLanguage(sourceLang: String?, preferred: String): String =
        when (sourceLang?.lowercase()?.substringBefore('-')) {
            "ko" -> TextRecognizerLanguage.KOREAN.name
            "ja" -> TextRecognizerLanguage.JAPANESE.name
            "zh" -> TextRecognizerLanguage.CHINESE.name
            null, "", "all", "other" -> preferred
            else -> TextRecognizerLanguage.ENGLISH.name
        }

    private const val MAX_GLOSSARY = 40
}
