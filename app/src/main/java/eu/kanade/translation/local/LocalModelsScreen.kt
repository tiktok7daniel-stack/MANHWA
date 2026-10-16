package eu.kanade.translation.local

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.orchestra.OrchestraRules
import eu.kanade.translation.orchestra.OrchestraStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreference
import java.util.Locale

/**
 * MANHWA model shelf and the hybrid orchestra: the strongest phone models to download (what suits this phone,
 * how strong each is, which plays the main part and which judges), the orchestra switch and what it did today.
 */
class LocalModelsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val prefs = remember { context.appGraph.translationPreferences }
        val orchestra by prefs.translationOrchestra().collectPreference()
        val mainPath by prefs.translationLocalModelPath().collectPreference()
        val judgePath by prefs.translationLocalJudgePath().collectPreference()
        var token by remember { mutableStateOf(prefs.translationHfToken().get()) }
        var wifiOnly by remember { mutableStateOf(true) }
        var tick by remember { mutableIntStateOf(0) }
        var message by remember { mutableStateOf<String?>(null) }
        val ram = remember { ModelCatalog.ramGb(context) }
        val arabic = Locale.getDefault().language == "ar"
        // Download progress moves on by itself
        val inactive by prefs.translationInactiveModels().collectPreference()
        val size by prefs.translationEnsembleSize().collectPreference()
        // Everything that asks the system (downloads, memory, files) is read off the main thread: every second
        // while a model downloads, every 10 seconds otherwise
        var statuses by remember { mutableStateOf<Map<String, ModelCatalog.Status>>(emptyMap()) }
        var lineupState by remember { mutableStateOf<PhoneEnsemble.Lineup?>(null) }
        var stats by remember { mutableStateOf(OrchestraStore.Stats()) }
        var keys by remember { mutableIntStateOf(0) }
        LaunchedEffect(tick, mainPath, judgePath, inactive, size) {
            while (true) {
                withContext(Dispatchers.IO) {
                    statuses = ModelCatalog.models.associate { it.repo to ModelCatalog.status(context, it) }
                    lineupState = PhoneEnsemble.lineup(context)
                    stats = OrchestraStore.stats(context)
                    keys = context.appGraph.teamStore.active().size
                }
                delay(if (statuses.values.any { it is ModelCatalog.Status.Downloading }) 1_000 else 10_000)
            }
        }
        // A plain value, so the checks below hold for it
        val lineup = lineupState
        val gatedText = stringResource(ATMR.strings.models_gated)
        val missingText = stringResource(ATMR.strings.models_not_found)
        val bottom = WindowInsets.navigationBars.asPaddingValues()
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.models_title), subtitle = stringResource(ATMR.strings.models_subtitle), onBack = navigator::pop)
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The orchestra
                    item {
                        Column(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.5f).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(ATMR.strings.orchestra_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = orchestra, onCheckedChange = { prefs.translationOrchestra().set(it) })
                            }
                            Text(stringResource(ATMR.strings.orchestra_info), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                            Text(
                                stringResource(ATMR.strings.orchestra_today, stats.localLines, stats.teamLines, OrchestraRules.callsSaved(stats.localLines), stats.judged),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF3DDC97),
                            )
                            // The whole phone team, one line per model: the main one and its helpers by strength
                            if (lineup == null) {
                                Text(stringResource(ATMR.strings.ensemble_none), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB020))
                            } else {
                                Text(stringResource(ATMR.strings.ensemble_header, lineup.playing, keys), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.White)
                                lineup.members.forEach { m -> RoleLine(m.name, m.role) }
                                if (!lineup.ensemble) Text(stringResource(ATMR.strings.ensemble_add_more), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f))
                            }
                            // How many phone models play: 1, 2, 3 or all of the active ones
                            Text(stringResource(ATMR.strings.models_count_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 2, 3, 0).forEach { n ->
                                    androidx.compose.material3.FilterChip(
                                        selected = size == n,
                                        onClick = { prefs.translationEnsembleSize().set(n) },
                                        label = { Text(if (n == 0) stringResource(ATMR.strings.models_count_all) else n.toString(), fontWeight = FontWeight.Bold) },
                                    )
                                }
                            }
                            Text(stringResource(ATMR.strings.models_count_info), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                            Text(stringResource(ATMR.strings.models_ram, ram), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                    // The reader's own token, for gated models they accepted
                    item {
                        Column(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.45f).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(ATMR.strings.models_token_info), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                            OutlinedTextField(
                                value = token,
                                onValueChange = {
                                    token = it.trim()
                                    prefs.translationHfToken().set(it.trim())
                                },
                                placeholder = { Text("hf_…") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(ATMR.strings.models_wifi_only), style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                            }
                            ManhwaKit.GhostButton(stringResource(ATMR.strings.models_token_page), icon = MaterialSymbols.Rounded.OpenInNew, modifier = Modifier.fillMaxWidth()) {
                                eu.kanade.translation.team.openInBrowser(context, "https://huggingface.co/settings/tokens")
                            }
                            message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFB020)) }
                        }
                    }
                    items(ModelCatalog.models, key = { it.repo }) { model ->
                        val status = statuses[model.repo] ?: ModelCatalog.Status.NotDownloaded
                        val ready = (status as? ModelCatalog.Status.Ready)?.file
                        val isMain = ready != null && ready.absolutePath == mainPath
                        val isJudge = ready != null && ready.absolutePath == judgePath
                        val active = ready != null && (isMain || ready.absolutePath !in inactive)
                        val role = ready?.let { f -> lineup?.members?.firstOrNull { it.file.absolutePath == f.absolutePath }?.role }
                        Column(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), accent = isMain, alpha = 0.5f).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                                StrengthBar(model.strength)
                            }
                            Text(if (arabic) model.noteAr else model.noteEn, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                            Text(
                                stringResource(ATMR.strings.models_meta, String.format(Locale.US, "%.1f", model.sizeGb), model.minRamGb) +
                                    if (!CatalogRules.fits(model, ram)) " · " + stringResource(ATMR.strings.models_too_big) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (CatalogRules.fits(model, ram)) Color.White.copy(alpha = 0.55f) else Color(0xFFFFB020),
                            )
                            // A model on the phone plays (switch on) or rests; the main one always plays
                            if (ready != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        if (role != null) RoleLine(null, role) else Text(stringResource(if (active) ATMR.strings.ensemble_over_count else ATMR.strings.ensemble_resting), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Switch(
                                        checked = active,
                                        enabled = !isMain,
                                        onCheckedChange = { on ->
                                            val path = ready.absolutePath
                                            prefs.translationInactiveModels().set(if (on) inactive - path else inactive + path)
                                        },
                                    )
                                }
                            }
                            when (status) {
                                is ModelCatalog.Status.Downloading -> {
                                    Text(stringResource(ATMR.strings.models_downloading, status.percent), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                    Progress(status.percent / 100f)
                                }
                                is ModelCatalog.Status.Failed -> Text(status.reason + " · " + stringResource(ATMR.strings.models_failed_hint), color = Color(0xFFFF4D6A), style = MaterialTheme.typography.labelSmall)
                                else -> Unit
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when {
                                    ready != null && !isMain -> ManhwaKit.NeonButton(stringResource(ATMR.strings.models_use_main), icon = MaterialSymbols.Rounded.Verified, modifier = Modifier.weight(1f)) {
                                        LocalAiEngine.use(context, ready)
                                    }
                                    ready == null && status !is ModelCatalog.Status.Downloading -> ManhwaKit.NeonButton(stringResource(ATMR.strings.models_download), icon = MaterialSymbols.Rounded.Download, modifier = Modifier.weight(1f)) {
                                        scope.launch {
                                            message = runCatching { ModelCatalog.download(context, model, wifiOnly) }.fold(
                                                { null },
                                                {
                                                    when (it.message) {
                                                        ModelCatalog.GATED -> gatedText
                                                        ModelCatalog.NOT_FOUND -> missingText
                                                        else -> it.message
                                                    }
                                                },
                                            )
                                            tick++
                                        }
                                    }
                                    else -> Unit
                                }
                                if (ready != null && active && !isMain && !isJudge) {
                                    ManhwaKit.GhostButton(stringResource(ATMR.strings.models_use_judge), modifier = Modifier.weight(1f)) {
                                        prefs.translationLocalJudgePath().set(ready.absolutePath)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManhwaKit.GhostButton(stringResource(ATMR.strings.models_page), icon = MaterialSymbols.Rounded.OpenInNew, modifier = Modifier.weight(1f)) {
                                    eu.kanade.translation.team.openInBrowser(context, model.page)
                                }
                                if (status !is ModelCatalog.Status.NotDownloaded) {
                                    ManhwaKit.GhostButton(stringResource(ATMR.strings.models_delete), icon = MaterialSymbols.Rounded.Delete, modifier = Modifier.weight(1f)) {
                                        ModelCatalog.delete(context, model)
                                        tick++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** "Gemma 3n E4B · main" style line in the colour of the role. */
    @Composable
    private fun RoleLine(name: String?, role: EnsembleRules.Role) {
        val (text, color) = when (role) {
            EnsembleRules.Role.MAIN -> stringResource(ATMR.strings.ensemble_role_main) to Color(0xFF3DDC97)
            EnsembleRules.Role.DRAFTS -> stringResource(ATMR.strings.ensemble_role_drafts) to Color(0xFFFFB020)
            EnsembleRules.Role.JUDGES -> stringResource(ATMR.strings.ensemble_role_judges) to Color(0xFF4FD8FF)
            EnsembleRules.Role.DRAFTS_AND_JUDGES -> stringResource(ATMR.strings.ensemble_role_both) to Color(0xFFFFB020)
            EnsembleRules.Role.NO_ROOM -> stringResource(ATMR.strings.ensemble_role_no_room) to Color.White.copy(alpha = 0.55f)
        }
        Text(
            if (name != null) "$name · $text" else text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }

    @Composable
    private fun StrengthBar(strength: Int) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier
                        .width(10.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < strength) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f)),
                )
            }
        }
    }

    @Composable
    private fun Progress(fraction: Float) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(2.dp))
    }
}
