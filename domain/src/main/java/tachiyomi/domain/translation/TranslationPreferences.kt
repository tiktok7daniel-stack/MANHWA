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
    fun translationSkipSfx() = preferenceStore.getBoolean("manhwa_translation_skip_sfx", true)

    /** MANHWA: skill, spell and sign text drawn into the art is translated as a caption under it, the art kept. */
    fun translationArtText() = preferenceStore.getBoolean("manhwa_translation_art_text", true)

    /** MANHWA master codex packs in use (system, murim, fantasy): standard Arabic for each genre's terms. */
    fun translationCodexPacks() = preferenceStore.getStringSet("manhwa_codex_packs", setOf("system", "murim", "fantasy"))

    /** MANHWA: OCR slips (split words, 0 for O, 1 for I) mended before the collection and the AI see a line. */
    fun translationOcrHealer() = preferenceStore.getBoolean("manhwa_ocr_healer", true)

    /** MANHWA: sound effects were switched to "left as drawn" once for readers from before it was the default. */
    fun translationSfxMigrated() = preferenceStore.getBoolean("manhwa_translation_sfx_migrated", false)

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

    /** The team's keys, as JSON (see TeamStore). */
    fun translationTeamMembers() = preferenceStore.getString("manhwa_translation_team_members", "[]")

    /** What the last analysis found out about each key, as JSON (see TeamStore). */
    fun translationTeamAnalysis() = preferenceStore.getString("manhwa_translation_team_analysis", "{}")

    /** Each key's use today and what its provider says is left, as JSON (see UsageMeter). */
    fun translationTeamUsage() = preferenceStore.getString("manhwa_translation_team_usage", "{}")

    /** Chapters waiting to be translated ("mangaId:chapterId,…"), so the queue survives the app being closed. */
    fun translationPendingQueue() = preferenceStore.getString("manhwa_translation_pending_queue", "")

    /** Calls made today by each kind of work (live, chapters, chat…), as JSON (see AiRouter). */
    fun translationTeamLanes() = preferenceStore.getString("manhwa_translation_team_lanes", "{}")

    /** Accurate mode: after the team translates, its lead reviews the chapter and fixes what is wrong. */
    fun translationTeamAccurate() = preferenceStore.getBoolean("manhwa_translation_team_accurate", false)

    /** While a chapter is read, the next one is translated in the background (off unless the reader turns it on). */
    fun translationPretranslateNext() = preferenceStore.getBoolean("manhwa_translation_pretranslate_next", false)

    /** System windows (game status, quests) drawn with glowing HUD corners, shouts with a heavy outline. */
    fun translationDramaticStyle() = preferenceStore.getBoolean("manhwa_translation_dramatic_style", true)

    /** Auto-scroll slows down where there is a lot of text to read and speeds up over art. */
    fun translationSmartScroll() = preferenceStore.getBoolean("manhwa_translation_smart_scroll", true)

    /** Inside the reader, a quick Google draft shows first and the team's translation replaces it. */
    fun translationQuickDraft() = preferenceStore.getBoolean("manhwa_translation_quick_draft", true)
}
