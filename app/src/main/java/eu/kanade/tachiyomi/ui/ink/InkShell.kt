package eu.kanade.tachiyomi.ui.ink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.anime.AnimeBrowseScreen
import eu.kanade.tachiyomi.anime.AnimeDetailsScreen
import eu.kanade.tachiyomi.novel.ContinueCard
import eu.kanade.tachiyomi.novel.NovelBrowseScreen
import eu.kanade.tachiyomi.novel.NovelCard
import eu.kanade.tachiyomi.novel.NovelDetailsScreen
import eu.kanade.tachiyomi.novel.NovelReaderScreen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceBestScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.main.brand.AppLogo
import eu.kanade.tachiyomi.ui.main.brand.LogoTile
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.nova.NovaColors
import eu.kanade.tachiyomi.ui.nova.NovaSourceSheet
import eu.kanade.tachiyomi.ui.nova.NovaTheme
import eu.kanade.tachiyomi.ui.nova.novaAppear
import eu.kanade.tachiyomi.ui.nova.novaClick
import eu.kanade.tachiyomi.ui.nova.openSeries
import eu.kanade.tachiyomi.ui.nova.rememberNovaSource
import eu.kanade.tachiyomi.ui.nova.rememberNovaSources
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.KeyboardArrowDown
import mihon.icons.materialsymbols.rounded.Menu
import mihon.icons.materialsymbols.rounded.Notifications
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPref

/** Inkverse's accent gradient: ink violet into panel pink. */
private val InkGradient = listOf(Color(0xFF7C5CFF), Color(0xFFFF4D8D))
private val InkNew = Color(0xFF22D3A6)

/**
 * MANHWA: the third system, Inkverse. A cinema lobby on deep ink: a full-bleed spotlight that turns every few
 * seconds, the stories being read, a top ten with huge outlined numbers and today's new episodes, for manhwa,
 * novels and anime alike (switched at the top); behind a floating glass bar of five places (home, library,
 * updates, browse, more). Its series pages are Nova's layout in Inkverse's colours. Every feature of the app stays.
 */
@Composable
fun InkShell(showBar: Boolean = true, events: @Composable (TabNavigator) -> Unit = {}) {
    NovaTheme {
        val navigator = LocalNavigator.currentOrThrow
        TabNavigator(tab = InkHomeTab, key = "InkTabs") { tabNavigator ->
            CompositionLocalProvider(LocalNavigator provides navigator) {
                Column(Modifier.fillMaxSize().background(NovaColors.Background)) {
                    Box(Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = tabNavigator.current,
                            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                            label = "inkTab",
                        ) { tab ->
                            tabNavigator.saveableState(key = "inkTab", tab) { tab.Content() }
                        }
                    }
                    AnimatedVisibility(
                        visible = showBar,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) { InkNavBar() }
                }
                BackHandler(enabled = tabNavigator.current != InkHomeTab) { tabNavigator.current = InkHomeTab }
                events(tabNavigator)
            }
        }
    }
}

/** The floating glass bar: the open place in a violet pill with its name, the others as icons only. */
@Composable
private fun InkNavBar() {
    val tabNavigator = LocalTabNavigator.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val items: List<Triple<Tab, ImageVector, String>> = listOf(
        Triple(InkHomeTab, MaterialSymbols.Rounded.Home, stringResource(ATMR.strings.nova_home)),
        Triple(LibraryTab, MaterialSymbols.Rounded.Bookmark, stringResource(MR.strings.label_library)),
        Triple(UpdatesTab, MaterialSymbols.Rounded.Notifications, stringResource(MR.strings.label_recent_updates)),
        Triple(BrowseTab, MaterialSymbols.Rounded.Explore, stringResource(MR.strings.browse)),
        Triple(MoreTab, MaterialSymbols.Rounded.Menu, stringResource(MR.strings.label_more)),
    )
    Box(Modifier.fillMaxWidth().background(NovaColors.Background).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(NovaColors.Surface.copy(alpha = 0.96f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (tab, icon, label) ->
                val selected = tabNavigator.current::class == tab::class
                val fill by animateColorAsState(if (selected) NovaColors.Blue.copy(alpha = 0.18f) else Color.Transparent, label = "inkPill")
                val tint by animateColorAsState(if (selected) NovaColors.Blue else NovaColors.Muted, label = "inkTint")
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .novaClick {
                            if (!selected) tabNavigator.current = tab else scope.launch { (tab as? eu.kanade.presentation.util.Tab)?.onReselect(navigator) }
                        }
                        .background(fill)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut(),
                    ) {
                        Text(
                            label,
                            color = NovaColors.Ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

data object InkHomeTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 0u, title = stringResource(ATMR.strings.nova_home), icon = rememberVectorPainter(MaterialSymbols.Rounded.Home))

    @Composable
    override fun Content() = InkHome()
}

// ---------------------------------------------------------------- The lobby

private const val KIND_MANGA = 0
private const val KIND_NOVEL = 1
private const val KIND_ANIME = 2

/** What the lobby keeps for this run of the app, so coming back does not ask the source again. */
private object InkCache {
    val popular = HashMap<Long, List<SManga>>()
    val latest = HashMap<Long, List<SManga>>()
    val top = HashMap<Long, List<SourceBestScreen.Best>>()
}

@Composable
private fun InkHome() {
    var kind by rememberSaveable { mutableIntStateOf(KIND_MANGA) }
    Box(Modifier.fillMaxSize().background(NovaColors.Background)) {
        AnimatedContent(
            targetState = kind,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "inkKind",
        ) { k ->
            when (k) {
                KIND_NOVEL -> NovelLobby()
                KIND_ANIME -> AnimeLobby()
                else -> MangaLobby()
            }
        }
        InkTopBar(kind) { kind = it }
    }
}

/** The bar over the spotlight: the logo, the three kinds of story, search and new chapters. */
@Composable
private fun InkTopBar(kind: Int, onKind: (Int) -> Unit) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val logoName by context.appGraph.uiPreferences.logoStyle.collectPref()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(AppLogo.of(logoName), 32.dp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { navigator.push(GlobalSearchScreen()) }) { Icon(MaterialSymbols.Rounded.Search, null, tint = Color.White) }
            IconButton(onClick = { navigator.push(FreshChaptersScreen()) }) { Icon(MaterialSymbols.Rounded.Notifications, null, tint = Color.White) }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                KIND_MANGA to stringResource(ATMR.strings.library_manhwa),
                KIND_NOVEL to stringResource(ATMR.strings.novel_kind),
                KIND_ANIME to stringResource(ATMR.strings.library_anime),
            ).forEach { (k, label) ->
                val on = k == kind
                val fill by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.12f), label = "inkKindChip")
                Text(
                    label,
                    color = if (on) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .novaClick { onKind(k) }
                        .clip(RoundedCornerShape(50))
                        .background(fill)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Manhwa

@Composable
private fun MangaLobby() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.appGraph }
    val scope = rememberCoroutineScope()
    val sources = rememberNovaSources()
    val source = rememberNovaSource(sources)
    var picking by remember { mutableStateOf(false) }

    val popular by produceState(source?.let { InkCache.popular[it.id] }, source?.id) {
        val s = source ?: return@produceState
        if (value == null) value = load(s) { it.getPopularManga(1).mangas }?.also { InkCache.popular[s.id] = it }
    }
    val latest by produceState(source?.let { InkCache.latest[it.id] }, source?.id) {
        val s = source ?: return@produceState
        if (value == null && s.supportsLatest) value = load(s) { it.getLatestUpdates(1).mangas }?.also { InkCache.latest[s.id] = it }
    }
    val top by produceState(source?.let { InkCache.top[it.id] }, source?.id) {
        val s = source ?: return@produceState
        if (value == null) {
            value = try {
                withContext(Dispatchers.IO) { SourceBestScreen(s.id, s.name).rank(graph) }.take(10).also { InkCache.top[s.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val history by remember { graph.getHistory.subscribe("") }.collectAsState(initial = emptyList())
    val reading = remember(history) { history.distinctBy { it.mangaId }.take(12) }

    fun open(manga: SManga) {
        val s = source ?: return
        scope.launch { openSeries(context, navigator, s.id, manga) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item(key = "hero") {
            Spotlight(
                items = popular.orEmpty().filter { !it.thumbnail_url.isNullOrBlank() }.take(6),
                sourceId = source?.id ?: -1L,
                sourceName = source?.name,
                onOpen = ::open,
                onSource = { source?.let { navigator.push(BrowseSourceScreen(it.id, null)) } },
                onServer = { picking = true },
            )
        }
        if (reading.isNotEmpty()) {
            item(key = "continue-title") { RailTitle(stringResource(ATMR.strings.ink_continue)) }
            item(key = "continue") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(reading, key = { _, h -> h.mangaId }) { i, h ->
                        ContinueMangaCard(h.title, h.coverData, h.chapterNumber, Modifier.novaAppear(i)) {
                            context.startActivity(ReaderActivity.newIntent(context, h.mangaId, h.chapterId))
                        }
                    }
                }
            }
        }
        top?.takeIf { it.isNotEmpty() }?.let { list ->
            item(key = "top-title") { RailTitle(stringResource(ATMR.strings.ink_top10)) }
            item(key = "top") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list, key = { i, b -> "${b.manga.url}#$i" }) { i, best ->
                        RankedCover(i + 1, best.manga.thumbnail_url, source?.id ?: -1L, Modifier.novaAppear(i)) { open(best.manga) }
                    }
                }
            }
        }
        latest?.takeIf { it.isNotEmpty() }?.let { list ->
            item(key = "latest-title") { RailTitle(stringResource(ATMR.strings.ink_new_today)) }
            item(key = "latest") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(list.take(20), key = { i, m -> "${m.url}#$i" }) { i, manga ->
                        PosterCard(manga.title, manga.thumbnail_url, source?.id ?: -1L, badge = "UP", modifier = Modifier.novaAppear(i)) { open(manga) }
                    }
                }
            }
        }
        popular?.drop(6)?.takeIf { it.isNotEmpty() }?.let { list ->
            item(key = "popular-title") { RailTitle(stringResource(ATMR.strings.nova_popular)) }
            item(key = "popular") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(list, key = { i, m -> "${m.url}#$i" }) { i, manga ->
                        PosterCard(manga.title, manga.thumbnail_url, source?.id ?: -1L, modifier = Modifier.novaAppear(i)) { open(manga) }
                    }
                }
            }
        }
        if (source == null) {
            item(key = "empty") { Empty(stringResource(ATMR.strings.nova_no_source)) }
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
            onSettings = { navigator.push(eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen(it.id)); picking = false },
            onBrowse = { navigator.push(BrowseSourceScreen(it.id, null)); picking = false },
            onDismiss = { picking = false },
        )
    }
}

/** One call to a source, null when it fails (the rail is then left out). */
private suspend fun <T> load(source: CatalogueSource, block: suspend (CatalogueSource) -> T): T? = try {
    withContext(Dispatchers.IO) { block(source) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * The spotlight: full-bleed art that slowly zooms (Ken Burns), a title on the poster gradient, two buttons, and dashes
 * that fill like a story bar while each slide waits its six seconds; a touch holds it.
 */
@Composable
private fun Spotlight(
    items: List<SManga>,
    sourceId: Long,
    sourceName: String?,
    onOpen: (SManga) -> Unit,
    onSource: () -> Unit,
    onServer: () -> Unit,
) {
    val height = 520.dp
    if (items.isEmpty()) {
        // The shape of the spotlight, breathing, while the source answers
        val pulse by rememberInfiniteTransition(label = "inkHeroPulse").animateFloat(
            initialValue = 0.4f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "inkHeroPulseValue",
        )
        Box(Modifier.fillMaxWidth().height(height).graphicsLayer { alpha = pulse }.background(NovaColors.Raised))
        return
    }
    val pager = rememberPagerState { items.size }
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
        if (pager.isScrollInProgress) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(6_000, easing = LinearEasing))
        pager.animateScrollToPage((pager.currentPage + 1) % items.size, animationSpec = tween(700, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val manga = items[page]
            val zoom by animateFloatAsState(
                targetValue = if (pager.currentPage == page) 1.06f else 1f,
                animationSpec = tween(6_000, easing = LinearEasing),
                label = "inkKenBurns",
            )
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = MangaCover(-1L, sourceId, false, manga.thumbnail_url, null),
                    contentDescription = manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                    },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to NovaColors.Background.copy(alpha = 0.35f),
                            1f to NovaColors.Background,
                        ),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 36.dp)) {
                    if (sourceName != null) {
                        Row(
                            Modifier.novaClick(onServer).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sourceName.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Icon(MaterialSymbols.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text(
                        manga.title,
                        color = Color.White,
                        fontSize = 32.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier
                                .novaClick { onOpen(manga) }
                                .clip(RoundedCornerShape(50))
                                .background(Brush.linearGradient(InkGradient))
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(MaterialSymbols.RoundedFilled.PlayArrow, null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(ATMR.strings.ink_read_now), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            stringResource(ATMR.strings.ink_more_here),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .novaClick(onSource)
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
        // Story dashes: the open slide fills as its time runs
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(items.size) { i ->
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                    val fill = when {
                        i < pager.currentPage -> 1f
                        i == pager.currentPage -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                }
            }
        }
    }
}

@Composable
private fun RailTitle(text: String) {
    Text(
        text,
        color = NovaColors.Ink,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, color = NovaColors.Muted, modifier = Modifier.fillMaxWidth().padding(32.dp))
}

/** A series being read: its cover, its title and the chapter it is at; opens the reader there. */
@Composable
private fun ContinueMangaCard(title: String, cover: MangaCover, chapter: Double, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .novaClick(onClick)
            .width(280.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(NovaColors.Surface)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = NovaColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val number = if (chapter % 1.0 == 0.0) chapter.toLong().toString() else chapter.toString()
            Text(stringResource(ATMR.strings.ink_chapter_n, number), color = NovaColors.Muted, fontSize = 13.sp)
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Brush.linearGradient(InkGradient)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MaterialSymbols.RoundedFilled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(stringResource(ATMR.strings.ink_resume), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** A poster (2:3) with its title, and a small badge in the corner when there is one. */
@Composable
private fun PosterCard(title: String, cover: String?, sourceId: Long, badge: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.novaClick(onClick).width(120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            AsyncImage(
                model = MangaCover(-1L, sourceId, false, cover, null),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(14.dp)).background(NovaColors.Raised),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(InkNew).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, color = NovaColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A cover with its rank as a huge outlined number half behind it, as a streaming top ten shows them. */
@Composable
private fun RankedCover(rank: Int, cover: String?, sourceId: Long, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.novaClick(onClick).width(170.dp).height(170.dp)) {
        Text(
            "$rank",
            style = TextStyle(
                fontSize = 120.sp,
                fontWeight = FontWeight.Black,
                color = NovaColors.Muted,
                drawStyle = Stroke(width = 4f),
            ),
            modifier = Modifier.align(Alignment.BottomStart).offset(y = 18.dp),
        )
        AsyncImage(
            model = MangaCover(-1L, sourceId, false, cover, null),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.BottomEnd).width(110.dp).height(165.dp).clip(RoundedCornerShape(14.dp)).background(NovaColors.Raised),
        )
    }
}

// ---------------------------------------------------------------- Novels

@Composable
private fun NovelLobby() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val tabNavigator = LocalTabNavigator.current
    val graph = remember { context.appGraph }
    val store = graph.novelStore
    val library by store.library.collectAsState()
    val progress by store.progress.collectAsState()
    val installed by graph.novelExtensionManager.installed.collectAsState()
    val pinned by graph.sourcePreferences.novelPinnedSources.collectPref()
    val reading = remember(library, progress) { store.history().take(12) }
    val favorites = remember(library) { store.favorites() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 120.dp, bottom = 120.dp)) {
        item(key = "novel-hero") {
            LobbyHeader(stringResource(ATMR.strings.ink_novels_title), stringResource(ATMR.strings.ink_novels_sub))
        }
        if (reading.isNotEmpty()) {
            item(key = "novel-continue-title") { RailTitle(stringResource(ATMR.strings.ink_continue)) }
            item(key = "novel-continue") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(reading, key = { it.first.key }) { (novel, p) ->
                        ContinueCard(novel, p) { navigator.push(NovelReaderScreen(novel.sourceId, novel.url, p.chapterUrl)) }
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "novel-fav-title") { RailTitle(stringResource(ATMR.strings.novel_library)) }
            item(key = "novel-fav") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(favorites, key = { _, n -> n.key }) { i, novel ->
                        NovelCard(novel.title, novel.cover, Modifier.width(120.dp).novaAppear(i)) {
                            navigator.push(NovelDetailsScreen(novel.sourceId, novel.url, novel.title, novel.cover))
                        }
                    }
                }
            }
        }
        item(key = "novel-sources-title") { RailTitle(stringResource(ATMR.strings.ink_sources)) }
        if (installed.isEmpty()) {
            item(key = "novel-sources-empty") {
                SourcesCta(stringResource(ATMR.strings.novel_no_sources)) { tabNavigator.current = BrowseTab }
            }
        } else {
            item(key = "novel-sources") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(installed.sortedByDescending { it.source.id.toString() in pinned }, key = { it.pkg }) { ext ->
                        SourceChip(ext.name, ext.lang) { navigator.push(NovelBrowseScreen(ext.source.id)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Anime

@Composable
private fun AnimeLobby() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val tabNavigator = LocalTabNavigator.current
    val graph = remember { context.appGraph }
    val store = graph.animeStore
    val library by store.library.collectAsState()
    val progress by store.progress.collectAsState()
    val installed by graph.animeExtensionManager.installed.collectAsState()
    val watching = remember(library, progress) { store.history().take(12) }
    val favorites = remember(library) { store.favorites() }
    val sources = remember(installed) { graph.animeExtensionManager.sources() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 120.dp, bottom = 120.dp)) {
        item(key = "anime-hero") {
            LobbyHeader(stringResource(ATMR.strings.ink_anime_title), stringResource(ATMR.strings.ink_anime_sub))
        }
        if (watching.isNotEmpty()) {
            item(key = "anime-continue-title") { RailTitle(stringResource(ATMR.strings.ink_continue_watching)) }
            item(key = "anime-continue") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(watching, key = { _, w -> w.first.key }) { i, (anime, _) ->
                        WideArt(anime.title, anime.thumbnailUrl, Modifier.novaAppear(i)) {
                            navigator.push(AnimeDetailsScreen(anime.sourceId, anime.url, anime.title, anime.thumbnailUrl))
                        }
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(key = "anime-fav-title") { RailTitle(stringResource(ATMR.strings.nova_library)) }
            item(key = "anime-fav") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(favorites, key = { _, a -> a.key }) { i, anime ->
                        Column(Modifier.novaAppear(i).novaClick { navigator.push(AnimeDetailsScreen(anime.sourceId, anime.url, anime.title, anime.thumbnailUrl)) }.width(120.dp)) {
                            AsyncImage(
                                model = anime.thumbnailUrl,
                                contentDescription = anime.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(14.dp)).background(NovaColors.Raised),
                            )
                            Text(anime.title, color = NovaColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        item(key = "anime-sources-title") { RailTitle(stringResource(ATMR.strings.ink_sources)) }
        if (sources.isEmpty()) {
            item(key = "anime-sources-empty") {
                SourcesCta(stringResource(ATMR.strings.ink_no_anime_sources)) { tabNavigator.current = BrowseTab }
            }
        } else {
            item(key = "anime-sources") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(sources, key = { it.id }) { s ->
                        SourceChip(s.name, s.lang) { navigator.push(AnimeBrowseScreen(s.id)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Shared pieces

/** The lobby's opening line for novels and anime: a big title over the ink gradient. */
@Composable
private fun LobbyHeader(title: String, sub: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(InkGradient.map { it.copy(alpha = 0.35f) }, start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Text(title, color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SourceChip(name: String, lang: String, onClick: () -> Unit) {
    Row(
        Modifier
            .novaClick(onClick)
            .clip(RoundedCornerShape(16.dp))
            .background(NovaColors.Surface)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = NovaColors.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(lang.uppercase(), color = NovaColors.Blue, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SourcesCta(text: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = NovaColors.Muted)
        Text(
            stringResource(MR.strings.browse),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .novaClick(onClick)
                .clip(RoundedCornerShape(50))
                .background(Brush.linearGradient(InkGradient))
                .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

/** A wide still (16:9) with its title over a shade, for what is being watched. */
@Composable
private fun WideArt(title: String, image: String?, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.novaClick(onClick).width(260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(NovaColors.Raised)) {
        AsyncImage(model = image, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.RoundedFilled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
