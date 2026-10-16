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
    fun translationCodexPacks() = preferenceStore.getStringSet("manhwa_codex_packs", setOf("system", "murim", "fantasy", "modern", "gaming", "idioms"))

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

    /** MANHWA: each key's health (status, limits, errors, cooldown, models, use today), as JSON (see KeyHealthStore). */
    fun translationTeamKeyHealth() = preferenceStore.getString("manhwa_translation_team_key_health", "{}")

    /** When a chapter is read, its lines missing from the translation library are saved there. */
    fun translationSaveOnRead() = preferenceStore.getBoolean("manhwa_translation_save_on_read", true)

    /** When a chapter is read, its translation file, temporary images and cache are deleted (the library keeps the lines). */
    fun translationFreeSpaceOnRead() = preferenceStore.getBoolean("manhwa_translation_free_space_on_read", true)

    /** When a chapter is read, its downloaded pages are deleted too (off: downloads kept for offline reading). */
    fun translationDeleteDownloadOnRead() = preferenceStore.getBoolean("manhwa_translation_delete_download_on_read", false)

    /** Space freed by deleting read chapters' temporary files, in bytes. */
    fun translationFreedBytes() = preferenceStore.getLong("manhwa_translation_freed_bytes", 0L)

    /** The next chapter is translated by itself while reading, when the team has enough working keys. */
    fun translationAutoPrefetch() = preferenceStore.getBoolean("manhwa_translation_auto_prefetch", true)

    /** Doubtful and important lines are checked by a second key, and the better translation is kept. */
    fun translationConsensus() = preferenceStore.getBoolean("manhwa_translation_consensus", true)

    /** New chapters of library works from English sources are translated at night (charging, Wi-Fi). */
    fun translationNightly() = preferenceStore.getBoolean("manhwa_translation_nightly", true)

    /** Each chapter's translation quality (from library, by AI, reviewed, doubtful), as JSON. */
    fun translationChapterQuality() = preferenceStore.getString("manhwa_translation_chapter_quality", "{}")

    /** Letters on busy drawings are inpainted from the drawing itself (real scanlation cleaning). */
    fun translationDeepClean() = preferenceStore.getBoolean("manhwa_translation_deep_clean", true)

    /** The on-device AI model file the reader imported (a MediaPipe .task file), empty when none. */
    fun translationLocalModelPath() = preferenceStore.getString("manhwa_translation_local_model_path", "")

    /** The on-device AI is used: as the draft before the keys, and alone when no key works. */
    fun translationLocalAi() = preferenceStore.getBoolean("manhwa_translation_local_ai", true)

    /** The reader's Arabic: FUSHA (formal), LIGHT (light formal, the default) or EGYPTIAN (spoken Egyptian). */
    fun translationStyleDialect() = preferenceStore.getString("manhwa_translation_style_dialect", "LIGHT")

    /** Korean and Japanese honorifics: KEEP ("hyung", "senpai") or ARABIC ("my big brother", "senior"). */
    fun translationStyleHonorifics() = preferenceStore.getString("manhwa_translation_style_honorifics", "ARABIC")

    /** What the reader's choices taught about their style, as JSON (see StyleProfile). */
    fun translationStyleProfile() = preferenceStore.getString("manhwa_translation_style_profile", "{}")

    /** The work's brain: characters, relations, ranks, places and a timeline learnt chapter by chapter. */
    fun translationSeriesBrain() = preferenceStore.getBoolean("manhwa_translation_series_brain", true)

    /** One work, all sources: a dead source or a missing chapter is read from another source by itself. */
    fun translationUnifiedSources() = preferenceStore.getBoolean("manhwa_translation_unified_sources", true)

    /** The hybrid orchestra: the on-device models and the team's keys work on every chapter together. */
    fun translationOrchestra() = preferenceStore.getBoolean("manhwa_translation_orchestra", true)

    /** A second on-device model that judges between two translations (empty: the main one judges). */
    fun translationLocalJudgePath() = preferenceStore.getString("manhwa_translation_local_judge_path", "")

    /** The reader's own Hugging Face token, only to download gated models they accepted (kept on the phone). */
    fun translationHfToken() = preferenceStore.getString("manhwa_translation_hf_token", "")

    /** MANHWA phone ensemble: models on the phone the reader switched off (every other model plays). */
    fun translationInactiveModels() = preferenceStore.getStringSet("manhwa_translation_inactive_models", emptySet())

    /** What the orchestra did (lines by the phone, by the keys, judgements), as JSON (see OrchestraStats). */
    fun translationOrchestraStats() = preferenceStore.getString("manhwa_translation_orchestra_stats", "{}")
}
