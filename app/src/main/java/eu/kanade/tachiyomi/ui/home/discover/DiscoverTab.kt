package eu.kanade.tachiyomi.ui.home.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.translation.search.AiChatScreen
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA home: the content of the reader's chosen sources, gathered on one screen. */
data object DiscoverTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 5u,
            title = stringResource(ATMR.strings.home_tab),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Home),
        )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val context = LocalContext.current
        val viewModel = metroViewModel<DiscoverViewModel>()
        val state by viewModel.state.collectAsState()
        var showSources by rememberSaveable { mutableStateOf(false) }
        val openManga = { manga: Manga -> navigator.push(MangaScreen(manga.id, true)) }

        GlassBackdrop {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item {
                        TopBar(
                            onSearch = { navigator.push(GlobalSearchScreen("")) },
                            onSources = { showSources = true },
                        )
                    }
                    if (state.needsSetup) {
                        item { SetupCard(onClick = { showSources = true }) }
                    } else {
                        val hero = state.popular.take(6)
                        if (hero.isNotEmpty()) item { Hero(hero, onOpen = openManga) }
                    }
                    if (state.newInLibrary.isNotEmpty()) {
                        item {
                            Section(stringResource(ATMR.strings.home_new_in_library)) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(state.newInLibrary, key = { it.chapterId }) { update ->
                                        PosterCard(
                                            cover = update.coverData,
                                            title = update.mangaTitle,
                                            subtitle = update.chapterName,
                                            badge = stringResource(ATMR.strings.home_new_badge),
                                            onClick = {
                                                context.startActivity(
                                                    ReaderActivity.newIntent(context, update.mangaId, update.chapterId),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.continueReading.isNotEmpty()) {
                        item {
                            Section(
                                title = stringResource(ATMR.strings.home_continue),
                                onSeeAll = { tabNavigator.current = HistoryTab },
                            ) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(state.continueReading, key = { it.id }) { history ->
                                        PosterCard(
                                            cover = history.coverData,
                                            title = history.title,
                                            subtitle = stringResource(
                                                ATMR.strings.home_chapter,
                                                formatChapter(history.chapterNumber),
                                            ),
                                            onClick = {
                                                context.startActivity(
                                                    ReaderActivity.newIntent(context, history.mangaId, history.chapterId),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!state.needsSetup) {
                        item {
                            SourceChips(
                                feeds = state.feeds,
                                selected = state.sourceFilter,
                                onSelect = viewModel::setSourceFilter,
                            )
                        }
                        item {
                            Section(stringResource(ATMR.strings.home_latest)) {
                                FeedRow(state.latest, state.feeds.any { it.loading }, onOpen = openManga)
                            }
                        }
                        item {
                            Section(stringResource(ATMR.strings.home_popular)) {
                                FeedRow(state.popular, state.feeds.any { it.loading }, onOpen = openManga)
                            }
                        }
                        state.visibleFeeds.filter { it.failed }.forEach { feed ->
                            item(key = "failed-${feed.source.id}") {
                                Text(
                                    stringResource(ATMR.strings.home_source_failed, feed.source.name),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        state.visibleFeeds.forEach { feed ->
                            item(key = "source-${feed.source.id}") {
                                Section(
                                    title = feed.source.name,
                                    onSeeAll = { navigator.push(BrowseSourceScreen(feed.source.id, null)) },
                                ) {
                                    FeedRow(feed.latest.map { it to feed.source }, feed.loading, onOpen = openManga)
                                }
                            }
                        }
                    }
                    if (state.categories.isNotEmpty()) {
                        item {
                            Section(stringResource(ATMR.strings.home_my_lists)) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(state.categories, key = { it.id }) { category ->
                                        Text(
                                            category.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier
                                                .glass(RoundedCornerShape(50))
                                                .clickable { tabNavigator.current = LibraryTab }
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { AiCard(onClick = { navigator.push(AiChatScreen()) }) }
                }
            }
        }

        if (showSources) {
            SourcesSheet(
                sources = state.availableSources,
                selected = state.selectedSourceIds,
                onDone = {
                    viewModel.setSources(it)
                    showSources = false
                },
                onDismiss = { showSources = false },
            )
        }
    }

    @Composable
    private fun TopBar(onSearch: () -> Unit, onSources: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "MANHWA",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(onClick = onSearch) { Icon(MaterialSymbols.Rounded.Search, contentDescription = null) }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(onClick = onSources) { Icon(MaterialSymbols.Rounded.Explore, contentDescription = null) }
        }
    }

    @Composable
    private fun GlassIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            modifier = Modifier.size(44.dp).glass(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    @Composable
    private fun SetupCard(onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), accent = true)
                .clickable(onClick = onClick)
                .padding(20.dp),
        ) {
            Text(
                stringResource(ATMR.strings.home_setup_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(ATMR.strings.home_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            RedButton(stringResource(ATMR.strings.home_my_sources), onClick)
        }
    }

    @Composable
    private fun RedButton(text: String, onClick: () -> Unit) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF8E0F1F))))
                .clickable(onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }

    @Composable
    private fun Hero(items: List<Pair<Manga, Source>>, onOpen: (Manga) -> Unit) {
        val listState = rememberLazyListState()
        LaunchedEffect(items.size) {
            while (items.size > 1) {
                delay(5_000)
                listState.animateScrollToItem((listState.firstVisibleItemIndex + 1) % items.size)
            }
        }
        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.first.id }) { (manga, source) ->
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(200.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
                        .clickable { onOpen(manga) },
                ) {
                    MangaCover.Book(
                        data = manga.asMangaCover(),
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(0.dp),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE050507))),
                        ),
                    )
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        SourceBadge(source.name)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            manga.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        RedButton(stringResource(ATMR.strings.home_read)) { onOpen(manga) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, onSeeAll: (() -> Unit)? = null, content: @Composable () -> Unit) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onSeeAll != null) {
                    TextButton(onClick = onSeeAll) { Text(stringResource(ATMR.strings.home_see_all)) }
                }
            }
            content()
        }
    }

    @Composable
    private fun SourceChips(feeds: List<DiscoverViewModel.SourceFeed>, selected: Long?, onSelect: (Long?) -> Unit) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Chip(stringResource(ATMR.strings.home_all), selected == null) { onSelect(null) } }
            items(feeds, key = { it.source.id }) { feed ->
                Chip(feed.source.name, selected == feed.source.id) { onSelect(feed.source.id) }
            }
        }
    }

    @Composable
    private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(50)
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .then(
                    if (selected) {
                        Modifier.clip(shape).background(
                            Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF8E0F1F))),
                        )
                    } else {
                        Modifier.glass(shape)
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }

    @Composable
    private fun FeedRow(items: List<Pair<Manga, Source>>, loading: Boolean, onOpen: (Manga) -> Unit) {
        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            return
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.first.id }) { (manga, source) ->
                PosterCard(
                    cover = manga.asMangaCover(),
                    title = manga.title,
                    subtitle = null,
                    badge = source.name,
                    onClick = { onOpen(manga) },
                )
            }
        }
    }

    @Composable
    private fun PosterCard(cover: Any, title: String, subtitle: String?, badge: String? = null, onClick: () -> Unit) {
        Column(modifier = Modifier.width(128.dp).clickable(onClick = onClick)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
            ) {
                MangaCover.Book(data = cover, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(18.dp))
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC050507)),
                    ),
                )
                if (badge != null) {
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { SourceBadge(badge) }
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }

    @Composable
    private fun SourceBadge(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }

    @Composable
    private fun AiCard(onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), accent = true)
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF7A0A2E))),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) { Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = Color.White) }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(ATMR.strings.home_ai_card), style = MaterialTheme.typography.titleSmall)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SourcesSheet(
        sources: List<Source>,
        selected: Set<String>,
        onDone: (Set<String>) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var picked by remember { mutableStateOf(selected) }
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(ATMR.strings.home_setup_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(ATMR.strings.home_selected_count, picked.size),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                RedButton(stringResource(ATMR.strings.home_done)) { onDone(picked) }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                items(sources, key = { it.id }) { source ->
                    val id = "${source.id}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = if (id in picked) picked - id else picked + id }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = id in picked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                source.lang.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun formatChapter(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}
