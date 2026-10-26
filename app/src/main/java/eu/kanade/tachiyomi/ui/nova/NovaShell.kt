package eu.kanade.tachiyomi.ui.nova

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.domain.source.service.AdultFilter
import eu.kanade.presentation.components.LocalManhwaLook
import eu.kanade.presentation.components.LookTokens
import eu.kanade.presentation.components.ManhwaLook
import eu.kanade.presentation.components.slateDarkScheme
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceBestScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.domain.manga.model.toDomainManga
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.History
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.Menu
import mihon.icons.materialsymbols.rounded.MilitaryTech
import mihon.icons.materialsymbols.rounded.Notifications
import mihon.icons.materialsymbols.rounded.OpenInNew
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Storage
import mihon.icons.materialsymbols.rounded.Widgets
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import tachiyomi.domain.manga.model.MangaCover as CoverData
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPref

/**
 * MANHWA Nova: the second system of the app. A home that shows one source (its "server", picked from a sheet of
 * the installed sources by language) in three tabs (most popular, latest updates, recommendations), a ranking
 * tab, and the history, the library and the menu of the app, behind its own bottom bar, on slate and blue. The
 * series page and its chapters have their own Nova layout too (NovaMangaDetails). Every feature of the app stays:
 * the menu opens all of it.
 */
object NovaColors {
    val Background = LookTokens.SlateBackground
    val Surface = LookTokens.SlateSurface
    val Raised = LookTokens.SlateRaised
    val Ink = LookTokens.SlateInk
    val Muted = LookTokens.SlateMuted
    val Blue = LookTokens.SlateBlue
}

/** Nova's colours and look for everything under it (the app's own tabs inside it included). */
@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme: ColorScheme = remember(base) { slateDarkScheme(base) }
    val look = remember { ManhwaLook.slate(NovaColors.Blue) }
    CompositionLocalProvider(LocalManhwaLook provides look) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

private val NOVA_TABS: List<Tab> get() = listOf(NovaHomeTab, HistoryTab, NovaTopTab, LibraryTab, MoreTab)

/** The whole Nova app: its tabs and its bottom bar. */
@Composable
fun NovaShell() {
    NovaTheme {
        // The tab navigator only holds tabs; screens opened from inside a tab go on the app's navigator, as in the
        // original home (pushing one on the tab navigator crashes it)
        val navigator = LocalNavigator.currentOrThrow
        TabNavigator(tab = NovaHomeTab, key = "NovaTabs") { tabNavigator ->
            androidx.compose.runtime.CompositionLocalProvider(LocalNavigator provides navigator) {
                Column(Modifier.fillMaxSize().background(NovaColors.Background)) {
                    Box(Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = tabNavigator.current,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "novaTab",
                        ) { tab ->
                            tabNavigator.saveableState(key = "novaTab", tab) { tab.Content() }
                        }
                    }
                    NovaBottomBar()
                }
                BackHandler(enabled = tabNavigator.current != NovaHomeTab) { tabNavigator.current = NovaHomeTab }
            }
        }
    }
}

@Composable
private fun NovaBottomBar() {
    val tabNavigator = LocalTabNavigator.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val items = listOf(
        Triple(NovaHomeTab, MaterialSymbols.Rounded.Home, stringResource(ATMR.strings.nova_home)),
        Triple(HistoryTab, MaterialSymbols.Rounded.History, stringResource(ATMR.strings.nova_history)),
        Triple(NovaTopTab, MaterialSymbols.Rounded.MilitaryTech, stringResource(ATMR.strings.nova_top)),
        Triple(LibraryTab, MaterialSymbols.Rounded.Bookmark, stringResource(ATMR.strings.nova_library)),
        Triple(MoreTab, MaterialSymbols.Rounded.Menu, stringResource(ATMR.strings.nova_menu)),
    )
    Column(Modifier.fillMaxWidth().background(NovaColors.Background)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            items.forEach { (tab, icon, label) ->
                val selected = tabNavigator.current::class == tab::class
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            if (!selected) tabNavigator.current = tab else scope.launch { tab.onReselect(navigator) }
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val pill by androidx.compose.animation.animateColorAsState(
                        if (selected) NovaColors.Blue.copy(alpha = 0.22f) else Color.Transparent,
                        label = "novaPill",
                    )
                    val tint by androidx.compose.animation.animateColorAsState(
                        if (selected) NovaColors.Blue else NovaColors.Muted,
                        label = "novaTint",
                    )
                    val lift by androidx.compose.animation.core.animateFloatAsState(
                        if (selected) 1.12f else 1f,
                        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f),
                        label = "novaLift",
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(pill)
                            .padding(horizontal = 18.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = tint,
                            modifier = Modifier.graphicsLayer {
                                scaleX = lift
                                scaleY = lift
                            },
                        )
                    }
                    Text(
                        label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) NovaColors.Ink else NovaColors.Muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ sources

/** The sources Nova can show: the installed catalogues of the languages turned on, the +18 switches holding. */
@Composable
private fun rememberNovaSources(): List<CatalogueSource> {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val languages by graph.sourcePreferences.enabledLanguages.collectPref()
    val hide by graph.sourcePreferences.hideAdult.collectPref()
    val only by graph.sourcePreferences.onlyAdult.collectPref()
    val list by produceState(emptyList<CatalogueSource>(), languages, hide, only) {
        value = withContext(Dispatchers.IO) {
            graph.sourceManager.getAll().filterIsInstance<CatalogueSource>()
                .filter { it.id != 0L && (it.lang in languages || it.lang == "all") && !graph.adultFilter.hides(it.id, it.name) }
                .sortedBy { it.name.lowercase() }
        }
    }
    return list
}

/** The source picked for Nova's home: the saved one, else the first home source, else the first one. */
@Composable
private fun rememberNovaSource(sources: List<CatalogueSource>): CatalogueSource? {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val saved by graph.uiPreferences.novaSourceId.collectPref()
    val home = remember { graph.sourcePreferences.homeSources.get().mapNotNull { it.toLongOrNull() } }
    return sources.firstOrNull { it.id == saved }
        ?: sources.firstOrNull { it.id in home }
        ?: sources.firstOrNull()
}

/** Opens a series of [source] (it is added to the database as browsing does) in its page. */
private suspend fun openSeries(context: android.content.Context, navigator: Navigator, sourceId: Long, manga: SManga) {
    val local = withContext(Dispatchers.IO) { context.appGraph.networkToLocalManga(manga.toDomainManga(sourceId)) }
    navigator.push(MangaScreen(local.id, true))
}

// ------------------------------------------------------------------ home

data object NovaHomeTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 0u, title = stringResource(ATMR.strings.nova_home), icon = rememberVectorPainter(MaterialSymbols.Rounded.Home))

    @Composable
    override fun Content() = NovaHome()
}

private class Feed {
    val items = mutableStateListOf<SManga>()
    var page = 1
    var hasNext = true
    var loading by mutableStateOf(false)
    var failed by mutableStateOf<String?>(null)
}

/** The feeds of this run of the app, by source and tab, so going back keeps what was loaded. */
private val feeds = HashMap<String, Feed>()

@Composable
private fun NovaHome() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.appGraph }
    val scope = rememberCoroutineScope()
    val sources = rememberNovaSources()
    val source = rememberNovaSource(sources)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(NovaColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: the app, then search, new chapters and the grid / list switch
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(NovaColors.Blue),
                    contentAlignment = Alignment.Center,
                ) { Text("M", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Text("MANHWA", color = NovaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { navigator.push(GlobalSearchScreen()) }) { Icon(MaterialSymbols.Rounded.Search, null, tint = NovaColors.Ink) }
                IconButton(onClick = { navigator.push(FreshChaptersScreen()) }) { Icon(MaterialSymbols.Rounded.Notifications, null, tint = NovaColors.Ink) }
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) MaterialSymbols.Rounded.FormatListNumbered else MaterialSymbols.Rounded.Widgets, null, tint = NovaColors.Ink)
                }
            }
            NovaTabs(
                titles = listOf(
                    stringResource(ATMR.strings.nova_popular),
                    stringResource(ATMR.strings.nova_latest),
                    stringResource(ATMR.strings.nova_recommended),
                ),
                selected = tab,
                onSelect = { tab = it },
            )
            if (source == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(ATMR.strings.nova_no_source), color = NovaColors.Muted, textAlign = TextAlign.Center)
                }
            } else {
                AnimatedContent(targetState = tab to source.id, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "novaFeed") { (t, _) ->
                    when (t) {
                        2 -> NovaRanking(source, compactBadges = true)
                        else -> NovaFeed(source, latest = t == 1, grid = grid && t == 0) { manga ->
                            scope.launch { openSeries(context, navigator, source.id, manga) }
                        }
                    }
                }
            }
        }
        // The server button, floating over the corner
        if (source != null) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(NovaColors.Raised)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .clickable { picking = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MaterialSymbols.Rounded.Storage, null, tint = NovaColors.Blue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(ATMR.strings.nova_server, source.name), color = NovaColors.Ink, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(MaterialSymbols.Rounded.KeyboardArrowDown, null, tint = NovaColors.Blue, modifier = Modifier.size(18.dp))
            }
        }
    }
    if (picking) {
        NovaSourceSheet(
            sources = sources,
            selected = source?.id,
            onPick = {
                graph.uiPreferences.novaSourceId.set(it.id)
                picking = false
            },
            onSettings = { navigator.push(SourcePreferencesScreen(it.id)); picking = false },
            onBrowse = { navigator.push(BrowseSourceScreen(it.id, null)); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** The tabs of the home: titles with a blue line under the open one, sliding to it. */
@Composable
fun NovaTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth / titles.size
            val x by animateDpAsState(width * selected, label = "novaTabs")
            Row(Modifier.fillMaxWidth()) {
                titles.forEachIndexed { i, title ->
                    Text(
                        title,
                        modifier = Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 14.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 17.sp,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == selected) NovaColors.Blue else NovaColors.Muted,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x)
                    .width(width)
                    .padding(horizontal = 12.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(NovaColors.Blue),
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
    }
}

/** Popular (a grid of covers, or a list) or latest updates (wide cards) of [source], page after page. */
@Composable
private fun NovaFeed(source: CatalogueSource, latest: Boolean, grid: Boolean, onOpen: (SManga) -> Unit) {
    val key = "${source.id}:$latest"
    val feed = remember(key) { feeds.getOrPut(key) { Feed() } }
    val scope = rememberCoroutineScope()
    fun more() {
        if (feed.loading || !feed.hasNext) return
        feed.loading = true
        feed.failed = null
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    if (latest && source.supportsLatest) source.getLatestUpdates(feed.page) else source.getPopularManga(feed.page)
                }
                feed.items.addAll(page.mangas.filter { m -> feed.items.none { it.url == m.url } })
                feed.hasNext = page.hasNextPage
                feed.page += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feed.failed = e.message ?: e.javaClass.simpleName
            } finally {
                feed.loading = false
            }
        }
    }
    LaunchedEffect(key) { if (feed.items.isEmpty()) more() }
    val padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp)
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(feed.items, key = { i, m -> "${m.url}#$i" }) { i, manga ->
                if (i >= feed.items.size - 6) LaunchedEffect(feed.items.size) { more() }
                NovaCover(source.id, manga, modifier = Modifier.novaAppear(if (i < 12) i else i % 3), onClick = { onOpen(manga) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) { FeedFooter(feed) { more() } }
        }
    } else {
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            items(feed.items.size, key = { "${feed.items[it].url}#$it" }) { i ->
                val manga = feed.items[i]
                if (i >= feed.items.size - 4) LaunchedEffect(feed.items.size) { more() }
                Row(
                    Modifier
                        .novaAppear(if (i < 6) i else 0)
                        .novaClick { onOpen(manga) }
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(NovaColors.Surface)
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                        .padding(10.dp),
                ) {
                    AsyncImage(
                        model = CoverData(-1L, source.id, false, manga.thumbnail_url, null),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(78.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        manga.title,
                        modifier = Modifier.weight(1f).padding(top = 6.dp),
                        color = NovaColors.Ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item { FeedFooter(feed) { more() } }
        }
    }
}

@Composable
private fun FeedFooter(feed: Feed, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            feed.loading -> CircularProgressIndicator(color = NovaColors.Blue)
            feed.failed != null -> Text(
                feed.failed!!,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(onClick = retry),
            )
        }
    }
}

/** A cover with its title under it, as the home grid shows them. */
@Composable
fun NovaCover(sourceId: Long, manga: SManga, badge: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.novaClick(onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            AsyncImage(
                model = CoverData(-1L, sourceId, false, manga.thumbnail_url, null),
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(8.dp)).background(NovaColors.Blue).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            manga.title,
            color = NovaColors.Ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------ the sources sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovaSourceSheet(
    sources: List<CatalogueSource>,
    selected: Long?,
    onPick: (CatalogueSource) -> Unit,
    onSettings: (CatalogueSource) -> Unit,
    onBrowse: (CatalogueSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = when (filter) {
        1 -> sources.filter { it.lang == "ar" }
        2 -> sources.filter { it.lang == "en" }
        else -> sources
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NovaColors.Surface,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(NovaColors.Blue.copy(alpha = 0.16f))
                        .border(1.dp, NovaColors.Blue.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(MaterialSymbols.Rounded.Public, null, tint = NovaColors.Blue) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(ATMR.strings.nova_sources_title), color = NovaColors.Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(stringResource(ATMR.strings.nova_sources_sub), color = NovaColors.Muted, fontSize = 13.sp)
                }
            }
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ATMR.strings.nova_sources_ar, ATMR.strings.nova_sources_en, ATMR.strings.nova_sources_all).forEachIndexed { i, res ->
                    val on = filter == (i + 1) % 3
                    Text(
                        stringResource(res),
                        color = if (on) Color.White else NovaColors.Ink,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) NovaColors.Blue else NovaColors.Raised)
                            .clickable { filter = (i + 1) % 3 }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().height(440.dp)) {
                items(shown, key = { it.id }) { source ->
                    val on = source.id == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) NovaColors.Blue.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onPick(source) }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(NovaColors.Raised),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(source.name.take(2).uppercase(), color = NovaColors.Blue, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(source.name, color = NovaColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                LocaleHelper.getSourceDisplayName(source.lang, context),
                                color = NovaColors.Muted,
                                fontSize = 12.sp,
                            )
                        }
                        IconButton(onClick = { onBrowse(source) }) { Icon(MaterialSymbols.Rounded.OpenInNew, null, tint = NovaColors.Muted) }
                        if (source is ConfigurableSource) {
                            IconButton(onClick = { onSettings(source) }) { Icon(MaterialSymbols.Rounded.Settings, null, tint = NovaColors.Ink) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ------------------------------------------------------------------ ranking

data object NovaTopTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 2u, title = stringResource(ATMR.strings.nova_top), icon = rememberVectorPainter(MaterialSymbols.Rounded.MilitaryTech))

    @Composable
    override fun Content() {
        val sources = rememberNovaSources()
        val source = rememberNovaSource(sources)
        Column(Modifier.fillMaxSize().background(NovaColors.Background)) {
            Text(
                stringResource(ATMR.strings.nova_top_title),
                color = NovaColors.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            )
            if (source != null) NovaRanking(source, compactBadges = false)
        }
    }
}

private val rankings = HashMap<Long, List<SourceBestScreen.Best>>()

/** [source]'s best series (AniList score and popularity, the library left out), as covers with their score. */
@Composable
private fun NovaRanking(source: CatalogueSource, compactBadges: Boolean) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val list by produceState(rankings[source.id], source.id) {
        if (value == null) {
            value = runCatching {
                withContext(Dispatchers.IO) { SourceBestScreen(source.id, source.name).rank(context.appGraph) }
            }.getOrDefault(emptyList()).also { rankings[source.id] = it }
        }
    }
    val items = list
    if (items == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = NovaColors.Blue) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { i, b -> "${b.manga.url}#$i" }) { i, best ->
            val score = best.score?.let { "%.1f ★".format(it / 10f) }
            NovaCover(source.id, best.manga, modifier = Modifier.novaAppear(if (i < 12) i else i % 3), badge = if (compactBadges) score else listOfNotNull("#${i + 1}", score).joinToString("  ")) {
                scope.launch { openSeries(context, navigator, source.id, best.manga) }
            }
        }
    }
}
