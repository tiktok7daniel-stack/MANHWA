package eu.kanade.tachiyomi.ui.nova

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import tachiyomi.i18n.MR
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
    /** The palette of the system that is on: Nova's slate and blue, or Inkverse's ink and violet. */
    internal var palette by mutableStateOf(NovaPalette.SLATE)

    val Background get() = palette.background
    val Surface get() = palette.surface
    val Raised get() = palette.raised
    val Ink get() = palette.ink
    val Muted get() = palette.muted
    val Blue get() = palette.accent
}

/** The colours a shell built on Nova's screens draws with. */
enum class NovaPalette(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
) {
    SLATE(LookTokens.SlateBackground, LookTokens.SlateSurface, LookTokens.SlateRaised, LookTokens.SlateInk, LookTokens.SlateMuted, LookTokens.SlateBlue),
    INK(Color(0xFF0B0B10), Color(0xFF13131A), Color(0xFF1B1B24), Color(0xFFF4F4F8), Color(0xFFA9A9B8), Color(0xFF7C5CFF)),
}

/** Nova's colours and look for everything under it (the app's own tabs inside it included); Inkverse's when it is on. */
@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    val palette = if (rememberAppSystem() == AppSystem.INK) NovaPalette.INK else NovaPalette.SLATE
    // The screens read the palette as they draw; it follows the system picked
    androidx.compose.runtime.SideEffect { NovaColors.palette = palette }
    val base = MaterialTheme.colorScheme
    val scheme: ColorScheme = remember(base, palette) {
        if (palette == NovaPalette.SLATE) {
            slateDarkScheme(base)
        } else {
            slateDarkScheme(base).copy(
                primary = palette.accent,
                primaryContainer = androidx.compose.ui.graphics.lerp(palette.accent, Color.Black, 0.55f),
                onPrimaryContainer = androidx.compose.ui.graphics.lerp(palette.accent, Color.White, 0.8f),
                secondary = Color(0xFFFF4D8D),
                tertiary = Color(0xFF22D3A6),
                background = palette.background,
                onBackground = palette.ink,
                surface = palette.background,
                onSurface = palette.ink,
                surfaceVariant = palette.surface,
                onSurfaceVariant = palette.muted,
                surfaceContainerLowest = palette.background,
                surfaceContainerLow = palette.surface,
                surfaceContainer = palette.surface,
                surfaceContainerHigh = palette.raised,
                surfaceContainerHighest = Color(0xFF24242F),
            )
        }
    }
    val look = remember(palette) {
        ManhwaLook.slate(palette.accent).copy(
            background = palette.background,
            surface = palette.surface,
            surfaceRaised = palette.raised,
            text = palette.ink,
            muted = palette.muted,
        )
    }
    CompositionLocalProvider(LocalManhwaLook provides look) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}

private val NOVA_TABS: List<Tab> get() = listOf(NovaHomeTab, HistoryTab, NovaTopTab, LibraryTab, MoreTab)

/**
 * The whole Nova app: its tabs and its bottom bar. The bar steps aside while [showBar] is off (the library choosing
 * series), and [events] is handed the tabs so requests from outside the app (notifications, shortcuts) reach them.
 */
@Composable
fun NovaShell(showBar: Boolean = true, events: @Composable (TabNavigator) -> Unit = {}) {
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
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showBar,
                        enter = androidx.compose.animation.expandVertically() + fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
                    ) { NovaBottomBar() }
                }
                events(tabNavigator)
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
internal fun rememberNovaSources(): List<CatalogueSource> {
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
internal fun rememberNovaSource(sources: List<CatalogueSource>): CatalogueSource? {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val saved by graph.uiPreferences.novaSourceId.collectPref()
    val home = remember { graph.sourcePreferences.homeSources.get().mapNotNull { it.toLongOrNull() } }
    return sources.firstOrNull { it.id == saved }
        ?: sources.firstOrNull { it.id in home }
        ?: sources.firstOrNull()
}

/** Opens a series of [source] (it is added to the database as browsing does) in its page. */
internal suspend fun openSeries(context: android.content.Context, navigator: Navigator, sourceId: Long, manga: SManga) {
    // A failure here is told, not thrown: it runs in a screen's scope, where it would close the app
    val local = try {
        withContext(Dispatchers.IO) { context.appGraph.networkToLocalManga(manga.toDomainManga(sourceId)) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, e.message ?: e.javaClass.simpleName, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
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
                // The logo the reader picked, as its home-screen icon looks
                val logoName by graph.uiPreferences.logoStyle.collectPref()
                eu.kanade.tachiyomi.ui.main.brand.LogoTile(eu.kanade.tachiyomi.ui.main.brand.AppLogo.of(logoName), 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "MANHWA",
                    color = NovaColors.Ink,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f),
                )
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
                eu.kanade.presentation.browse.components.SourceIcon(
                    source = tachiyomi.domain.source.model.Source(source.id, source.lang, source.name, source.supportsLatest, isStub = false),
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)),
                )
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
    // The first page on its way: grey shapes of the covers to come, breathing, instead of an empty screen
    if (feed.items.isEmpty() && feed.loading) {
        NovaSkeleton(grid, padding)
        return
    }
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
private fun NovaSkeleton(grid: Boolean, padding: PaddingValues) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "novaSkeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(800),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "novaSkeletonPulse",
    )
    val shade = NovaColors.Raised
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(12) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(16.dp)).graphicsLayer { alpha = pulse }.background(shade))
                    Box(Modifier.fillMaxWidth(0.7f).height(10.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(5.dp)).graphicsLayer { alpha = pulse }.background(shade))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
            items(6) {
                Box(Modifier.fillMaxWidth().height(124.dp).clip(RoundedCornerShape(18.dp)).graphicsLayer { alpha = pulse }.background(shade))
            }
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
internal fun NovaSourceSheet(
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
                        // The source's own extension icon, as Browse shows it
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(NovaColors.Raised)
                                .border(1.dp, if (on) NovaColors.Blue else Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            eu.kanade.presentation.browse.components.SourceIcon(
                                source = tachiyomi.domain.source.model.Source(source.id, source.lang, source.name, source.supportsLatest, isStub = false),
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
                            )
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
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember(source.id) { mutableStateOf<String?>(null) }
    // Only a finished ranking is kept; one cut short (the tab left) or failed is asked again next time
    val list by produceState(rankings[source.id], source.id, attempt) {
        if (value == null) {
            try {
                value = withContext(Dispatchers.IO) { SourceBestScreen(source.id, source.name).rank(context.appGraph) }
                    .also { rankings[source.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            }
        }
    }
    val items = list
    if (items.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when {
                failed != null -> Text(
                    failed!!,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable {
                        failed = null
                        attempt++
                    },
                )
                items == null -> CircularProgressIndicator(color = NovaColors.Blue)
                else -> Text(stringResource(MR.strings.no_results_found), color = NovaColors.Muted)
            }
        }
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
