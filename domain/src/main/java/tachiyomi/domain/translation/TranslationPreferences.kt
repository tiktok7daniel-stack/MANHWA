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
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ARABIC")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationEngine() = preferenceStore.getInt("translation_engine", 6)
    fun translationEngineModel() = preferenceStore.getString("translation_engine_model_v2", "")
    fun translationEngineApiKey() = preferenceStore.getString("translation_engine_api_key", "")
    fun translationEngineTemperature() = preferenceStore.getString("translation_engine_temperature", "0.4")
    fun translationEngineMaxOutputTokens() = preferenceStore.getString("translation_engine_output_tokens", "16000")
    fun translationEngineBaseUrl() = preferenceStore.getString("translation_engine_base_url", "")
    fun translationInstructions() = preferenceStore.getString("translation_instructions", "")
    fun translationVision() = preferenceStore.getBoolean("translation_vision", true)

    /** Typeface of translated bubbles (index of TranslationTypeface, 0 = Auto by bubble kind). */
    fun translationTypeface() = preferenceStore.getInt("manhwa_translation_typeface", 11)

    /** Leave sound effects drawn in the art untranslated. */
    fun translationSkipSfx() = preferenceStore.getBoolean("manhwa_translation_skip_sfx", false)

    /** Text size relative to the best fit inside each bubble (0.7 to 1.6). */
    fun translationSizeScale() = preferenceStore.getFloat("manhwa_translation_size_scale", 1f)

    /** Smallest text size in sp, so text never becomes unreadable. */
    fun translationMinSize() = preferenceStore.getInt("manhwa_translation_min_size", 9)

    /** Light outline around letters, for text over art. */
    fun translationOutline() = preferenceStore.getBoolean("manhwa_translation_outline", true)

    /** Line height multiplier. */
    fun translationLineSpacing() = preferenceStore.getFloat("manhwa_translation_line_spacing", 1.12f)

    /** Translate chapters that have no saved translation while reading, page by page. */
    fun liveTranslation() = preferenceStore.getBoolean("live_translation", true)

    /** The floating MANHWA translation button inside the reader. */
    fun translationReaderButton() = preferenceStore.getBoolean("manhwa_translation_reader_button", true)

    /** The translation collection: lines translated before are reused at once, in every manhwa. */
    fun translationCollection() = preferenceStore.getBoolean("manhwa_translation_collection", true)

    /** Largest size of the translation collection on storage, in megabytes (0 = no limit). */
    fun translationCollectionLimitMb() = preferenceStore.getInt("manhwa_translation_collection_limit_mb", 1024)
}
