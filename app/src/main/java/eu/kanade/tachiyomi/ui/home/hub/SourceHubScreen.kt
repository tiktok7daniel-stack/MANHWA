package eu.kanade.tachiyomi.ui.home.hub

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.coil.InstalledSourceIcon
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import mihon.domain.extension.model.ContentWarning
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.i18n.stringResource
import mihon.icons.materialsymbols.rounded.Shield
import mihon.icons.materialsymbols.rounded.SortByAlpha
import mihon.icons.materialsymbols.rounded.Speed
import mihon.icons.materialsymbols.rounded.Verified
import mihon.icons.materialsymbols.rounded.VisibilityOff
import mihon.icons.materialsymbols.roundedfilled.Star
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/** Full-screen hub to pick the home screen's sources, check their servers and add new ones. */
class SourceHubScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<SourceHubViewModel>()
        val state by viewModel.state.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        var discover by rememberSaveable { mutableStateOf(false) }
        var language by rememberSaveable { mutableStateOf<String?>(null) }
        // 0 = all, 1 = online, 2 = offline
        var health by rememberSaveable { mutableStateOf(0) }
        var aiOpen by rememberSaveable { mutableStateOf(false) }
        var confirmUninstall by remember { mutableStateOf<Long?>(null) }

        val q = query.trim().lowercase()
        val top = language == TOP
        // Sorted only when what the order depends on changes, not on every frame
        val sortedInstalled = remember(state.installed, state.sort, state.health, state.pinned, state.libraryBySource, state.fameVersion) {
            state.sorted(state.installed)
        }
        val sortedAddable = remember(state.addable, state.sort, state.fameVersion) { state.sortedExtensions(state.addable) }
        val installed = sortedInstalled.filter {
            (q.isEmpty() || it.name.lowercase().contains(q)) &&
                (language == null || it.lang == language || (top && (SourceFame.score(it.name) ?: 0) >= TOP_SCORE)) &&
                when (health) {
                    1 -> (state.health[it.id]?.latencyMs ?: -1) >= 0
                    2 -> state.health[it.id]?.latencyMs == -1L
                    else -> true
                }
        }
        val addable = sortedAddable.filter {
            (q.isEmpty() || it.name.lowercase().contains(q)) &&
                (language == null || it.lang == language || (top && (SourceFame.score(it.name) ?: 0) >= TOP_SCORE))
        }
        // Fame rank of each source inside its language, for the "#1" badges
        val fameRank = remember(state.installed, state.fameVersion) {
            state.installed.groupBy { it.lang }.flatMap { (_, list) ->
                list.filter { SourceFame.score(it.name) != null }
                    .sortedByDescending { SourceFame.score(it.name) }
                    .mapIndexed { i, src -> src.id to i + 1 }
            }.toMap()
        }
        val onHome = state.home.count { id -> state.installed.any { "${it.id}" == id } }

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.hub_title),
                    subtitle = stringResource(ATMR.strings.hub_on_home, onHome),
                    onBack = navigator::pop,
                ) {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.AutoAwesome, active = aiOpen) { aiOpen = !aiOpen }
                    Spacer(Modifier.width(8.dp))
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh) {
                        viewModel.recheckHealth()
                        viewModel.refreshExtensions()
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ManhwaKit.SearchField(query, stringResource(ATMR.strings.src_search), { query = it })
                    Spacer(Modifier.height(12.dp))
                    Tabs(
                        left = stringResource(ATMR.strings.hub_tab_mine, state.installed.size),
                        right = stringResource(ATMR.strings.hub_tab_discover, state.addable.size),
                        rightSelected = discover,
                        onSelect = { discover = it },
                    )
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { ManhwaKit.Chip(stringResource(ATMR.strings.home_all), language == null) { language = null } }
                    item { ManhwaKit.Chip(stringResource(ATMR.strings.hub_top_manhwa), language == TOP) { language = TOP } }
                    items(state.languages, key = { it }) { lang ->
                        ManhwaKit.Chip(languageName(lang), language == lang) { language = lang }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item { SortChip(MaterialSymbols.RoundedFilled.Star, stringResource(ATMR.strings.hub_sort_fame), state.sort == SourceHubViewModel.SORT_FAME) { viewModel.setSort(SourceHubViewModel.SORT_FAME) } }
                    if (!discover) {
                        item { SortChip(MaterialSymbols.Rounded.Speed, stringResource(ATMR.strings.hub_sort_speed), state.sort == SourceHubViewModel.SORT_SPEED) { viewModel.setSort(SourceHubViewModel.SORT_SPEED) } }
                    }
                    item { SortChip(MaterialSymbols.Rounded.SortByAlpha, stringResource(ATMR.strings.hub_sort_name), state.sort == SourceHubViewModel.SORT_NAME) { viewModel.setSort(SourceHubViewModel.SORT_NAME) } }
                    if (!discover) {
                        item { Box(Modifier.width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.2f))) }
                        item { ManhwaKit.Chip(stringResource(ATMR.strings.hub_online), health == 1) { health = if (health == 1) 0 else 1 } }
                        item { ManhwaKit.Chip(stringResource(ATMR.strings.hub_offline), health == 2) { health = if (health == 2) 0 else 2 } }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (aiOpen) {
                        item(key = "ai") {
                            AiPanel(
                                state = state,
                                onAsk = viewModel::askAi,
                                onClear = viewModel::clearAi,
                                onOpen = { navigator.push(BrowseSourceScreen(it, null)) },
                                onToggle = viewModel::toggleHome,
                                onInstall = viewModel::install,
                                onUninstall = { confirmUninstall = it },
                            )
                        }
                    }
                    item(key = "adult") { AdultSwitch(state.hideAdult, viewModel::setHideAdult) }
                    if (!discover) {
                        item(key = "auto-update") {
                            UpdatesCard(
                                autoUpdate = state.autoUpdate,
                                updates = state.updatable.size,
                                updating = state.updating,
                                queue = state.updateQueue,
                                steps = state.installing,
                                onAutoUpdate = viewModel::setAutoUpdate,
                                onUpdateAll = viewModel::updateAll,
                            )
                        }
                    }
                    if (!discover) {
                        installedContent(
                            state = state,
                            sources = installed,
                            onToggle = viewModel::toggleHome,
                            onPin = viewModel::togglePin,
                            onOpen = { navigator.push(BrowseSourceScreen(it, null)) },
                            onAutoPick = viewModel::autoPick,
                            onClear = { viewModel.setHome(emptySet()) },
                            onMove = viewModel::move,
                            onDiscover = { discover = true },
                            fameRank = fameRank,
                            onUninstall = { confirmUninstall = it },
                        )
                    } else {
                        discoverContent(
                            state = state,
                            extensions = addable,
                            onInstall = viewModel::install,
                            onInstallAll = viewModel::installAll,
                            onAddStore = { navigator.push(ExtensionStoresScreen()) },
                        )
                    }
                }
            }
            BottomBar(
                count = onHome,
                onDone = navigator::pop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // MANHWA: why a source left the home screen, with "try now" and "show it anyway"
        val hiddenInfo by hiddenInfoRequest.collectAsState()
        hiddenInfo?.let { sourceId ->
            val entry = state.hiddenFromHome[sourceId]
            val name = state.installed.firstOrNull { it.id == sourceId }?.name.orEmpty()
            if (entry == null) {
                hiddenInfoRequest.value = null
            } else {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { hiddenInfoRequest.value = null },
                    containerColor = Color(0xFF0E0B0F),
                    icon = { Icon(MaterialSymbols.Rounded.Info, contentDescription = null, tint = Color(0xFFFFB020)) },
                    title = { Text(name, color = Color.White, fontWeight = FontWeight.Black) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(ATMR.strings.source_hidden_body), color = Color.White.copy(alpha = 0.85f))
                            Text(
                                stringResource(ATMR.strings.source_hidden_stats, entry.fails, timeAgo(entry.lastSuccess), timeAgo(entry.nextCheck)),
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            entry.reason?.let {
                                Text(it, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, maxLines = 3)
                            }
                        }
                    },
                    confirmButton = {
                        ManhwaKit.NeonButton(
                            stringResource(if (state.recheckingHidden) ATMR.strings.hub_checking else ATMR.strings.source_hidden_check),
                            icon = MaterialSymbols.Rounded.Refresh,
                        ) { viewModel.recheckHidden() }
                    },
                    dismissButton = {
                        ManhwaKit.GhostButton(stringResource(ATMR.strings.source_hidden_restore)) {
                            viewModel.restoreToHome(sourceId)
                            hiddenInfoRequest.value = null
                        }
                    },
                )
            }
        }
        // A source just added that did not answer: said once
        val toastContext = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(state.newlyHidden) {
            if (state.newlyHidden.isNotEmpty()) {
                android.widget.Toast.makeText(
                    toastContext,
                    toastContext.stringResource(ATMR.strings.source_hidden_new, state.newlyHidden.joinToString("، ")),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                viewModel.consumeNewlyHidden()
            }
        }
        confirmUninstall?.let { sourceId ->
            val extension = state.extensionBySource[sourceId]
            if (extension == null) {
                confirmUninstall = null
            } else {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmUninstall = null },
                    containerColor = Color(0xFF0E0B0F),
                    title = { Text(stringResource(ATMR.strings.hub_uninstall_title, extension.name), color = Color.White, fontWeight = FontWeight.Black) },
                    text = { Text(stringResource(ATMR.strings.hub_uninstall_body, extension.sources.size), color = Color.White.copy(alpha = 0.75f)) },
                    confirmButton = {
                        ManhwaKit.NeonButton(stringResource(ATMR.strings.hub_uninstall_ok), icon = MaterialSymbols.Rounded.Delete) {
                            viewModel.uninstall(sourceId)
                            confirmUninstall = null
                        }
                    },
                    dismissButton = {
                        ManhwaKit.GhostButton(stringResource(ATMR.strings.th_cancel)) { confirmUninstall = null }
                    },
                )
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.installedContent(
        state: SourceHubViewModel.State,
        sources: List<Source>,
        onToggle: (String) -> Unit,
        onPin: (String) -> Unit,
        onOpen: (Long) -> Unit,
        onAutoPick: () -> Unit,
        onClear: () -> Unit,
        onMove: (String, Int) -> Unit,
        onDiscover: () -> Unit,
        fameRank: Map<Long, Int>,
        onUninstall: (Long) -> Unit,
    ) {
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManhwaKit.NeonButton(
                    stringResource(ATMR.strings.src_auto_pick),
                    Modifier.weight(1f),
                    MaterialSymbols.Rounded.AutoAwesome,
                    onAutoPick,
                )
                ManhwaKit.GhostButton(stringResource(ATMR.strings.hub_clear), Modifier.weight(1f), null, onClear)
            }
        }
        if (state.installed.isEmpty()) {
            item(key = "empty") {
                EmptyCard(
                    title = stringResource(ATMR.strings.hub_empty_title),
                    body = stringResource(ATMR.strings.src_none_installed),
                    button = stringResource(ATMR.strings.src_add_new),
                    onClick = onDiscover,
                )
            }
            return
        }
        val ordered = state.homeOrdered
        if (ordered.size >= 2) {
            item(key = "order-title") {
                ManhwaKit.SectionTitle(stringResource(ATMR.strings.hub_order), stringResource(ATMR.strings.hub_order_sub))
            }
            items(ordered.size, key = { "o-${ordered[it].id}" }) { index ->
                val source = ordered[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(26.dp),
                    )
                    SourceLogo(source, Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.KeyboardArrowUp, size = 34) { onMove("${source.id}", -1) }
                    Spacer(Modifier.width(6.dp))
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.KeyboardArrowDown, size = 34) { onMove("${source.id}", 1) }
                }
            }
        }
        val pinned = sources.filter { "${it.id}" in state.pinned }
        if (pinned.isNotEmpty()) {
            item(key = "pinned-title") { ManhwaKit.SectionTitle(stringResource(ATMR.strings.hub_pinned)) }
            item(key = "pinned") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(pinned, key = { "p-${it.id}" }) { source ->
                        SourceCard(
                            source = source,
                            state = state,
                            modifier = Modifier.width(176.dp),
                            onToggle = onToggle,
                            onPin = onPin,
                            onOpen = onOpen,
                            rank = fameRank[source.id],
                            onUninstall = onUninstall,
                        )
                    }
                }
            }
        }
        item(key = "all-title") { ManhwaKit.SectionTitle(stringResource(ATMR.strings.hub_all_sources)) }
        sources.chunked(2).forEach { row ->
            item(key = "row-${row.first().id}") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { source ->
                        SourceCard(source, state, Modifier.weight(1f), onToggle, onPin, onOpen, fameRank[source.id], onUninstall)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    private fun androidx.compose.foundation.lazy.LazyListScope.discoverContent(
        state: SourceHubViewModel.State,
        extensions: List<Extension.Available>,
        onInstall: (Extension.Available) -> Unit,
        onInstallAll: (Set<String>) -> Unit,
        onAddStore: () -> Unit,
    ) {
        val bulk = state.bulk
        if (bulk != null) {
            item(key = "bulk") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.45f)
                        .padding(18.dp),
                ) {
                    Text(
                        stringResource(ATMR.strings.src_installing_all, bulk.first, bulk.second),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                    ManhwaKit.GlowBar(bulk.first.toFloat() / bulk.second.coerceAtLeast(1))
                }
            }
        } else if (state.addable.isNotEmpty()) {
            item(key = "bulk-buttons") {
                val arabic = state.addable.count { it.lang == "ar" && it.contentWarning == ContentWarning.SAFE }
                val english = state.addable.count { it.lang == "en" && it.contentWarning == ContentWarning.SAFE }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (arabic > 0) {
                        BulkCard(stringResource(ATMR.strings.src_all_arabic), arabic, Modifier.weight(1f)) { onInstallAll(setOf("ar")) }
                    }
                    if (english > 0) {
                        BulkCard(stringResource(ATMR.strings.src_all_english), english, Modifier.weight(1f)) { onInstallAll(setOf("en")) }
                    }
                }
            }
        }
        if (state.addable.isEmpty()) {
            item(key = "no-store") {
                EmptyCard(
                    title = stringResource(ATMR.strings.src_no_store_title),
                    body = stringResource(ATMR.strings.src_no_store_body),
                    button = stringResource(ATMR.strings.src_add_store),
                    onClick = onAddStore,
                )
            }
            return
        }
        items(extensions, key = { "e-${it.pkgName}" }) { extension ->
            ExtensionCard(extension, state.installing[extension.pkgName], onInstall)
        }
    }

    @Composable
    private fun SourceCard(
        source: Source,
        state: SourceHubViewModel.State,
        modifier: Modifier,
        onToggle: (String) -> Unit,
        onPin: (String) -> Unit,
        onOpen: (Long) -> Unit,
        rank: Int? = null,
        onUninstall: (Long) -> Unit = {},
    ) {
        val colors = MaterialTheme.colorScheme
        val id = "${source.id}"
        val selected = id in state.home
        val pinned = id in state.pinned
        val shape = RoundedCornerShape(22.dp)
        Column(
            modifier = modifier
                .glass(shape, accent = selected, alpha = if (selected) 0.55f else 0.4f)
                .clickable { onToggle(id) }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                SourceLogo(source, Modifier.size(46.dp))
                Spacer(Modifier.weight(1f))
                if (state.extensionBySource[source.id]?.hasUpdate == true) {
                    Icon(
                        MaterialSymbols.Rounded.Download,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.padding(top = 6.dp, end = 4.dp).size(16.dp),
                    )
                }
                if (state.extensionBySource.containsKey(source.id)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable { onUninstall(source.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MaterialSymbols.Rounded.Delete, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (pinned) colors.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
                        .clickable { onPin(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (pinned) MaterialSymbols.RoundedFilled.Star else MaterialSymbols.Rounded.Keep,
                        contentDescription = null,
                        tint = if (pinned) colors.primary else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                source.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    languageName(source.lang),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                HealthDot(state.health[source.id])
                // MANHWA source health: left out of the home screen by itself; the (i) says why
                if (state.hiddenFromHome[source.id] != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        MaterialSymbols.Rounded.Info,
                        contentDescription = stringResource(ATMR.strings.source_hidden_title),
                        tint = Color(0xFFFFB020),
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { hiddenInfoRequest.value = source.id },
                    )
                }
            }
            FameBadge(source.name, rank)
            val inLibrary = state.libraryBySource[source.id] ?: 0
            if (inLibrary > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(ATMR.strings.hub_in_library, inLibrary),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (selected) {
                                Modifier.background(ManhwaKit.accentBrush())
                            } else {
                                Modifier.border(1.dp, colors.primary.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            },
                        )
                        .clickable { onToggle(id) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (selected) MaterialSymbols.Rounded.Check else MaterialSymbols.Rounded.Add,
                        contentDescription = null,
                        tint = if (selected) Color.White else colors.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(if (selected) ATMR.strings.hub_on_home_btn else ATMR.strings.hub_add_home),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else colors.primary,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .glass(RoundedCornerShape(10.dp), alpha = 0.35f)
                        .clickable { onOpen(source.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(MaterialSymbols.Rounded.Explore, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
    }

    @Composable
    private fun SourceLogo(source: Source, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.8f), lerp(colors.primary, Color.Black, 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(source.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
            AsyncImage(
                model = InstalledSourceIcon(source.id),
                contentDescription = null,
                placeholder = ColorPainter(Color.Transparent),
                error = ColorPainter(Color.Transparent),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** "3 h ago" / "in 6 h" for the hidden-source dialog; "never" for 0. */
    @Composable
    private fun timeAgo(at: Long): String {
        if (at <= 0L) return stringResource(ATMR.strings.source_hidden_never)
        return android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
    }

    @Composable
    private fun HealthDot(health: SourceHubViewModel.Health?) {
        val latency = health?.latencyMs
        val (color, label) = when {
            health == null -> return
            latency == null -> Color.White.copy(alpha = 0.4f) to stringResource(ATMR.strings.hub_checking)
            latency < 0 -> Color(0xFFFF5252) to stringResource(ATMR.strings.hub_offline)
            latency < 700 -> Color(0xFF3DDC97) to "${latency}ms"
            latency < 1800 -> Color(0xFFFFB020) to "${latency}ms"
            else -> Color(0xFFFF7A1A) to "${latency}ms"
        }
        Box(Modifier.size(7.dp).neonGlow(CircleShape, color, 0.4f, 4.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }

    @Composable
    private fun ExtensionCard(extension: Extension.Available, step: InstallStep?, onInstall: (Extension.Available) -> Unit) {
        val colors = MaterialTheme.colorScheme
        val working = step == InstallStep.Pending || step == InstallStep.Downloading || step == InstallStep.Installing
        val done = step == InstallStep.Installed
        val canInstall = !working && !done
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), accent = done, alpha = 0.4f)
                .clickable(enabled = canInstall) { onInstall(extension) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.8f), lerp(colors.primary, Color.Black, 0.7f)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(extension.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
                AsyncImage(
                    model = extension.iconUrl,
                    contentDescription = null,
                    placeholder = ColorPainter(Color.Transparent),
                    error = ColorPainter(Color.Transparent),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        extension.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (extension.contentWarning != ContentWarning.SAFE) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "18+",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFF5252),
                            modifier = Modifier
                                .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                FameBadge(extension.name, null)
                Text(
                    listOf(languageName(extension.lang), stringResource(ATMR.strings.src_count, extension.sources.size), "v${extension.versionName}")
                        .joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .then(
                        if (done) {
                            Modifier.neonGlow(CircleShape, colors.primary, 0.35f, 10.dp).clip(CircleShape).background(ManhwaKit.accentBrush())
                        } else {
                            Modifier.clip(CircleShape).border(1.5.dp, colors.primary, CircleShape)
                        },
                    )
                    .clickable(enabled = canInstall) { onInstall(extension) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    working -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    done -> Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = Color.White)
                    else -> Icon(MaterialSymbols.Rounded.Add, contentDescription = null, tint = colors.primary)
                }
            }
        }
    }

    @Composable
    private fun BulkCard(title: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = modifier
                .neonGlow(RoundedCornerShape(22.dp), colors.primary, 0.2f, 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(lerp(colors.primary, Color.Black, 0.35f), Color(0xFF120A0D))))
                .border(1.dp, colors.primary.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
        ) {
            Icon(MaterialSymbols.Rounded.Download, contentDescription = null, tint = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White)
            Text(stringResource(ATMR.strings.src_count, count), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }

    @Composable
    private fun EmptyCard(title: String, body: String, button: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.45f)
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(14.dp))
            ManhwaKit.NeonButton(button, onClick = onClick)
        }
    }

    @Composable
    private fun Tabs(left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(50), alpha = 0.4f)
                .padding(4.dp),
        ) {
            listOf(false to left, true to right).forEach { (isRight, label) ->
                val selected = isRight == rightSelected
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .then(if (selected) Modifier.background(ManhwaKit.accentBrush()) else Modifier)
                        .clickable { onSelect(isRight) }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }

    @Composable
    private fun BottomBar(count: Int, onDone: () -> Unit, modifier: Modifier) {
        Row(
            modifier = modifier
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.85f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(ATMR.strings.hub_on_home, count),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            ManhwaKit.NeonButton(stringResource(ATMR.strings.home_done), onClick = onDone)
        }
    }

    @Composable
    private fun SortChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(50)
        Row(
            modifier = Modifier
                .then(
                    if (selected) {
                        Modifier.neonGlow(shape, colors.primary, 0.2f, 8.dp).clip(shape).background(ManhwaKit.accentBrush())
                    } else {
                        Modifier.glass(shape, alpha = 0.35f)
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
    }

    /** "#1 · S-Tier" style badge from the fame index; nothing for sources nobody knows. */
    @Composable
    private fun FameBadge(name: String, rank: Int?) {
        val tier = SourceFame.tier(name)
        if (tier == null && (rank == null || rank > 3)) return
        val colors = MaterialTheme.colorScheme
        val label = buildList {
            if (rank != null && rank <= 3) add(stringResource(ATMR.strings.hub_rank, rank))
            when (tier) {
                SourceFame.Tier.S -> add(stringResource(ATMR.strings.tier_s))
                SourceFame.Tier.OFFICIAL -> add(stringResource(ATMR.strings.tier_official))
                SourceFame.Tier.TOP -> add(stringResource(ATMR.strings.tier_top))
                SourceFame.Tier.POPULAR -> add(stringResource(ATMR.strings.tier_popular))
                null -> {}
            }
        }.joinToString(" · ")
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.primary.copy(alpha = 0.18f))
                .border(1.dp, colors.primary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (tier == SourceFame.Tier.OFFICIAL) MaterialSymbols.Rounded.Verified else MaterialSymbols.RoundedFilled.Star,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        }
    }

    /** Auto-update switch for the sources, with "Update all" when updates wait. */
    @Composable
    private fun UpdatesCard(
        autoUpdate: Boolean,
        updates: Int,
        updating: Boolean,
        queue: List<Extension.Loaded>,
        steps: Map<String, InstallStep>,
        onAutoUpdate: (Boolean) -> Unit,
        onUpdateAll: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), accent = autoUpdate, alpha = 0.4f)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onAutoUpdate(!autoUpdate) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(ATMR.strings.hub_auto_update), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(stringResource(ATMR.strings.hub_auto_update_sub), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                }
                androidx.compose.material3.Switch(checked = autoUpdate, onCheckedChange = onAutoUpdate)
            }
            if (queue.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(ATMR.strings.hub_updating_count, queue.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                )
                Spacer(Modifier.height(6.dp))
                // The list of what updates now: each source shows its step, then leaves when it is done
                Column(
                    modifier = Modifier.animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    queue.forEach { extension ->
                        androidx.compose.runtime.key(extension.pkgName) {
                            UpdateRow(extension, steps[extension.pkgName])
                        }
                    }
                }
            }
            if (updates > 0 || updating) {
                Spacer(Modifier.height(10.dp))
                if (updating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(ATMR.strings.hub_updating), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                } else {
                    ManhwaKit.NeonButton(
                        stringResource(ATMR.strings.hub_update_all, updates),
                        Modifier.fillMaxWidth(),
                        MaterialSymbols.Rounded.Download,
                        onUpdateAll,
                    )
                }
            }
        }
    }

    /** One source in the live update list, sliding in when it joins and showing its step until it is done. */
    @Composable
    private fun UpdateRow(extension: Extension.Loaded, step: InstallStep?) {
        val colors = MaterialTheme.colorScheme
        val appear = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
        androidx.compose.animation.AnimatedVisibility(
            visibleState = appear,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
        ) {
            val done = step == InstallStep.Installed
            val failed = step == InstallStep.Error
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(14.dp), accent = done, alpha = 0.35f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = extension.sources.firstOrNull()
                Box(
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(colors.primary.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(extension.name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.White)
                    if (icon != null) {
                        AsyncImage(
                            model = InstalledSourceIcon(icon.id),
                            contentDescription = null,
                            placeholder = ColorPainter(Color.Transparent),
                            error = ColorPainter(Color.Transparent),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(extension.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(
                            when (step) {
                                InstallStep.Downloading -> ATMR.strings.hub_step_downloading
                                InstallStep.Installing -> ATMR.strings.hub_step_installing
                                InstallStep.Installed -> ATMR.strings.hub_step_done
                                InstallStep.Error -> ATMR.strings.hub_step_failed
                                else -> ATMR.strings.hub_step_waiting
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            done -> colors.primary
                            failed -> colors.error
                            else -> Color.White.copy(alpha = 0.6f)
                        },
                    )
                }
                when {
                    done -> Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    failed -> Icon(MaterialSymbols.Rounded.Close, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                    step == InstallStep.Downloading || step == InstallStep.Installing ->
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else -> Box(Modifier.size(8.dp).background(Color.White.copy(alpha = 0.3f), CircleShape))
                }
            }
        }
    }

    /** AI source search: ask about sources only, get picks you can open, add to home, install or remove. */
    @Composable
    private fun AiPanel(
        state: SourceHubViewModel.State,
        onAsk: (String) -> Unit,
        onClear: () -> Unit,
        onOpen: (Long) -> Unit,
        onToggle: (String) -> Unit,
        onInstall: (Extension.Available) -> Unit,
        onUninstall: (Long) -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        var question by rememberSaveable { mutableStateOf("") }
        val ai = state.ai
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .neonGlow(RoundedCornerShape(24.dp), colors.primary, 0.2f, 12.dp)
                .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.5f)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(ATMR.strings.ai_src_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
                    Text(stringResource(ATMR.strings.ai_src_sub), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                }
                if (ai.answer != null || ai.picks.isNotEmpty() || ai.error != null) {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Close, size = 32, onClick = onClear)
                }
            }
            ManhwaKit.SearchField(question, stringResource(ATMR.strings.ai_src_hint), { question = it })
            ManhwaKit.NeonButton(stringResource(ATMR.strings.ai_src_ask), Modifier.fillMaxWidth(), MaterialSymbols.Rounded.AutoAwesome) { onAsk(question) }
            val suggestions = listOf(
                stringResource(ATMR.strings.ai_src_s1),
                stringResource(ATMR.strings.ai_src_s2),
                stringResource(ATMR.strings.ai_src_s3),
                stringResource(ATMR.strings.ai_src_s4),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { text ->
                    ManhwaKit.Chip(text, false) {
                        question = text
                        onAsk(text)
                    }
                }
            }
            when {
                ai.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(ATMR.strings.ai_src_thinking), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                ai.error != null -> Text(
                    if (ai.error == SourceHubViewModel.NO_KEY) stringResource(ATMR.strings.ai_src_no_key) else stringResource(ATMR.strings.ai_src_failed, ai.error.take(160)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            ai.answer?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            }
            ai.picks.forEachIndexed { index, pick ->
                AiPickCard(index + 1, pick, state, onOpen, onToggle, onInstall, onUninstall)
            }
        }
    }

    @Composable
    private fun AiPickCard(
        number: Int,
        pick: SourceHubViewModel.AiPick,
        state: SourceHubViewModel.State,
        onOpen: (Long) -> Unit,
        onToggle: (String) -> Unit,
        onInstall: (Extension.Available) -> Unit,
        onUninstall: (Long) -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp), alpha = 0.45f)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = colors.primary, modifier = Modifier.width(22.dp))
                val installed = pick.installed
                if (installed != null) {
                    SourceLogo(installed, Modifier.size(38.dp))
                } else {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(colors.primary.copy(alpha = 0.3f))) {
                        AsyncImage(
                            model = pick.available?.iconUrl,
                            contentDescription = null,
                            placeholder = ColorPainter(Color.Transparent),
                            error = ColorPainter(Color.Transparent),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pick.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            languageName(installed?.lang ?: pick.available?.lang.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        if (installed != null) {
                            HealthDot(state.health[installed.id])
                        } else {
                            HealthDot(SourceHubViewModel.Health(pick.latencyMs))
                        }
                    }
                }
            }
            if (pick.reason.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(pick.reason, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val installed = pick.installed
                val available = pick.available
                if (installed != null) {
                    val onHome = "${installed.id}" in state.home
                    ManhwaKit.Chip(
                        stringResource(if (onHome) ATMR.strings.hub_on_home_btn else ATMR.strings.hub_add_home),
                        onHome,
                    ) { onToggle("${installed.id}") }
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Explore, size = 36) { onOpen(installed.id) }
                    if (state.extensionBySource.containsKey(installed.id)) {
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Delete, size = 36) { onUninstall(installed.id) }
                    }
                } else if (available != null) {
                    val step = state.installing[available.pkgName]
                    val working = step == InstallStep.Pending || step == InstallStep.Downloading || step == InstallStep.Installing
                    when {
                        working -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        step == InstallStep.Installed -> Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = colors.primary)
                        else -> ManhwaKit.NeonButton(stringResource(ATMR.strings.ai_src_install), icon = MaterialSymbols.Rounded.Download) { onInstall(available) }
                    }
                }
            }
        }
    }

    /** The +18 isolation switch, shown on top of both lists. */
    @Composable
    private fun AdultSwitch(hidden: Boolean, onChange: (Boolean) -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(20.dp), accent = hidden, alpha = 0.4f)
                .clickable { onChange(!hidden) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (hidden) MaterialSymbols.Rounded.Shield else MaterialSymbols.Rounded.VisibilityOff,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(ATMR.strings.hub_hide_adult), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text(stringResource(ATMR.strings.hub_hide_adult_sub), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            androidx.compose.material3.Switch(checked = hidden, onCheckedChange = onChange)
        }
    }

    /** Language code plus its own name, e.g. "KO · 한국어", so every source shows its language clearly. */
    private fun languageName(lang: String): String = when (lang) {
        "all" -> "MULTI"
        "" -> "?"
        else -> {
            val name = Locale.forLanguageTag(lang).let { it.getDisplayName(it).replaceFirstChar { c -> c.uppercase() } }
            if (name.isBlank() || name.equals(lang, ignoreCase = true)) lang.uppercase() else "${lang.uppercase()} · $name"
        }
    }

    private companion object {
        const val TOP = "__top"
        const val TOP_SCORE = 85

        /** The source whose "hidden from home" dialog is open. */
        val hiddenInfoRequest = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    }
}
