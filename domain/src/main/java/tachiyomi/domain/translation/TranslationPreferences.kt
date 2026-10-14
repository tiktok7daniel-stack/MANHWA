package tachiyomi.domain.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)
    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationEngine() = preferenceStore.getInt("translation_engine", 0)
    fun translationEngineModel() = preferenceStore.getString("translation_engine_model_v2", "")
    fun translationEngineApiKey() = preferenceStore.getString("translation_engine_api_key", "")
    fun translationEngineTemperature() = preferenceStore.getString("translation_engine_temperature", "0.4")
    fun translationEngineMaxOutputTokens() = preferenceStore.getString("translation_engine_output_tokens", "16000")
    fun translationEngineBaseUrl() = preferenceStore.getString("translation_engine_base_url", "")
    fun translationInstructions() = preferenceStore.getString("translation_instructions", "")
    fun translationVision() = preferenceStore.getBoolean("translation_vision", true)
}
