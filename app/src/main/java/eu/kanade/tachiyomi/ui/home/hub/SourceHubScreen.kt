package eu.kanade.tachiyomi.ui.home.hub

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.KeyboardArrowUp
import mihon.icons.materialsymbols.rounded.Refresh
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

        val q = query.trim().lowercase()
        val installed = state.installed.filter {
            (q.isEmpty() || it.name.lowercase().contains(q)) &&
                (language == null || it.lang == language) &&
                when (health) {
                    1 -> (state.health[it.id]?.latencyMs ?: -1) >= 0
                    2 -> state.health[it.id]?.latencyMs == -1L
                    else -> true
                }
        }
        val addable = state.addable.filter {
            (q.isEmpty() || it.name.lowercase().contains(q)) && (language == null || it.lang == language)
        }
        val onHome = state.home.count { id -> state.installed.any { "${it.id}" == id } }

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.hub_title),
                    subtitle = stringResource(ATMR.strings.hub_on_home, onHome),
                    onBack = navigator::pop,
                ) {
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
                    items(state.languages, key = { it }) { lang ->
                        ManhwaKit.Chip(languageName(lang), language == lang) { language = lang }
                    }
                }
                if (!discover) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { ManhwaKit.Chip(stringResource(ATMR.strings.home_all), health == 0) { health = 0 } }
                        item { ManhwaKit.Chip("● " + stringResource(ATMR.strings.hub_online), health == 1) { health = 1 } }
                        item { ManhwaKit.Chip("○ " + stringResource(ATMR.strings.hub_offline), health == 2) { health = 2 } }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                        SourceCard(source, state, Modifier.weight(1f), onToggle, onPin, onOpen)
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
            }
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

    /** Language code plus its own name, e.g. "KO · 한국어", so every source shows its language clearly. */
    private fun languageName(lang: String): String = when (lang) {
        "all" -> "🌐 MULTI"
        "" -> "?"
        else -> {
            val name = Locale.forLanguageTag(lang).let { it.getDisplayName(it).replaceFirstChar { c -> c.uppercase() } }
            if (name.isBlank() || name.equals(lang, ignoreCase = true)) lang.uppercase() else "${lang.uppercase()} · $name"
        }
    }
}
