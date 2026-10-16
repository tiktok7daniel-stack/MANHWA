package eu.kanade.translation.health

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.settings.HubIconTile
import eu.kanade.translation.team.ModelHealthManager
import eu.kanade.translation.team.TeamStore
import eu.kanade.translation.team.TranslationTeamScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Key
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Verified
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val Green = Color(0xFF3DDC97)
private val Amber = Color(0xFFFFB020)
private val Crimson = Color(0xFFFF4D6A)

/**
 * MANHWA app health: one glass screen for everything that keeps itself alive: the keys (working, repaired,
 * out and why, with "check and fix all"), the sources hidden from home (with "try now"), the translation
 * library and the space freed after reading, and the library organizer (with "organize now").
 */
class HealthDashboardScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val graph = remember { context.appGraph }
        val store = graph.teamStore
        val members by store.members.collectAsState()
        val keyHealth by store.keyHealth.all.collectAsState()
        val sources by graph.sourceHealthManager.entries.collectAsState()
        val organizerRunning by graph.libraryOrganizer.running.collectAsState()
        val organizerResult by graph.libraryOrganizer.lastResult.collectAsState()
        var fixing by remember { mutableStateOf(false) }
        var fixSummary by remember { mutableStateOf<TeamStore.CheckSummary?>(null) }
        var rechecking by remember { mutableStateOf(false) }
        val stats by produceState<eu.kanade.translation.collection.TranslationCollection.Stats?>(null) {
            value = withContext(Dispatchers.IO) { runCatching { graph.translationCollection.stats() }.getOrNull() }
        }
        val freed = remember { graph.translationPreferences.translationFreedBytes().get() }
        val lastFull = remember(organizerResult) { graph.libraryPreferences.organizerLastFull.get() }
        val hidden = sources.filterValues { it.hidden }
        // Source names are read off the main thread
        val names by produceState(emptyMap<Long, String>(), hidden.keys) {
            value = withContext(Dispatchers.IO) { hidden.keys.associateWith { id -> graph.sourceManager.get(id)?.name ?: "$id" } }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.health_title),
                    subtitle = stringResource(ATMR.strings.health_subtitle),
                    onBack = navigator::pop,
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Keys
                    item {
                        val counts = members.filter { it.key.isNotBlank() }.groupingBy { keyHealth[it.id]?.statusEnum ?: ModelHealthManager.Status.UNKNOWN }.eachCount()
                        val working = (counts[ModelHealthManager.Status.ACTIVE] ?: 0) + (counts[ModelHealthManager.Status.DEGRADED] ?: 0)
                        val out = counts[ModelHealthManager.Status.DISABLED] ?: 0
                        Card(MaterialSymbols.Rounded.Key, stringResource(ATMR.strings.health_keys), if (out > 0) Amber else Green) {
                            Line(stringResource(ATMR.strings.health_keys_line, working, members.size, out))
                            members.filter { keyHealth[it.id]?.statusEnum == ModelHealthManager.Status.DISABLED }.forEach { m ->
                                Line("${m.displayName} ${m.key.takeLast(4)} · ${keyHealth[m.id]?.disabledReason.orEmpty().take(90)}", Crimson)
                            }
                            fixSummary?.let { s -> Line(stringResource(ATMR.strings.keys_fix_done, s.working, s.total, s.fixed.size), if (s.working > 0) Green else Crimson) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManhwaKit.NeonButton(
                                    stringResource(if (fixing) ATMR.strings.hub_checking else ATMR.strings.keys_fix_all),
                                    icon = MaterialSymbols.Rounded.Verified,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (!fixing) {
                                        fixing = true
                                        scope.launch {
                                            fixSummary = runCatching { store.checkAll() }.getOrNull()
                                            fixing = false
                                        }
                                    }
                                }
                                ManhwaKit.GhostButton(stringResource(ATMR.strings.team_title), icon = MaterialSymbols.Rounded.Key) { navigator.push(TranslationTeamScreen()) }
                            }
                        }
                    }
                    // Sources hidden from home
                    item {
                        Card(MaterialSymbols.Rounded.Public, stringResource(ATMR.strings.health_sources), if (hidden.isEmpty()) Green else Amber) {
                            if (hidden.isEmpty()) {
                                Line(stringResource(ATMR.strings.health_sources_ok))
                            } else {
                                hidden.forEach { (id, e) ->
                                    val name = names[id] ?: "$id"
                                    Line("$name · ${e.reason.orEmpty().take(80)}", Amber)
                                }
                                ManhwaKit.NeonButton(
                                    stringResource(if (rechecking) ATMR.strings.hub_checking else ATMR.strings.source_hidden_check),
                                    icon = MaterialSymbols.Rounded.Refresh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (!rechecking) {
                                        rechecking = true
                                        scope.launch {
                                            runCatching { graph.sourceHealthManager.recheckAll() }
                                            rechecking = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Translation library and space
                    item {
                        Card(MaterialSymbols.Rounded.CollectionsBookmark, stringResource(ATMR.strings.health_library), Green) {
                            stats?.let { s ->
                                Line(stringResource(ATMR.strings.health_library_line, s.lines.toInt(), s.templates.toInt(), Formatter.formatShortFileSize(context, s.bytes)))
                            }
                            Line(stringResource(ATMR.strings.health_freed, Formatter.formatShortFileSize(context, freed)))
                        }
                    }
                    // Organizer
                    item {
                        Card(MaterialSymbols.Rounded.Autorenew, stringResource(ATMR.strings.health_organizer), Green) {
                            Line(
                                if (lastFull > 0) {
                                    stringResource(
                                        ATMR.strings.health_organizer_last,
                                        DateUtils.getRelativeTimeSpanString(lastFull, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                    )
                                } else {
                                    stringResource(ATMR.strings.health_organizer_never)
                                },
                            )
                            organizerResult?.let { r -> Line(stringResource(ATMR.strings.lib_organize_done, r.changed, r.created, r.removed)) }
                            ManhwaKit.NeonButton(
                                stringResource(if (organizerRunning) ATMR.strings.hub_checking else ATMR.strings.lib_organize_now),
                                icon = MaterialSymbols.Rounded.Autorenew,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (!organizerRunning) scope.launch { runCatching { graph.libraryOrganizer.reorganize() } }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Card(icon: ImageVector, title: String, accent: Color, content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), alpha = 0.5f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubIconTile(icon, size = 40)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(10.dp)
                        .background(accent, androidx.compose.foundation.shape.CircleShape),
                )
            }
            content()
        }
    }

    @Composable
    private fun Line(text: String, color: Color = Color.White.copy(alpha = 0.75f)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
