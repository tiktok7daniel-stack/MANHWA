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
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
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
class TranslationHubScreen : Screen() {

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

        LaunchedEffect(Unit) {
            stats = withContext(Dispatchers.IO) { collection.stats() }
            // Keys never checked are checked now, so every key shows how it is doing
            team.analyzeUnchecked()
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
        val bottom = WindowInsets.navigationBars.asPaddingValues()
        val direction = LocalLayoutDirection.current

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.th_title),
                    subtitle = stringResource(ATMR.strings.th_subtitle),
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
                            HubDivider()
                            HubSwitchRow(
                                icon = MaterialSymbols.Rounded.Download,
                                title = stringResource(ATMR.strings.pref_translate_after_downloading),
                                info = stringResource(ATMR.strings.info_auto),
                                checked = auto,
                                onChange = { prefs.autoTranslateAfterDownload().set(it) },
                            )
                        }
                    }

                    item {
                        HubSection(stringResource(ATMR.strings.th_section_ai), MaterialSymbols.Rounded.AutoAwesome) {
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
                            HubDivider()
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
                            HubDivider()
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

                    item {
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
                            HubSwitchRow(
                                icon = MaterialSymbols.Rounded.SkipNext,
                                title = stringResource(ATMR.strings.pref_pretranslate_next),
                                info = stringResource(ATMR.strings.info_pretranslate),
                                checked = pretranslate,
                                onChange = { prefs.translationPretranslateNext().set(it) },
                            )
                            HubDivider()
                            // MANHWA on-device AI: translation with no key and no internet
                            eu.kanade.translation.local.LocalAiPanel(Modifier.padding(horizontal = 4.dp))
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
                                icon = MaterialSymbols.Rounded.TextFields,
                                title = stringResource(ATMR.strings.pref_deep_clean),
                                info = stringResource(ATMR.strings.info_deep_clean),
                                checked = deepClean,
                                onChange = { prefs.translationDeepClean().set(it) },
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
                                icon = MaterialSymbols.Rounded.SkipNext,
                                title = stringResource(ATMR.strings.pref_unified_sources),
                                info = stringResource(ATMR.strings.info_unified_sources),
                                checked = unified,
                                onChange = { prefs.translationUnifiedSources().set(it) },
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
                                icon = MaterialSymbols.Rounded.Psychology,
                                title = stringResource(ATMR.strings.pref_consensus),
                                info = stringResource(ATMR.strings.info_consensus),
                                checked = consensus,
                                onChange = { prefs.translationConsensus().set(it) },
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

                    item {
                        HubSection(stringResource(ATMR.strings.th_section_knowledge), MaterialSymbols.Rounded.Psychology) {
                            HubNavRow(
                                icon = MaterialSymbols.Rounded.Psychology,
                                title = stringResource(ATMR.strings.health_title),
                                info = stringResource(ATMR.strings.health_subtitle),
                                value = null,
                                onClick = { navigator.push(eu.kanade.translation.health.HealthDashboardScreen()) },
                            )
                            HubDivider()
                            HubNavRow(
                                icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                title = stringResource(ATMR.strings.tsearch_title),
                                info = stringResource(ATMR.strings.tsearch_subtitle),
                                onClick = { navigator.push(eu.kanade.translation.search.TranslationSearchScreen()) },
                            )
                            HubDivider()
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
                                icon = MaterialSymbols.Rounded.Upload,
                                title = stringResource(ATMR.strings.th_backup),
                                info = stringResource(ATMR.strings.info_backup),
                                value = stringResource(ATMR.strings.th_backup_value),
                                onClick = { navigator.push(LessonsScreen()) },
                            )
                        }
                    }
                }
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

