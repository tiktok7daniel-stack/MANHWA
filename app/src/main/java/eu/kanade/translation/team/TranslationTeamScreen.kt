package eu.kanade.translation.team

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.settings.HubIconTile
import eu.kanade.translation.settings.InfoButton
import eu.kanade.translation.translator.AiTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.HourglassEmpty
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.Key
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Speed
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.Verified
import mihon.icons.materialsymbols.roundedfilled.Bolt
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import mihon.icons.materialsymbols.roundedfilled.Star
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPreference

/**
 * MANHWA: the translation team: any number of API keys, and the AI of the whole app. Each key is checked when it
 * is added or changed and every time this screen opens (does it work and why not, speed, model, images) and gets a
 * strength; chapters are shared by strength, the strongest key leads.
 */
class TranslationTeamScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val store = remember { context.appGraph.teamStore }
        val prefs = remember { context.appGraph.translationPreferences }
        val accurate by prefs.translationTeamAccurate().collectPreference()
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
        val working = remember(members, analysis, health, usageState) { store.active().size }

        // Keys never checked (or changed since) are checked as soon as the screen opens
        LaunchedEffect(Unit) { store.analyzeUnchecked() }

        fun analyze(member: TeamStore.Member) {
            // analyze() runs on the IO dispatcher by itself
            scope.launch { store.analyze(member) }
        }

        val bottom = WindowInsets.navigationBars.asPaddingValues()

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.team_title),
                    subtitle = stringResource(ATMR.strings.team_subtitle),
                    onBack = navigator::pop,
                ) {
                    InfoButton(stringResource(ATMR.strings.team_title), stringResource(ATMR.strings.info_team))
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.5f)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HubIconTile(MaterialSymbols.Rounded.Key, size = 48)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    stringResource(ATMR.strings.team_summary, working, members.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // Fast: scout + translators + collection + automatic check. Accurate: the lead also edits.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(ATMR.strings.team_mode),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.weight(1f),
                                )
                                InfoButton(stringResource(ATMR.strings.team_mode), stringResource(ATMR.strings.info_mode))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ModeChip(MaterialSymbols.RoundedFilled.Bolt, stringResource(ATMR.strings.team_mode_fast), !accurate) {
                                    prefs.translationTeamAccurate().set(false)
                                }
                                ModeChip(MaterialSymbols.Rounded.Verified, stringResource(ATMR.strings.team_mode_accurate), accurate) {
                                    prefs.translationTeamAccurate().set(true)
                                }
                            }
                            Text(
                                stringResource(if (accurate) ATMR.strings.team_mode_accurate_summary else ATMR.strings.team_mode_fast_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ManhwaKit.NeonButton(
                                    text = stringResource(ATMR.strings.team_add),
                                    icon = MaterialSymbols.Rounded.Add,
                                    modifier = Modifier.weight(1f),
                                ) { adding = true }
                                ManhwaKit.GhostButton(
                                    text = stringResource(ATMR.strings.team_analyze_all),
                                    icon = MaterialSymbols.Rounded.Refresh,
                                    modifier = Modifier.weight(1f),
                                ) { members.forEach(::analyze) }
                            }
                        }
                    }
                    if (members.isEmpty()) {
                        item {
                            Text(
                                stringResource(ATMR.strings.team_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 6.dp),
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
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }

        if (adding || editing != null) {
            KeyDialog(
                member = editing,
                store = store,
                onDismiss = {
                    adding = false
                    editing = null
                },
                onSave = { edited ->
                    val current = editing
                    val saved = if (current == null) {
                        store.add(edited.key, edited.providerEnum, edited.model)
                            .copy(dailyLimit = edited.dailyLimit, baseUrl = edited.baseUrl)
                            .also(store::update)
                    } else {
                        current.copy(
                            key = edited.key.trim(),
                            provider = edited.provider,
                            model = edited.model.trim(),
                            dailyLimit = edited.dailyLimit,
                            baseUrl = edited.baseUrl.trim(),
                        ).also(store::update)
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
    private fun ModeChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(50)
        Row(
            modifier = Modifier
                .clip(shape)
                .then(if (selected) Modifier.background(ManhwaKit.accentBrush()) else Modifier.glass(shape, alpha = 0.35f))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }

    /** A small icon and a label, for the key's role, vision and record. */
    @Composable
    private fun Tag(icon: ImageVector, text: String, tint: Color) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
        }
    }

    @Composable
    private fun MeterBar(fraction: Float, color: Color) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
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
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), accent = lead, alpha = 0.5f)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HubIconTile(MaterialSymbols.Rounded.Key)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${providerLabel(member.providerEnum)} · ${maskKey(member.key)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val usedModel = analysis?.model?.takeIf { it.isNotBlank() } ?: member.model.ifBlank { member.providerEnum.defaultModel }
                    Text(
                        if (member.model.isBlank()) stringResource(ATMR.strings.team_model_auto_value, usedModel) else usedModel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = member.enabled, onCheckedChange = onEnabled)
            }
            // Status: working, resting, or not working and why
            val (status, statusColor, statusIcon) = when {
                analyzing -> Triple(stringResource(ATMR.strings.team_status_checking), Color.White.copy(alpha = 0.7f), null)
                !member.enabled -> Triple(stringResource(ATMR.strings.team_status_off), Color.White.copy(alpha = 0.5f), MaterialSymbols.Rounded.HourglassEmpty)
                health?.resting == true -> Triple(stringResource(ATMR.strings.team_status_resting), Amber, MaterialSymbols.Rounded.HourglassEmpty)
                analysis == null -> Triple(stringResource(ATMR.strings.team_status_unchecked), Color.White.copy(alpha = 0.6f), MaterialSymbols.Rounded.HourglassEmpty)
                analysis.ok -> Triple(stringResource(ATMR.strings.team_status_ok, analysis.latencyMs.toInt()), Green, MaterialSymbols.RoundedFilled.CheckCircle)
                analysis.limited -> Triple(stringResource(ATMR.strings.team_status_limited), Amber, MaterialSymbols.Rounded.HourglassEmpty)
                else -> Triple(stringResource(ATMR.strings.team_status_broken, analysis.error.orEmpty()), Crimson, MaterialSymbols.Rounded.Error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (statusIcon == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                } else {
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (member.enabled && analysis?.ok != false) {
                Text(
                    stringResource(ATMR.strings.team_strength, strength),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                MeterBar(strength / 100f, colors.primary)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (lead) {
                        Tag(MaterialSymbols.RoundedFilled.Star, stringResource(ATMR.strings.team_role_lead), Amber)
                    } else {
                        Tag(MaterialSymbols.Rounded.Translate, stringResource(ATMR.strings.team_role_translator), colors.primary)
                    }
                    if (share != null) Tag(MaterialSymbols.Rounded.Speed, stringResource(ATMR.strings.team_share, share), colors.primary)
                    if (analysis?.vision == true) Tag(MaterialSymbols.Rounded.Image, stringResource(ATMR.strings.team_vision), colors.primary)
                    if (health != null && health.done + health.failed > 0) {
                        Tag(MaterialSymbols.RoundedFilled.CheckCircle, stringResource(ATMR.strings.team_record, health.done, health.failed), Green)
                    }
                }
            }
            // How much is left: 100% to 0%, green, then amber, then red
            val levelColor = when {
                level.percent > 50 -> Green
                level.percent >= 20 -> Amber
                else -> Crimson
            }
            Text(
                stringResource(if (level.real) ATMR.strings.team_left_real else ATMR.strings.team_left_estimate, level.percent),
                style = MaterialTheme.typography.labelMedium,
                color = levelColor,
                fontWeight = FontWeight.Bold,
            )
            MeterBar(level.percent / 100f, levelColor)
            Text(
                stringResource(ATMR.strings.team_today, level.requests, level.errors),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionButton(MaterialSymbols.Rounded.Refresh, stringResource(ATMR.strings.team_actions_check), Color.White, enabled = !analyzing, onClick = onAnalyze)
                ActionButton(MaterialSymbols.Rounded.Edit, stringResource(ATMR.strings.team_edit), Color.White, onClick = onEdit)
                ActionButton(MaterialSymbols.Rounded.Delete, stringResource(ATMR.strings.team_delete), Crimson, onClick = onDelete)
            }
        }
    }

    @Composable
    private fun ActionButton(icon: ImageVector, label: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (enabled) 0.9f else 0.4f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint.copy(alpha = if (enabled) 0.9f else 0.4f))
        }
    }

    /** Add or change a key: provider, automatic or chosen model (from a list or the key's own), server, limit. */
    @Composable
    private fun KeyDialog(
        member: TeamStore.Member?,
        store: TeamStore,
        onDismiss: () -> Unit,
        onSave: (TeamStore.Member) -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var key by remember { mutableStateOf(member?.key.orEmpty()) }
        var dailyLimit by remember { mutableStateOf(member?.dailyLimit?.takeIf { it > 0 }?.toString().orEmpty()) }
        var model by remember { mutableStateOf(member?.model.orEmpty()) }
        var manual by remember { mutableStateOf(member?.model?.isNotBlank() == true) }
        var baseUrl by remember { mutableStateOf(member?.baseUrl.orEmpty()) }
        var chosen by remember { mutableStateOf(member?.providerEnum) }
        var liveModels by remember { mutableStateOf<List<String>?>(null) }
        var fetching by remember { mutableStateOf(false) }
        var fetchError by remember { mutableStateOf<String?>(null) }
        var filter by remember { mutableStateOf("") }
        // Told from the key as it is typed, until the reader picks one
        val provider = chosen ?: TeamStore.detectProvider(key)

        fun draft() = TeamStore.Member(
            id = member?.id ?: "draft",
            provider = provider.name,
            key = key.trim(),
            model = if (manual) model.trim() else "",
            dailyLimit = dailyLimit.toIntOrNull() ?: 0,
            baseUrl = baseUrl.trim(),
        )

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(if (member == null) ATMR.strings.team_add else ATMR.strings.team_edit)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(ATMR.strings.team_key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(ATMR.strings.team_provider), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiTranslator.Provider.entries.forEach { p ->
                            FilterChip(
                                selected = provider == p,
                                onClick = {
                                    chosen = p
                                    liveModels = null
                                },
                                label = { Text(providerLabel(p)) },
                            )
                        }
                    }
                    Text(stringResource(ATMR.strings.team_model_mode), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !manual, onClick = { manual = false }, label = { Text(stringResource(ATMR.strings.team_model_auto)) })
                        FilterChip(selected = manual, onClick = { manual = true }, label = { Text(stringResource(ATMR.strings.team_model_manual)) })
                    }
                    if (!manual) {
                        Text(
                            stringResource(ATMR.strings.team_model_auto_summary) + " (" + provider.defaultModel + ")",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text(stringResource(ATMR.strings.team_model)) },
                            placeholder = { Text(provider.defaultModel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            enabled = key.isNotBlank() && !fetching,
                            onClick = {
                                fetching = true
                                fetchError = null
                                scope.launch {
                                    try {
                                        // fetchModels() works on the IO dispatcher
                                        liveModels = store.fetchModels(draft())
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        fetchError = e.message ?: e.javaClass.simpleName
                                    } finally {
                                        fetching = false
                                    }
                                }
                            },
                        ) {
                            if (fetching) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(ATMR.strings.team_fetching_models))
                            } else {
                                Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(ATMR.strings.team_fetch_models))
                            }
                        }
                        fetchError?.let {
                            Text(
                                stringResource(ATMR.strings.team_models_failed, it.take(160)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        val options = liveModels ?: provider.suggestedModels
                        if (liveModels != null) {
                            Text(
                                stringResource(ATMR.strings.team_models_found, options.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (options.size > 12) {
                                OutlinedTextField(
                                    value = filter,
                                    onValueChange = { filter = it },
                                    label = { Text(stringResource(ATMR.strings.team_search_models)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            options.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }.take(MAX_SHOWN_MODELS).forEach { option ->
                                val selected = option == model.trim()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { model = option }
                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(option, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    // Own server for OpenAI-compatible keys (any provider can be given one)
                    if (provider == AiTranslator.Provider.OPENAI_COMPATIBLE || baseUrl.isNotBlank()) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(stringResource(ATMR.strings.team_base_url)) },
                            placeholder = { Text(provider.defaultBaseUrl) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                TextButton(onClick = { onSave(draft()) }, enabled = key.isNotBlank()) {
                    Text(stringResource(ATMR.strings.team_save))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(ATMR.strings.col_cancel)) } },
        )
    }

    private fun providerLabel(provider: AiTranslator.Provider) = when (provider) {
        AiTranslator.Provider.GEMINI -> "Gemini"
        AiTranslator.Provider.OPENROUTER -> "OpenRouter"
        AiTranslator.Provider.OPENAI_COMPATIBLE -> "OpenAI"
        AiTranslator.Provider.CLAUDE -> "Claude"
        AiTranslator.Provider.GROQ -> "Groq"
        AiTranslator.Provider.DEEPSEEK -> "DeepSeek"
    }

    /** Only the start and the end of a key are shown. */
    private fun maskKey(key: String) = if (key.length <= 10) "••••" else key.take(6) + "…" + key.takeLast(4)

    private companion object {
        const val MAX_SHOWN_MODELS = 300
        val Green = Color(0xFF2ECC71)
        val Amber = Color(0xFFF2B01E)
        val Crimson = Color(0xFFFF4D5E)
    }
}
