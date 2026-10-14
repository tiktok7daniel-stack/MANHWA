package eu.kanade.translation.translator

import eu.kanade.translation.data.PageImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage
    suspend fun translate(pages: MutableMap<String, PageTranslation>)

    /** Engines that can look at the page override this; the others only use the OCR text. */
    suspend fun translate(pages: MutableMap<String, PageTranslation>, images: Map<String, PageImage>) = translate(pages)
}

enum class TextTranslators(val label: String, val needsApiKey: Boolean = false) {
    MLKIT("ML Kit (on device, offline)"),
    GOOGLE("Google Translate"),
    GEMINI("Gemini AI", needsApiKey = true),
    OPENROUTER("OpenRouter (any model)", needsApiKey = true),
    OPENAI("OpenAI-compatible (custom URL)", needsApiKey = true),
    CLAUDE("Claude AI", needsApiKey = true),
    ;

    val isAi: Boolean get() = this in AI_ENGINES

    fun build(
        pref: TranslationPreferences,
        fromLang: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(pref.translateFromLanguage()),
        toLang: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(pref.translateToLanguage()),
    ): TextTranslator {
        fun ai(provider: AiTranslator.Provider) = AiTranslator(
            fromLang = fromLang,
            toLang = toLang,
            provider = provider,
            apiKey = pref.translationEngineApiKey().get().trim(),
            model = pref.translationEngineModel().get().trim(),
            baseUrl = pref.translationEngineBaseUrl().get().trim(),
            maxOutputTokens = pref.translationEngineMaxOutputTokens().get().toIntOrNull() ?: 16000,
            temperature = pref.translationEngineTemperature().get().toFloatOrNull() ?: 0.4f,
            instructions = pref.translationInstructions().get(),
        )
        return when (this) {
            MLKIT -> MLKitTranslator(fromLang, toLang)
            GOOGLE -> GoogleTranslator(fromLang, toLang)
            GEMINI -> ai(AiTranslator.Provider.GEMINI)
            OPENROUTER -> ai(AiTranslator.Provider.OPENROUTER)
            OPENAI -> ai(AiTranslator.Provider.OPENAI_COMPATIBLE)
            CLAUDE -> ai(AiTranslator.Provider.CLAUDE)
        }
    }

    companion object {
        private val AI_ENGINES = setOf(GEMINI, OPENROUTER, OPENAI, CLAUDE)

        fun fromPref(pref: Preference<Int>): TextTranslators {
            var translator = entries.getOrNull(pref.get())
            if (translator == null) {
                pref.set(0)
                return MLKIT
            }
            return translator
        }
    }
}
