package eu.kanade.translation.team

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.translator.AiTranslator
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreference
import tachiyomi.presentation.core.util.plus

/**
 * MANHWA: the translation team: any number of API keys. Each key is checked when it is added (does it work and
 * why not, speed, model, images) and gets a strength; the team gives every key work by its strength, the
 * strongest one leads.
 */
class TranslationTeamScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val store = remember { context.appGraph.teamStore }
        val prefs = remember { context.appGraph.translationPreferences }
        val enabled by prefs.translationTeamEnabled().collectPreference()
        val members by store.members.collectAsState()
        val analysis by store.analysis.collectAsState()
        val health by store.health.collectAsState()
        val analyzing by store.analyzingIds.collectAsState()
        // Read so the meters move while the screen is open
        val usageState by store.usage.usage.collectAsState()
        var adding by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<TeamStore.Member?>(null) }

        // Recomputed whenever something changes: strength, share and the lead
        val shares = remember(members, analysis, health) { store.shares() }
        val leadId = remember(members, analysis, health) { store.lead()?.id }

        fun analyze(member: TeamStore.Member) {
            scope.launch { store.analyze(member) }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(ATMR.strings.team_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(ATMR.strings.team_enabled), fontWeight = FontWeight.Bold)
                                Text(stringResource(ATMR.strings.team_enabled_summary), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = enabled, onCheckedChange = { prefs.translationTeamEnabled().set(it) })
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { adding = true }) { Text(stringResource(ATMR.strings.team_add)) }
                        OutlinedButton(onClick = { members.forEach(::analyze) }, enabled = members.isNotEmpty()) {
                            Text(stringResource(ATMR.strings.team_analyze_all))
                        }
                    }
                }
                if (members.isEmpty()) {
                    item {
                        Text(
                            stringResource(ATMR.strings.team_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(members, key = { it.id }) { member ->
                    MemberCard(
                        member = member,
                        analysis = analysis[member.id],
                        health = health[member.id],
                        analyzing = member.id in analyzing,
                        strength = store.strength(member.id),
                        share = shares[member.id],
                        lead = member.id == leadId,
                        level = remember(member, usageState) { store.usage.level(member) },
                        onEnabled = { store.setEnabled(member.id, it) },
                        onAnalyze = { analyze(member) },
                        onEdit = { editing = member },
                        onDelete = { store.remove(member.id) },
                    )
                }
                item {
                    Text(
                        stringResource(ATMR.strings.team_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (adding || editing != null) {
            KeyDialog(
                member = editing,
                onDismiss = {
                    adding = false
                    editing = null
                },
                onSave = { key, provider, model, dailyLimit ->
                    val current = editing
                    val saved = if (current == null) {
                        store.add(key, provider, model).copy(dailyLimit = dailyLimit).also(store::update)
                    } else {
                        current.copy(key = key.trim(), provider = provider.name, model = model.trim(), dailyLimit = dailyLimit).also(store::update)
                    }
                    adding = false
                    editing = null
                    // Every key is checked as soon as it is added or changed
                    analyze(saved)
                },
            )
        }
    }

    @Composable
    private fun MemberCard(
        member: TeamStore.Member,
        analysis: TeamStore.Analysis?,
        health: TeamStore.Health?,
        analyzing: Boolean,
        strength: Int,
        share: Int?,
        lead: Boolean,
        level: UsageMeter.Level,
        onEnabled: (Boolean) -> Unit,
        onAnalyze: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${providerLabel(member.providerEnum)} · ${maskKey(member.key)}",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (analysis?.model?.takeIf { it.isNotBlank() } ?: member.model.ifBlank { member.providerEnum.defaultModel }),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(checked = member.enabled, onCheckedChange = onEnabled)
            }
            // Status: working, resting, or not working and why
            val (status, statusColor) = when {
                analyzing -> stringResource(ATMR.strings.team_status_checking) to colors.onSurfaceVariant
                !member.enabled -> stringResource(ATMR.strings.team_status_off) to colors.onSurfaceVariant
                health?.resting == true -> stringResource(ATMR.strings.team_status_resting) to Amber
                analysis == null -> stringResource(ATMR.strings.team_status_unchecked) to colors.onSurfaceVariant
                analysis.ok -> stringResource(ATMR.strings.team_status_ok, analysis.latencyMs) to Green
                analysis.limited -> stringResource(ATMR.strings.team_status_limited) to Amber
                else -> stringResource(ATMR.strings.team_status_broken, analysis.error.orEmpty()) to colors.error
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (analyzing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("  ")
                }
                Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (member.enabled && analysis?.ok != false) {
                // Strength, role and share of the work
                Text(
                    stringResource(ATMR.strings.team_strength, strength),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    progress = { strength / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round,
                )
                Text(
                    buildString {
                        append(stringResource(if (lead) ATMR.strings.team_role_lead else ATMR.strings.team_role_translator))
                        if (share != null) append(" · ").append(stringResource(ATMR.strings.team_share, share))
                        if (analysis?.vision == true) append(" · ").append(stringResource(ATMR.strings.team_vision))
                        if (health != null && health.done + health.failed > 0) {
                            append(" · ").append(stringResource(ATMR.strings.team_record, health.done, health.failed))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            // How much is left: 100% → 0%, green, then amber, then red
            val levelColor = when {
                level.percent > 50 -> Green
                level.percent >= 20 -> Amber
                else -> colors.error
            }
            Text(
                stringResource(
                    if (level.real) ATMR.strings.team_left_real else ATMR.strings.team_left_estimate,
                    level.percent,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = levelColor,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { level.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = levelColor,
                trackColor = levelColor.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
            )
            Text(
                stringResource(ATMR.strings.team_today, level.requests, level.errors),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAnalyze, enabled = !analyzing) { Text(stringResource(ATMR.strings.team_analyze)) }
                TextButton(onClick = onEdit) { Text(stringResource(ATMR.strings.team_edit)) }
                TextButton(onClick = onDelete) { Text(stringResource(ATMR.strings.team_delete), color = colors.error) }
            }
        }
    }

    @Composable
    private fun KeyDialog(
        member: TeamStore.Member?,
        onDismiss: () -> Unit,
        onSave: (String, AiTranslator.Provider, String, Int) -> Unit,
    ) {
        var key by remember { mutableStateOf(member?.key.orEmpty()) }
        var dailyLimit by remember { mutableStateOf(member?.dailyLimit?.takeIf { it > 0 }?.toString().orEmpty()) }
        var model by remember { mutableStateOf(member?.model.orEmpty()) }
        var chosen by remember { mutableStateOf(member?.providerEnum) }
        // Told from the key as it is typed, until the reader picks one
        val provider = chosen ?: TeamStore.detectProvider(key)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(if (member == null) ATMR.strings.team_add else ATMR.strings.team_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(ATMR.strings.team_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiTranslator.Provider.entries.forEach { p ->
                            FilterChip(selected = provider == p, onClick = { chosen = p }, label = { Text(providerLabel(p)) })
                        }
                    }
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringResource(ATMR.strings.team_model)) },
                        placeholder = { Text(provider.defaultModel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Gemini does not say how much is left: the meter counts against this
                    if (provider == AiTranslator.Provider.GEMINI) {
                        OutlinedTextField(
                            value = dailyLimit,
                            onValueChange = { dailyLimit = it.filter(Char::isDigit).take(6) },
                            label = { Text(stringResource(ATMR.strings.team_daily_limit)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(key, provider, model, dailyLimit.toIntOrNull() ?: 0) }, enabled = key.isNotBlank()) {
                    Text(stringResource(ATMR.strings.team_save))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(ATMR.strings.col_cancel)) } },
        )
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }

    private fun providerLabel(provider: AiTranslator.Provider) = when (provider) {
        AiTranslator.Provider.GEMINI -> "Gemini"
        AiTranslator.Provider.OPENROUTER -> "OpenRouter"
        AiTranslator.Provider.OPENAI_COMPATIBLE -> "OpenAI"
        AiTranslator.Provider.CLAUDE -> "Claude"
    }

    /** Only the start and the end of a key are shown. */
    private fun maskKey(key: String) = if (key.length <= 10) "••••" else key.take(6) + "…" + key.takeLast(4)

    private companion object {
        val Green = Color(0xFF2E9E5B)
        val Amber = Color(0xFFE0A100)
    }
}
