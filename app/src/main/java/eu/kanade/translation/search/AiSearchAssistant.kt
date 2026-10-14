package eu.kanade.translation.search

import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslators
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.translation.TranslationPreferences

/**
 * Turns a free-form request ("revenge manhwa where the MC sees colours", a half-remembered title, a title in
 * another script) into the titles a source is most likely to index, using the AI engine set up for translation.
 */
class AiSearchAssistant(private val preferences: TranslationPreferences) {

    class NotConfiguredException : IllegalStateException("No AI engine configured")

    suspend fun suggestQueries(request: String): List<String> {
        val engine = TextTranslators.fromPref(preferences.translationEngine())
        if (!engine.isAi || preferences.translationEngineApiKey().get().isBlank() && engine != TextTranslators.OPENAI) {
            throw NotConfiguredException()
        }
        val ai = engine.build(preferences) as AiTranslator
        try {
            val reply = ai.askJson(SYSTEM, request.trim())
            return (reply["queries"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_QUERIES)
        } finally {
            ai.close()
        }
    }

    private companion object {
        const val MAX_QUERIES = 6
        val SYSTEM = """
            You help readers find comics (manga, manhwa, manhua, webtoons) on scanlation and publisher sites.
            The reader describes what they want in any language: an exact or half-remembered title, the plot,
            characters, tags, or a title in another script.
            Identify the series that best match and reply with ONLY this JSON:
            {"queries": ["...", "..."]}
            with up to 6 short search strings, best first: for the top match give its most common English or
            romanized title, its official English title, and its native title (Korean, Japanese or Chinese);
            then the titles of the next most likely series. No descriptions, no years, no punctuation that sites
            would not index.
        """.trimIndent()
    }
}
