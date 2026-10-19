package eu.kanade.translation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.collection.TranslationCollection
import eu.kanade.translation.collection.TranslationCollectionScreen
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.learning.LessonsScreen
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.team.TranslationTeamScreen
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.Mobile
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.CloudOff
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.DataUsage
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Draft
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.Key
import mihon.icons.materialsymbols.rounded.Language
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Psychology
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.Speed
import mihon.icons.materialsymbols.rounded.TextFields
import mihon.icons.materialsymbols.rounded.Thermostat
import mihon.icons.materialsymbols.rounded.TouchApp
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.Tune
import mihon.icons.materialsymbols.rounded.Upload
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreference

/**
 * MANHWA: everything about translation in one screen, opened from the side menu (and from Settings). A summary
 * of the team's keys and the collection on top, then languages, the AI, the reader and what the team knows.
 * Every setting has an (i) that explains what it does.
 */
class TranslationHubScreen(private val page: HubPage = HubPage.ROOT) : Screen() {

    /** MANHWA: the menus of the translation settings; each holds only what belongs together. */
    enum class HubPage { ROOT, KEYS, PHONE, NO_AI, QUALITY, READER, AUTO, KNOWLEDGE }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { context.appGraph.translationPreferences }
        val team = remember { context.appGraph.teamStore }
        val collection = remember { context.appGraph.translationCollection }

        val members by team.members.collectAsState()
        val analysis by team.analysis.collectAsState()
        val usage by team.usage.usage.collectAsState()
        val active = remember(members, analysis, usage) { team.active() }
        val lowest = remember(active, usage) { active.minOfOrNull { team.usage.level(it).percent } }
        var stats by remember { mutableStateOf<TranslationCollection.Stats?>(null) }
        var phoneModel by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            stats = withContext(Dispatchers.IO) { collection.stats() }
            phoneModel = withContext(Dispatchers.IO) { eu.kanade.translation.local.LocalAiEngine.available(context) }
            // Keys never checked are checked now, so every key shows how it is doing
            if (page == HubPage.ROOT || page == HubPage.KEYS) team.analyzeUnchecked()
            // The laptop's state on its card is the one of now
            if (page == HubPage.ROOT) runCatching { team.pingHome(force = true) }
        }

        val from by prefs.translateFromLanguage().collectPreference()
        val to by prefs.translateToLanguage().collectPreference()
        val auto by prefs.autoTranslateAfterDownload().collectPreference()
        val accurate by prefs.translationTeamAccurate().collectPreference()
        val vision by prefs.translationVision().collectPreference()
        val instructions by prefs.translationInstructions().collectPreference()
        val temperature by prefs.translationEngineTemperature().collectPreference()
        val maxOutput by prefs.translationEngineMaxOutputTokens().collectPreference()
        val engineIndex by prefs.translationEngine().collectPreference()
        val live by prefs.liveTranslation().collectPreference()
        val quickDraft by prefs.translationQuickDraft().collectPreference()
        // MANHWA fast translation (like Google Lens)
        val fastMode by prefs.translationFastMode().collectPreference()
        val fastEngine by prefs.translationFastEngine().collectPreference()
        val deeplKey by prefs.translationFastDeeplKey().collectPreference()
        val microsoftKey by prefs.translationFastMicrosoftKey().collectPreference()
        val microsoftRegion by prefs.translationFastMicrosoftRegion().collectPreference()
        val googleCloudKey by prefs.translationFastGoogleCloudKey().collectPreference()
        val fastLast by eu.kanade.translation.translator.FastUsage.last.collectAsState()
        val readerButton by prefs.translationReaderButton().collectPreference()
        val font by prefs.translationFont().collectPreference()
        val pretranslate by prefs.translationPretranslateNext().collectPreference()
        val autoPrefetch by prefs.translationAutoPrefetch().collectPreference()
        val saveOnRead by prefs.translationSaveOnRead().collectPreference()
        val freeSpace by prefs.translationFreeSpaceOnRead().collectPreference()
        val deleteDownload by prefs.translationDeleteDownloadOnRead().collectPreference()
        val consensus by prefs.translationConsensus().collectPreference()
        val translateOnly by prefs.translationTranslateOnly().collectPreference()
        val nightly by prefs.translationNightly().collectPreference()
        val dialect by prefs.translationStyleDialect().collectPreference()
        val ocrEngine by prefs.translationOcrEngine().collectPreference()
        val honorifics by prefs.translationStyleHonorifics().collectPreference()
        val deepClean by prefs.translationDeepClean().collectPreference()
        val brain by prefs.translationSeriesBrain().collectPreference()
        val unified by prefs.translationUnifiedSources().collectPreference()
        val dramatic by prefs.translationDramaticStyle().collectPreference()
        val smartScroll by prefs.translationSmartScroll().collectPreference()
        val noKeyEngine = if (TextTranslators.entries.getOrNull(engineIndex) == TextTranslators.MLKIT) TextTranslators.MLKIT else TextTranslators.GOOGLE
        val noKeyLabel = stringResource(
            if (noKeyEngine == TextTranslators.MLKIT) ATMR.strings.pref_no_key_mlkit else ATMR.strings.pref_no_key_google,
        )
        val homeMembers = members.filter { it.isHomeServer && it.enabled }
        val homeState = when {
            homeMembers.isEmpty() -> HomeState.NONE
            active.any { it.isHomeServer } -> HomeState.ON
            homeMembers.any { team.homeKnown(it.baseUrl) == false } -> HomeState.AWAY
            else -> HomeState.UNKNOWN
        }
        val cloudKeys = members.count { !it.isHomeServer }
        val cloudWorking = active.count { !it.isHomeServer }
        // Who translates a chapter now, in the order the app tries them
        val nowWith = when {
            fastMode -> stringResource(ATMR.strings.hubm_via_fast, engineName(eu.kanade.translation.translator.FastTranslatorRules.Engine.entries.getOrNull(fastEngine) ?: eu.kanade.translation.translator.FastTranslatorRules.Engine.AUTO))
            active.any { it.isHomeServer } -> stringResource(ATMR.strings.hubm_via_home)
            active.isNotEmpty() -> stringResource(ATMR.strings.hubm_via_keys, active.size)
            phoneModel -> stringResource(ATMR.strings.hubm_via_phone)
            else -> noKeyLabel
        }
        val googleRest by eu.kanade.translation.translator.GoogleStatus.restingUntil.collectAsState()
        val offlineBubbles by eu.kanade.translation.translator.GoogleStatus.offlineBubbles.collectAsState()
        val googleNote = when {
            noKeyEngine != TextTranslators.GOOGLE || active.isNotEmpty() || phoneModel || fastMode -> null
            googleRest > System.currentTimeMillis() -> stringResource(
                ATMR.strings.hubm_google_resting,
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(googleRest)),
            )
            offlineBubbles > 0 -> stringResource(ATMR.strings.hubm_google_offline_used, offlineBubbles)
            else -> null
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val title = when (page) {
            HubPage.ROOT -> stringResource(ATMR.strings.th_title)
            HubPage.KEYS -> stringResource(ATMR.strings.hubm_keys_title)
            HubPage.PHONE -> stringResource(ATMR.strings.hubm_phone_title)
            HubPage.NO_AI -> stringResource(ATMR.strings.hubm_noai_title)
            HubPage.QUALITY -> stringResource(ATMR.strings.hubm_quality_title)
            HubPage.READER -> stringResource(ATMR.strings.th_section_reader)
            HubPage.AUTO -> stringResource(ATMR.strings.hubm_auto_title)
            HubPage.KNOWLEDGE -> stringResource(ATMR.strings.th_section_knowledge)
        }

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = title,
                    subtitle = if (page == HubPage.ROOT) stringResource(ATMR.strings.th_subtitle) else stringResource(ATMR.strings.th_title),
                    onBack = navigator::pop,
                )
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp + bottom.calculateStartPadding(direction),
                        end = 16.dp + bottom.calculateEndPadding(direction),
                        top = 8.dp,
                        bottom = 24.dp + bottom.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (page) {
                        HubPage.ROOT -> {
                            item {
                                NowCard(
                                    nowWith = nowWith,
                                    note = googleNote,
                                    chain = stringResource(ATMR.strings.hubm_chain, noKeyLabel),
                                    stats = stats,
                                )
                            }
                            item {
                                HubSection(stringResource(ATMR.strings.th_section_languages), MaterialSymbols.Rounded.Language) {
                                    HubChoiceRow(
                                        icon = MaterialSymbols.Rounded.Image,
                                        title = stringResource(ATMR.strings.pref_translate_from),
                                        info = stringResource(ATMR.strings.info_from),
                                        options = TextRecognizerLanguage.entries.map { it.name to it.label },
                                        selected = from,
                                        onSelect = { prefs.translateFromLanguage().set(it) },
                                    )
                                    HubDivider()
                                    HubChoiceRow(
                                        icon = MaterialSymbols.Rounded.Translate,
                                        title = stringResource(ATMR.strings.pref_translate_to),
                                        info = stringResource(ATMR.strings.info_to),
                                        options = TextTranslatorLanguage.entries.map { it.name to it.label },
                                        selected = to,
                                        onSelect = { prefs.translateToLanguage().set(it) },
                                    )
                                }
                            }
                            // Who translates: the laptop, the keys, the phone's model, and the engines with no AI
                            item {
                                HubSection(stringResource(ATMR.strings.hubm_who_section), MaterialSymbols.Rounded.Group) {
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Home,
                                        title = stringResource(ATMR.strings.hubm_home_title),
                                        info = stringResource(ATMR.strings.hubm_home_info),
                                        value = stringResource(
                                            when (homeState) {
                                                HomeState.NONE -> ATMR.strings.hubm_home_off
                                                HomeState.ON -> ATMR.strings.hubm_home_on
                                                HomeState.AWAY -> ATMR.strings.hubm_home_away
                                                HomeState.UNKNOWN -> ATMR.strings.hubm_home_unknown
                                            },
                                        ),
                                        onClick = { navigator.push(eu.kanade.translation.team.HomeServerScreen()) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Key,
                                        title = stringResource(ATMR.strings.hubm_keys_title),
                                        info = stringResource(ATMR.strings.hubm_keys_info),
                                        value = stringResource(ATMR.strings.hubm_keys_value, cloudWorking, cloudKeys),
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.KEYS)) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Mobile,
                                        title = stringResource(ATMR.strings.hubm_phone_title),
                                        info = stringResource(ATMR.strings.hubm_phone_info),
                                        value = stringResource(if (phoneModel) ATMR.strings.hubm_phone_ready else ATMR.strings.hubm_phone_none),
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.PHONE)) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.CloudOff,
                                        title = stringResource(ATMR.strings.hubm_noai_title),
                                        info = stringResource(ATMR.strings.hubm_noai_info),
                                        value = noKeyLabel + if (fastMode) " · " + stringResource(ATMR.strings.fast_switch) else "",
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.NO_AI)) },
                                    )
                                }
                            }
                            // How it translates, in the reader, by itself, and what the app knows
                            item {
                                HubSection(stringResource(ATMR.strings.hubm_how_section), MaterialSymbols.Rounded.Tune) {
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.AutoAwesome,
                                        title = stringResource(ATMR.strings.hubm_quality_title),
                                        info = stringResource(ATMR.strings.hubm_quality_info),
                                        value = stringResource(if (accurate) ATMR.strings.team_mode_accurate else ATMR.strings.team_mode_fast),
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.QUALITY)) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.LocalLibrary,
                                        title = stringResource(ATMR.strings.th_section_reader),
                                        info = stringResource(ATMR.strings.hubm_reader_info),
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.READER)) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Schedule,
                                        title = stringResource(ATMR.strings.hubm_auto_title),
                                        info = stringResource(ATMR.strings.hubm_auto_info),
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.AUTO)) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Psychology,
                                        title = stringResource(ATMR.strings.th_section_knowledge),
                                        info = stringResource(ATMR.strings.hubm_knowledge_info),
                                        value = stats?.let { stringResource(ATMR.strings.th_library_stats, it.lines.toInt(), it.todayPercent()) },
                                        onClick = { navigator.push(TranslationHubScreen(HubPage.KNOWLEDGE)) },
                                    )
                                }
                            }
                        }

                        HubPage.KEYS -> {
                            item {
                                SummaryCard(
                                    working = active.size,
                                    noKeyLabel = noKeyLabel,
                                    lowest = lowest,
                                    stats = stats,
                                    onManage = { navigator.push(TranslationTeamScreen()) },
                                )
                            }
                            item {
                                HubSection(stringResource(ATMR.strings.hubm_keys_title), MaterialSymbols.Rounded.Key) {
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Group,
                                        title = stringResource(ATMR.strings.team_title),
                                        info = stringResource(ATMR.strings.info_team),
                                        value = stringResource(ATMR.strings.th_keys_count, members.size),
                                        onClick = { navigator.push(TranslationTeamScreen()) },
                                    )
                                    HubDivider()
                                    HubNavRow(
                                        icon = MaterialSymbols.Rounded.Tune,
                                        title = stringResource(ATMR.strings.router_title),
                                        info = stringResource(ATMR.strings.router_info),
                                        value = stringResource(ATMR.strings.lane_live) + " › " + stringResource(ATMR.strings.lane_chapter) +
                                            " › " + stringResource(ATMR.strings.lane_chat) + " › " + stringResource(ATMR.strings.lane_background),
                                        onClick = { navigator.push(TranslationTeamScreen()) },
                                    )
                                }
                            }
                            item {
                                HubSection(stringResource(ATMR.strings.hubm_advanced), MaterialSymbols.Rounded.Thermostat) {
                                    HubTextRow(
                                        icon = MaterialSymbols.Rounded.Thermostat,
                                        title = stringResource(ATMR.strings.pref_engine_temperature),
                                        info = stringResource(ATMR.strings.info_temperature),
                                        value = temperature,
                                        shown = temperature.ifBlank { stringResource(ATMR.strings.th_default) },
                                        numeric = true,
                                        onSave = { text ->
                                            val value = text.replace(',', '.').toFloatOrNull()?.coerceIn(0f, 2f)
                                            prefs.translationEngineTemperature().set(value?.toString() ?: "0.4")
                                        },
                                    )
                                    HubDivider()
                                    HubTextRow(
                                        icon = MaterialSymbols.Rounded.DataUsage,
                                        title = stringResource(ATMR.strings.pref_engine_max_output),
                                        info = stringResource(ATMR.strings.info_max_output),
                                        value = maxOutput,
                                        shown = maxOutput.ifBlank { stringResource(ATMR.strings.th_default) },
                                        numeric = true,
                                        onSave = { text ->
                                            val value = text.filter(Char::isDigit).toIntOrNull()?.coerceIn(1000, 65000)
                                            prefs.translationEngineMaxOutputTokens().set(value?.toString() ?: "16000")
                                        },
                                    )
                                }
                            }
                        }

                        // MANHWA on-device AI: translation with no key and no internet
                        HubPage.PHONE -> item {
                            HubSection(stringResource(ATMR.strings.hubm_phone_title), MaterialSymbols.Rounded.Mobile) {
                                eu.kanade.translation.local.LocalAiPanel(Modifier.padding(horizontal = 4.dp))
                            }
                        }

                        HubPage.NO_AI -> {
                            if (googleNote != null) {
                                item {
                                    Text(googleNote, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFFB020))
                                }
                            }
                            item {
                                HubSection(stringResource(ATMR.strings.hubm_noai_title), MaterialSymbols.Rounded.CloudOff) {
                                    HubChoiceRow(
                                        icon = MaterialSymbols.Rounded.CloudOff,
                                        title = stringResource(ATMR.strings.th_no_key_engine),
                                        info = stringResource(ATMR.strings.info_no_key_engine),
                                        options = listOf(
                                            TextTranslators.GOOGLE to stringResource(ATMR.strings.pref_no_key_google),
                                            TextTranslators.MLKIT to stringResource(ATMR.strings.pref_no_key_mlkit),
                                        ),
                                        selected = noKeyEngine,
                                        onSelect = { prefs.translationEngine().set(it.ordinal) },
                                    )
                                }
                            }
                            item { EngineTestCard(to) }
                            // MANHWA fast translation: the OCR straight to machine translation, no AI and no key of the team
                            item {
                                FastSection(
                                    enabled = fastMode,
                                    engine = fastEngine,
                                    deeplKey = deeplKey,
                                    microsoftKey = microsoftKey,
                                    microsoftRegion = microsoftRegion,
                                    googleCloudKey = googleCloudKey,
                                    last = fastLast,
                                    prefs = prefs,
                                )
                            }
                        }

                        HubPage.QUALITY -> item {
                            HubSection(stringResource(ATMR.strings.hubm_quality_title), MaterialSymbols.Rounded.AutoAwesome) {
                                HubChoiceRow(
                                    icon = MaterialSymbols.Rounded.Speed,
                                    title = stringResource(ATMR.strings.team_mode),
                                    info = stringResource(ATMR.strings.info_mode),
                                    options = listOf(
                                        false to stringResource(ATMR.strings.team_mode_fast),
                                        true to stringResource(ATMR.strings.team_mode_accurate),
                                    ),
                                    selected = accurate,
                                    onSelect = { prefs.translationTeamAccurate().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Image,
                                    title = stringResource(ATMR.strings.pref_translation_vision),
                                    info = stringResource(ATMR.strings.info_vision),
                                    checked = vision,
                                    onChange = { prefs.translationVision().set(it) },
                                )
                                HubDivider()
                                HubTextRow(
                                    icon = MaterialSymbols.Rounded.EditNote,
                                    title = stringResource(ATMR.strings.pref_translation_instructions),
                                    info = stringResource(ATMR.strings.info_instructions),
                                    value = instructions,
                                    shown = instructions.ifBlank { stringResource(ATMR.strings.th_not_set) },
                                    multiline = true,
                                    onSave = { prefs.translationInstructions().set(it) },
                                )
                                HubDivider()
                                // MANHWA fingerprint: the reader's own Arabic
                                HubChoiceRow(
                                    icon = MaterialSymbols.Rounded.TextFields,
                                    title = stringResource(ATMR.strings.style_dialect),
                                    info = stringResource(ATMR.strings.info_style_dialect),
                                    options = listOf(
                                        "FUSHA" to stringResource(ATMR.strings.style_dialect_fusha),
                                        "LIGHT" to stringResource(ATMR.strings.style_dialect_light),
                                        "EGYPTIAN" to stringResource(ATMR.strings.style_dialect_egyptian),
                                    ),
                                    selected = dialect,
                                    onSelect = { prefs.translationStyleDialect().set(it) },
                                )
                                HubDivider()
                                HubChoiceRow(
                                    icon = MaterialSymbols.Rounded.TextFields,
                                    title = stringResource(ATMR.strings.style_honorifics),
                                    info = stringResource(ATMR.strings.info_style_honorifics),
                                    options = listOf(
                                        "ARABIC" to stringResource(ATMR.strings.style_honorifics_arabic),
                                        "KEEP" to stringResource(ATMR.strings.style_honorifics_keep),
                                    ),
                                    selected = honorifics,
                                    onSelect = { prefs.translationStyleHonorifics().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Psychology,
                                    title = stringResource(ATMR.strings.pref_series_brain),
                                    info = stringResource(ATMR.strings.info_series_brain),
                                    checked = brain,
                                    onChange = { prefs.translationSeriesBrain().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Psychology,
                                    title = stringResource(ATMR.strings.pref_consensus),
                                    info = stringResource(ATMR.strings.info_consensus),
                                    checked = consensus,
                                    onChange = { prefs.translationConsensus().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.TextFields,
                                    title = stringResource(ATMR.strings.pref_deep_clean),
                                    info = stringResource(ATMR.strings.info_deep_clean),
                                    checked = deepClean,
                                    onChange = { prefs.translationDeepClean().set(it) },
                                )
                                HubDivider()
                                // MANHWA translate only: the fastest, lightest way, whoever translates
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Speed,
                                    title = stringResource(ATMR.strings.pref_translate_only),
                                    info = stringResource(ATMR.strings.info_translate_only),
                                    checked = translateOnly,
                                    onChange = { prefs.translationTranslateOnly().set(it) },
                                )
                            }
                        }

                        HubPage.READER -> item {
                            HubSection(stringResource(ATMR.strings.th_section_reader), MaterialSymbols.Rounded.LocalLibrary) {
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.AutoAwesome,
                                    title = stringResource(ATMR.strings.pref_live_translation),
                                    info = stringResource(ATMR.strings.info_live),
                                    checked = live,
                                    onChange = { prefs.liveTranslation().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Draft,
                                    title = stringResource(ATMR.strings.th_quick_draft),
                                    info = stringResource(ATMR.strings.info_quick_draft),
                                    checked = quickDraft,
                                    onChange = { prefs.translationQuickDraft().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.TouchApp,
                                    title = stringResource(ATMR.strings.pref_reader_button),
                                    info = stringResource(ATMR.strings.info_reader_button),
                                    checked = readerButton,
                                    onChange = { prefs.translationReaderButton().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Speed,
                                    title = stringResource(ATMR.strings.th_smart_scroll),
                                    info = stringResource(ATMR.strings.info_smart_scroll),
                                    checked = smartScroll,
                                    onChange = { prefs.translationSmartScroll().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.AutoAwesome,
                                    title = stringResource(ATMR.strings.th_dramatic),
                                    info = stringResource(ATMR.strings.info_dramatic),
                                    checked = dramatic,
                                    onChange = { prefs.translationDramaticStyle().set(it) },
                                )
                                HubDivider()
                                HubChoiceRow(
                                    icon = MaterialSymbols.Rounded.TextFields,
                                    title = stringResource(ATMR.strings.pref_reader_font),
                                    info = stringResource(ATMR.strings.info_font),
                                    options = TranslationFont.entries.map { it.ordinal to it.label },
                                    selected = font,
                                    onSelect = { prefs.translationFont().set(it) },
                                )
                                HubDivider()
                                // MANHWA dual OCR: what reads the text of the pages
                                HubChoiceRow(
                                    icon = MaterialSymbols.Rounded.TextFields,
                                    title = stringResource(ATMR.strings.ocr_engine),
                                    info = stringResource(ATMR.strings.info_ocr_engine),
                                    options = listOf(
                                        "DUAL" to stringResource(ATMR.strings.ocr_engine_dual),
                                        "PADDLE" to stringResource(ATMR.strings.ocr_engine_paddle),
                                        "MLKIT" to stringResource(ATMR.strings.ocr_engine_mlkit),
                                    ),
                                    selected = ocrEngine,
                                    onSelect = {
                                        prefs.translationOcrEngine().set(it)
                                        if (it != "MLKIT") eu.kanade.translation.ocr.OcrModels.download(context, eu.kanade.translation.ocr.OcrModels.Pack.MAIN)
                                    },
                                )
                                if (ocrEngine != "MLKIT") eu.kanade.translation.ocr.OcrModelsCard(Modifier.padding(horizontal = 4.dp))
                            }
                        }

                        HubPage.AUTO -> item {
                            HubSection(stringResource(ATMR.strings.hubm_auto_title), MaterialSymbols.Rounded.Schedule) {
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.Download,
                                    title = stringResource(ATMR.strings.pref_translate_after_downloading),
                                    info = stringResource(ATMR.strings.info_auto),
                                    checked = auto,
                                    onChange = { prefs.autoTranslateAfterDownload().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.SkipNext,
                                    title = stringResource(ATMR.strings.pref_pretranslate_next),
                                    info = stringResource(ATMR.strings.info_pretranslate),
                                    checked = pretranslate,
                                    onChange = { prefs.translationPretranslateNext().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.SkipNext,
                                    title = stringResource(ATMR.strings.pref_auto_prefetch),
                                    info = stringResource(ATMR.strings.info_auto_prefetch),
                                    checked = autoPrefetch,
                                    onChange = { prefs.translationAutoPrefetch().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.SkipNext,
                                    title = stringResource(ATMR.strings.pref_nightly),
                                    info = stringResource(ATMR.strings.info_nightly),
                                    checked = nightly,
                                    onChange = {
                                        prefs.translationNightly().set(it)
                                        eu.kanade.translation.night.NightTranslateWorker.schedule(context)
                                    },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                    title = stringResource(ATMR.strings.pref_save_on_read),
                                    info = stringResource(ATMR.strings.info_save_on_read),
                                    checked = saveOnRead,
                                    onChange = { prefs.translationSaveOnRead().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                    title = stringResource(ATMR.strings.pref_free_space_on_read),
                                    info = stringResource(ATMR.strings.info_free_space_on_read),
                                    checked = freeSpace,
                                    onChange = { prefs.translationFreeSpaceOnRead().set(it) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                    title = stringResource(ATMR.strings.pref_delete_download_on_read),
                                    info = stringResource(ATMR.strings.info_delete_download_on_read),
                                    checked = deleteDownload,
                                    onChange = { prefs.translationDeleteDownloadOnRead().set(it) },
                                )
                            }
                        }

                        HubPage.KNOWLEDGE -> item {
                            HubSection(stringResource(ATMR.strings.th_section_knowledge), MaterialSymbols.Rounded.Psychology) {
                                HubNavRow(
                                    icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                    title = stringResource(ATMR.strings.col_title),
                                    info = stringResource(ATMR.strings.info_collection),
                                    value = stats?.let { stringResource(ATMR.strings.th_library_stats, it.lines.toInt(), it.todayPercent()) },
                                    onClick = { navigator.push(TranslationCollectionScreen()) },
                                )
                                HubDivider()
                                HubNavRow(
                                    icon = MaterialSymbols.Rounded.Psychology,
                                    title = stringResource(ATMR.strings.lessons_title),
                                    info = stringResource(ATMR.strings.info_lessons),
                                    onClick = { navigator.push(LessonsScreen()) },
                                )
                                HubDivider()
                                HubNavRow(
                                    icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                    title = stringResource(ATMR.strings.tsearch_title),
                                    info = stringResource(ATMR.strings.tsearch_subtitle),
                                    onClick = { navigator.push(eu.kanade.translation.search.TranslationSearchScreen()) },
                                )
                                HubDivider()
                                HubSwitchRow(
                                    icon = MaterialSymbols.Rounded.SkipNext,
                                    title = stringResource(ATMR.strings.pref_unified_sources),
                                    info = stringResource(ATMR.strings.info_unified_sources),
                                    checked = unified,
                                    onChange = { prefs.translationUnifiedSources().set(it) },
                                )
                                HubDivider()
                                HubNavRow(
                                    icon = MaterialSymbols.Rounded.Upload,
                                    title = stringResource(ATMR.strings.th_backup),
                                    info = stringResource(ATMR.strings.info_backup),
                                    value = stringResource(ATMR.strings.th_backup_value),
                                    onClick = { navigator.push(LessonsScreen()) },
                                )
                                HubDivider()
                                HubNavRow(
                                    icon = MaterialSymbols.Rounded.Psychology,
                                    title = stringResource(ATMR.strings.health_title),
                                    info = stringResource(ATMR.strings.health_subtitle),
                                    value = null,
                                    onClick = { navigator.push(eu.kanade.translation.health.HealthDashboardScreen()) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private enum class HomeState { NONE, ON, AWAY, UNKNOWN }

    /** Who translates a chapter now, the order the app tries them in, and the collection. */
    @Composable
    private fun NowCard(nowWith: String, note: String?, chain: String, stats: TranslationCollection.Stats?) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), accent = true, alpha = 0.5f)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubIconTile(MaterialSymbols.Rounded.Translate, size = 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(ATMR.strings.hubm_now),
                        style = MaterialTheme.typography.labelMedium,
                        color = ManhwaInk.muted,
                    )
                    Text(
                        nowWith,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = ManhwaInk.text,
                    )
                }
            }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFFB020))
            }
            Spacer(Modifier.height(12.dp))
            Text(chain, style = MaterialTheme.typography.labelMedium, color = ManhwaInk.muted)
            if (stats != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(ATMR.strings.th_library_stats, stats.lines.toInt(), stats.todayPercent()),
                    style = MaterialTheme.typography.labelMedium,
                    color = ManhwaInk.muted,
                )
            }
        }
    }

    /** The team at a glance: keys working, the lowest quota left, the collection, and the way to the keys. */
    @Composable
    private fun SummaryCard(
        working: Int,
        noKeyLabel: String,
        lowest: Int?,
        stats: TranslationCollection.Stats?,
        onManage: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), accent = true, alpha = 0.5f)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubIconTile(MaterialSymbols.Rounded.Group, size = 52)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (working > 0) {
                            stringResource(ATMR.strings.th_keys_working, working)
                        } else {
                            stringResource(ATMR.strings.th_no_keys, noKeyLabel)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                    if (stats != null) {
                        Text(
                            stringResource(ATMR.strings.th_library_stats, stats.lines.toInt(), stats.todayPercent()),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
            }
            if (lowest != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(ATMR.strings.th_lowest_left, lowest),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(6.dp))
                ManhwaKit.GlowBar(lowest / 100f)
            }
            Spacer(Modifier.height(16.dp))
            ManhwaKit.NeonButton(
                text = stringResource(ATMR.strings.th_manage_keys),
                icon = MaterialSymbols.Rounded.Key,
                modifier = Modifier.fillMaxWidth(),
                onClick = onManage,
            )
        }
    }

    private fun TranslationCollection.Stats.todayPercent(): Int =
        if (todayLookups <= 0) 0 else (todayHits * 100 / todayLookups).toInt()
}

/** MANHWA fast translation (like Google Lens): on or off, the engine tried first, and the reader's own keys. */
@Composable
private fun FastSection(
    enabled: Boolean,
    engine: Int,
    deeplKey: String,
    microsoftKey: String,
    microsoftRegion: String,
    googleCloudKey: String,
    last: eu.kanade.translation.translator.FastTranslatorRules.Engine?,
    prefs: tachiyomi.domain.translation.TranslationPreferences,
) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val engines = eu.kanade.translation.translator.FastTranslatorRules.Engine.entries
    HubSection(stringResource(ATMR.strings.fast_title), MaterialSymbols.Rounded.Speed) {
        HubSwitchRow(
            icon = MaterialSymbols.Rounded.Speed,
            title = stringResource(ATMR.strings.fast_switch),
            info = stringResource(ATMR.strings.fast_info),
            checked = enabled,
            value = last?.let { stringResource(ATMR.strings.fast_last, engineName(it)) },
            onChange = { prefs.translationFastMode().set(it) },
        )
        HubDivider()
        HubChoiceRow(
            icon = MaterialSymbols.Rounded.Translate,
            title = stringResource(ATMR.strings.fast_engine),
            info = stringResource(ATMR.strings.fast_engine_info),
            options = engines.map { it to engineName(it) },
            selected = engines.getOrNull(engine) ?: engines.first(),
            onSelect = { prefs.translationFastEngine().set(it.ordinal) },
        )
        HubDivider()
        FastKeyRow("DeepL", deeplKey, stringResource(ATMR.strings.fast_deepl_info)) { prefs.translationFastDeeplKey().set(it) }
        GetKey { runCatching { uri.openUri("https://www.deepl.com/your-account/keys") } }
        HubDivider()
        FastKeyRow("Microsoft Translator", microsoftKey, stringResource(ATMR.strings.fast_microsoft_info)) { prefs.translationFastMicrosoftKey().set(it) }
        HubTextRow(
            icon = MaterialSymbols.Rounded.Key,
            title = stringResource(ATMR.strings.fast_microsoft_region),
            info = stringResource(ATMR.strings.fast_microsoft_region_info),
            value = microsoftRegion,
            shown = microsoftRegion.ifBlank { stringResource(ATMR.strings.th_default) },
            onSave = { prefs.translationFastMicrosoftRegion().set(it) },
        )
        GetKey { runCatching { uri.openUri("https://portal.azure.com/#create/Microsoft.CognitiveServicesTextTranslation") } }
        HubDivider()
        FastKeyRow("Google Cloud Translation", googleCloudKey, stringResource(ATMR.strings.fast_google_cloud_info)) { prefs.translationFastGoogleCloudKey().set(it) }
        GetKey { runCatching { uri.openUri("https://console.cloud.google.com/apis/credentials") } }
    }
}

/** A key typed in a dialog, shown masked. */
@Composable
private fun FastKeyRow(name: String, key: String, info: String, onSave: (String) -> Unit) {
    HubTextRow(
        icon = MaterialSymbols.Rounded.Key,
        title = name,
        info = info,
        value = key,
        shown = if (key.isBlank()) stringResource(ATMR.strings.fast_no_key) else "•••• " + key.takeLast(4),
        onSave = onSave,
    )
}

@Composable
private fun GetKey(onClick: () -> Unit) {
    eu.kanade.presentation.components.ManhwaKit.GhostButton(
        stringResource(ATMR.strings.fast_get_key),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        icon = MaterialSymbols.Rounded.Key,
        onClick = onClick,
    )
}

@Composable
private fun engineName(engine: eu.kanade.translation.translator.FastTranslatorRules.Engine): String = when (engine) {
    eu.kanade.translation.translator.FastTranslatorRules.Engine.AUTO -> stringResource(ATMR.strings.fast_auto)
    eu.kanade.translation.translator.FastTranslatorRules.Engine.DEEPL -> "DeepL"
    eu.kanade.translation.translator.FastTranslatorRules.Engine.MICROSOFT -> "Microsoft"
    eu.kanade.translation.translator.FastTranslatorRules.Engine.GOOGLE_CLOUD -> "Google Cloud"
    eu.kanade.translation.translator.FastTranslatorRules.Engine.GOOGLE -> "Google Translate"
    eu.kanade.translation.translator.FastTranslatorRules.Engine.MLKIT -> stringResource(ATMR.strings.fast_mlkit)
}


/** A few manhwa lines, in the capitals bubbles are lettered in and in normal case, to try an engine with. */
private val TEST_LINES = listOf(
    "WHAT ARE YOU DOING HERE?!",
    "I told you to stay away from the dungeon.",
    "HE'S AN S-RANK HUNTER... THERE'S NO WAY WE CAN WIN.",
    "Don't worry. I'll protect you.",
)

/**
 * MANHWA: tries Google Translate (internet, no key) or ML Kit (on the phone, no internet once its model is
 * downloaded) on [TEST_LINES] and shows each translation and the time it took, so the reader sees it works.
 */
@Composable
private fun EngineTestCard(to: String) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var running by remember { mutableStateOf<TextTranslators?>(null) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    val target = TextTranslatorLanguage.entries.firstOrNull { it.name == to } ?: TextTranslatorLanguage.entries.first()

    fun start(engine: TextTranslators) {
        if (running != null) return
        running = engine
        result = null
        scope.launch {
            result = withContext(Dispatchers.IO) { runTest(engine, target) }
            running = null
        }
    }

    HubSection(stringResource(ATMR.strings.hubm_test_title), MaterialSymbols.Rounded.Speed) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(ATMR.strings.hubm_test_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
            ManhwaKit.NeonButton(
                text = stringResource(if (running == TextTranslators.GOOGLE) ATMR.strings.hubm_test_running else ATMR.strings.hubm_test_google),
                icon = MaterialSymbols.Rounded.Translate,
                modifier = Modifier.fillMaxWidth(),
                onClick = { start(TextTranslators.GOOGLE) },
            )
            // Google Translate itself, in the browser or its app, with a line already typed: to see it answer
            ManhwaKit.GhostButton(
                stringResource(ATMR.strings.hubm_open_google),
                modifier = Modifier.fillMaxWidth(),
                icon = MaterialSymbols.Rounded.Translate,
                onClick = {
                    runCatching {
                        uri.openUri(
                            "https://translate.google.com/?sl=auto&tl=" + target.code + "&op=translate&text=" +
                                java.net.URLEncoder.encode(TEST_LINES.first(), "utf-8"),
                        )
                    }
                },
            )
            ManhwaKit.GhostButton(
                stringResource(if (running == TextTranslators.MLKIT) ATMR.strings.hubm_test_running else ATMR.strings.hubm_test_mlkit),
                modifier = Modifier.fillMaxWidth(),
                icon = MaterialSymbols.Rounded.CloudOff,
                onClick = { start(TextTranslators.MLKIT) },
            )
            result?.let { r ->
                Text(
                    if (r.error == null) {
                        stringResource(ATMR.strings.hubm_test_ok, r.lines.count { it.second.isNotBlank() }, TEST_LINES.size, r.millis.toInt())
                    } else {
                        stringResource(ATMR.strings.hubm_test_fail, r.error)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (r.error == null) Color(0xFF3DDC97) else Color(0xFFFF6B6B),
                )
                if (r.busy) {
                    Text(stringResource(ATMR.strings.hubm_test_busy), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text)
                }
                r.lines.forEach { (source, translated) ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(14.dp), alpha = 0.35f)
                            .padding(10.dp),
                    ) {
                        Text(source, style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                        Text(translated.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text)
                    }
                }
            }
        }
    }
}

private class TestResult(val lines: List<Pair<String, String>>, val millis: Long, val error: String?, val busy: Boolean = false)

private suspend fun runTest(engine: TextTranslators, target: TextTranslatorLanguage): TestResult {
    val started = System.currentTimeMillis()
    val done = mutableListOf<Pair<String, String>>()
    return try {
        when (engine) {
            TextTranslators.MLKIT -> {
                val code = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(target.code)
                    ?: return TestResult(emptyList(), 0, "ML Kit: ${target.label} not supported")
                val client = com.google.mlkit.nl.translate.Translation.getClient(
                    com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                        .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
                        .setTargetLanguage(code)
                        .build(),
                )
                try {
                    // The model is downloaded once (on any network), then it translates with none
                    com.google.android.gms.tasks.Tasks.await(
                        client.downloadModelIfNeeded(com.google.mlkit.common.model.DownloadConditions.Builder().build()),
                        3,
                        java.util.concurrent.TimeUnit.MINUTES,
                    )
                    val translateStart = System.currentTimeMillis()
                    TEST_LINES.forEach { line ->
                        done += line to com.google.android.gms.tasks.Tasks.await(client.translate(line), 20, java.util.concurrent.TimeUnit.SECONDS).orEmpty()
                    }
                    return TestResult(done, System.currentTimeMillis() - translateStart, null)
                } finally {
                    client.close()
                }
            }
            else -> {
                val google = eu.kanade.translation.translator.GoogleTranslator(TextRecognizerLanguage.ENGLISH, target)
                TEST_LINES.forEach { line -> done += line to google.translateText(line, ignoreRest = true) }
            }
        }
        TestResult(done, System.currentTimeMillis() - started, null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        TestResult(done, System.currentTimeMillis() - started, e.message ?: e.javaClass.simpleName, busy = eu.kanade.translation.translator.GoogleRules.isTooMany(e))
    }
}
