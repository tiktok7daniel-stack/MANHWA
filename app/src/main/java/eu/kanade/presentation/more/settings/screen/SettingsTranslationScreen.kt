package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import mihon.app.di.appGraph
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = ATMR.strings.pref_category_translations

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val translationPreferences = remember { context.appGraph.translationPreferences }
        return listOf(
            getLanguageGroup(translationPreferences),
            getEngineGroup(translationPreferences),
            getReadingGroup(translationPreferences),
        )
    }

    @Composable
    private fun getLanguageGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_setup),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.translateFromLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_from),
                    entries = TextRecognizerLanguage.entries.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.translateToLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_to),
                    entries = TextTranslatorLanguage.entries.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = prefs.autoTranslateAfterDownload(),
                    title = stringResource(ATMR.strings.pref_translate_after_downloading),
                ),
            ),
        )
    }

    @Composable
    private fun getEngineGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        val engineIndex by prefs.translationEngine().collectAsState()
        val engine = TextTranslators.entries.getOrElse(engineIndex) { TextTranslators.MLKIT }
        val provider = when (engine) {
            TextTranslators.GEMINI -> AiTranslator.Provider.GEMINI
            TextTranslators.OPENROUTER -> AiTranslator.Provider.OPENROUTER
            TextTranslators.OPENAI -> AiTranslator.Provider.OPENAI_COMPATIBLE
            TextTranslators.CLAUDE -> AiTranslator.Provider.CLAUDE
            else -> null
        }
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_engine),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.translationEngine(),
                    title = stringResource(ATMR.strings.pref_translator_engine),
                    entries = TextTranslators.entries.associate { it.ordinal to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.InfoPreference(
                    title = stringResource(
                        if (engine.isAi) ATMR.strings.pref_engine_info_ai else ATMR.strings.pref_engine_info_offline,
                    ),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.translationEngineApiKey(),
                    title = stringResource(ATMR.strings.pref_engine_api_key),
                    subtitle = stringResource(ATMR.strings.pref_sub_engine_api_key),
                    visible = engine.needsApiKey,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.translationEngineModel(),
                    title = stringResource(ATMR.strings.pref_engine_model),
                    subtitle = stringResource(ATMR.strings.pref_engine_model_summary, provider?.defaultModel.orEmpty()),
                    visible = provider != null,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.translationEngineBaseUrl(),
                    title = stringResource(ATMR.strings.pref_engine_base_url),
                    subtitle = stringResource(
                        ATMR.strings.pref_engine_base_url_summary,
                        provider?.defaultBaseUrl.orEmpty(),
                    ),
                    visible = engine == TextTranslators.OPENAI,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.translationInstructions(),
                    title = stringResource(ATMR.strings.pref_translation_instructions),
                    subtitle = stringResource(ATMR.strings.pref_translation_instructions_summary),
                    visible = engine.isAi,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.translationEngineTemperature(),
                    title = stringResource(ATMR.strings.pref_engine_temperature),
                    visible = engine.isAi && engine != TextTranslators.CLAUDE,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    preference = prefs.translationEngineMaxOutputTokens(),
                    title = stringResource(ATMR.strings.pref_engine_max_output),
                    visible = engine.isAi,
                ),
            ),
        )
    }

    @Composable
    private fun getReadingGroup(prefs: TranslationPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_advanced),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = prefs.translationFont(),
                    title = stringResource(ATMR.strings.pref_reader_font),
                    entries = TranslationFont.entries.associate { it.ordinal to it.label }.toImmutableMap(),
                ),
            ),
        )
    }
}
