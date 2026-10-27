package eu.kanade.tachiyomi.ui.home.discover

import eu.kanade.presentation.components.ManhwaInk

import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.Dp
import eu.kanade.presentation.components.neonGlow
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.Visibility
import mihon.icons.materialsymbols.roundedfilled.VisibilityOff
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.home.hub.SourceHubScreen
import eu.kanade.tachiyomi.ui.home.smart.SmartDownloadScreen
import eu.kanade.tachiyomi.ui.home.wrapped.WrappedScreen
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.QueryStats
import mihon.icons.materialsymbols.rounded.Storage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.translation.search.AiChatScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Menu
import mihon.icons.materialsymbols.rounded.MilitaryTech
import mihon.icons.materialsymbols.rounded.Notifications
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Verified
import mihon.icons.materialsymbols.roundedfilled.Bolt
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import mihon.icons.materialsymbols.roundedfilled.Star
import mihon.icons.materialsymbols.roundedfilled.Whatshot
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalTime
import kotlin.math.abs
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState

/**
 * MANHWA home: a cinematic, magazine-style front page built from the reader's chosen sources and library.
 * Nothing here needs opening a source: hero, top 10, genres, completed series and per-source rails are all
 * gathered from the sources picked in "My sources".
 */
data object DiscoverTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 5u,
            title = stringResource(ATMR.strings.home_tab),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Home),
        )

    private val Ink = Color(0xFF050507)

    /** Manhwa level cards shown on the home dashboard (the rest are behind "View all"). */
    private const val DASHBOARD_LEVELS = 3
    private const val LEVEL_UP_SHOWN_MS = 8_000L

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val context = LocalContext.current
        // The home is the first screen: release the system splash as soon as it is drawn
        LaunchedEffect(Unit) { (context as? eu.kanade.tachiyomi.ui.main.MainActivity)?.ready = true }
        val viewModel = metroViewModel<DiscoverViewModel>()
        val state by viewModel.state.collectAsState()
        val uiPreferences = remember { context.appGraph.uiPreferences }
        val accent by uiPreferences.manhwaAccent.collectPrefAsState()
        val appTheme by uiPreferences.appTheme.collectPrefAsState()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val openHub = { navigator.push(SourceHubScreen()) }
        // MANHWA: the feature tour, replayed from the menu
        var showTour by remember { androidx.compose.runtime.mutableStateOf(false) }
        if (showTour) eu.kanade.translation.whatsnew.FeatureTourDialog(onDismiss = { showTour = false })
        val listState = rememberLazyListState()
        val shown by remember {
            combine(HomeSection.entries.map { uiPreferences.homeSection(it.key, it.shownByDefault).changes() }) { on ->
                HomeSection.entries.filterIndexedTo(mutableSetOf()) { i, _ -> on[i] }
            }
        }.collectAsState(
            initial = remember {
                HomeSection.entries.filterTo(mutableSetOf()) { uiPreferences.homeSection(it.key, it.shownByDefault).get() }
            },
        )
        val solidHeader by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 420 }
        }

        val openManga = { manga: Manga -> navigator.push(MangaScreen(manga.id, true)) }
        val openReader = { mangaId: Long, chapterId: Long ->
            context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
        }
        val askAi = { prompt: String? -> navigator.push(AiChatScreen(prompt)) }
        val closeDrawerThen = { action: () -> Unit ->
            scope.launch { drawerState.close() }
            action()
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SideMenu(
                    accent = accent,
                    showAccent = appTheme == eu.kanade.domain.ui.model.AppTheme.DANGHWA,
                    onAccent = { uiPreferences.manhwaAccent.set(it) },
                    onClose = { scope.launch { drawerState.close() } },
                    entries = listOf(
                        MenuEntry(MaterialSymbols.Rounded.Home, stringResource(ATMR.strings.home_tab), true) {
                            closeDrawerThen {}
                        },
                        MenuEntry(MaterialSymbols.Rounded.LocalLibrary, stringResource(ATMR.strings.home_library)) {
                            closeDrawerThen { tabNavigator.current = LibraryTab }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Schedule, stringResource(ATMR.strings.home_history)) {
                            closeDrawerThen { tabNavigator.current = HistoryTab }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Explore, stringResource(ATMR.strings.home_browse)) {
                            closeDrawerThen { tabNavigator.current = BrowseTab }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Download, stringResource(ATMR.strings.home_downloads)) {
                            closeDrawerThen { navigator.push(DownloadQueueScreen) }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Schedule, stringResource(ATMR.strings.schedule_title)) {
                            closeDrawerThen { navigator.push(eu.kanade.tachiyomi.ui.home.schedule.ScheduleScreen()) }
                        },
                        MenuEntry(MaterialSymbols.Rounded.EditNote, stringResource(ATMR.strings.notes_title)) {
                            closeDrawerThen { navigator.push(eu.kanade.tachiyomi.ui.reader.notes.PageNotesScreen()) }
                        },
                        MenuEntry(MaterialSymbols.Rounded.AutoAwesome, stringResource(ATMR.strings.tour_replay)) {
                            closeDrawerThen { showTour = true }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Public, stringResource(ATMR.strings.hub_title)) {
                            closeDrawerThen { openHub() }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Storage, stringResource(ATMR.strings.smart_title)) {
                            closeDrawerThen { navigator.push(SmartDownloadScreen()) }
                        },
                        MenuEntry(MaterialSymbols.Rounded.QueryStats, stringResource(ATMR.strings.wrapped_title)) {
                            closeDrawerThen { navigator.push(WrappedScreen()) }
                        },
                        // MANHWA: everything about translation, one tap from the menu
                        MenuEntry(MaterialSymbols.Rounded.Translate, stringResource(ATMR.strings.th_title)) {
                            closeDrawerThen { navigator.push(eu.kanade.translation.settings.TranslationHubScreen()) }
                        },
                        MenuEntry(MaterialSymbols.Rounded.Settings, stringResource(ATMR.strings.home_settings)) {
                            closeDrawerThen { navigator.push(SettingsScreen()) }
                        },
                    ),
                    onAskAi = { closeDrawerThen { askAi(null) } },
                )
            },
        ) {
            GlassBackdrop {
                // MANHWA: the pull draws a ring of light, and a short buzz says the refresh started
                val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val pullHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = {
                        pullHaptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        viewModel.refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                    state = pullState,
                    indicator = { eu.kanade.presentation.components.GlowRefreshIndicator(pullState, state.refreshing) },
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                        // 1. Cinematic hero (the setup card always shows; hidden hero leaves room for the header)
                        if (!state.needsSetup && HomeSection.HERO !in shown) {
                            item(key = "top") { Spacer(Modifier.statusBarsPadding().height(76.dp)) }
                        } else item(key = "hero") {
                            val hero = state.trending.take(7)
                            when {
                                state.needsSetup -> Column {
                                    Spacer(Modifier.statusBarsPadding().height(76.dp))
                                    SetupCard(onClick = { openHub() })
                                }
                                hero.isNotEmpty() -> CinematicHero(
                                    items = hero,
                                    onOpen = openManga,
                                    onAsk = { askAi(it) },
                                )
                                else -> HeroPlaceholder()
                            }
                        }

                        // 2. Quick actions
                        if (HomeSection.QUICK in shown) item(key = "quick") {
                            QuickActions(
                                listOf(
                                    QuickAction(MaterialSymbols.Rounded.LocalLibrary, stringResource(ATMR.strings.home_library), Color(0xFFFF5A6A)) {
                                        tabNavigator.current = LibraryTab
                                    },
                                    QuickAction(MaterialSymbols.Rounded.Schedule, stringResource(ATMR.strings.home_history), Color(0xFF4FC3F7)) {
                                        tabNavigator.current = HistoryTab
                                    },
                                    QuickAction(MaterialSymbols.Rounded.Explore, stringResource(ATMR.strings.home_my_sources), Color(0xFF3DDC97)) {
                                        openHub()
                                    },
                                    QuickAction(MaterialSymbols.RoundedFilled.Whatshot, stringResource(ATMR.strings.wrapped_title), Color(0xFFFF4FA3)) {
                                        navigator.push(WrappedScreen())
                                    },
                                    QuickAction(MaterialSymbols.Rounded.Storage, stringResource(ATMR.strings.smart_title), Color(0xFF00D1E0)) {
                                        navigator.push(SmartDownloadScreen())
                                    },
                                    QuickAction(MaterialSymbols.Rounded.Download, stringResource(ATMR.strings.home_downloads), Color(0xFFB388FF)) {
                                        navigator.push(DownloadQueueScreen)
                                    },
                                ),
                            )
                        }

                        // Dashboard
                        if (HomeSection.DASHBOARD in shown) item(key = "dashboard") {
                            Dashboard(
                                stats = state.stats,
                                newCount = state.newInLibrary.size,
                                game = state.game,
                                onViewAll = { navigator.push(eu.kanade.tachiyomi.ui.home.stats.ReadingDashboardScreen()) },
                                onOpen = { id -> navigator.push(MangaScreen(id)) },
                                onContinue = { id ->
                                    scope.launch {
                                        val chapter = runCatching { viewModel.nextChapter(id) }.getOrNull()
                                        if (chapter != null) {
                                            context.startActivity(
                                                eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                            )
                                        } else {
                                            navigator.push(MangaScreen(id))
                                        }
                                    }
                                },
                            )
                        }

                        // Quick toggles
                        if (HomeSection.TOGGLES in shown) item(key = "toggles") { QuickToggles() }

                        // Smart alerts: a newer chapter on another source
                        if (HomeSection.ALERTS in shown && state.alerts.isNotEmpty()) {
                            item(key = "alerts") {
                                Section(
                                    title = stringResource(ATMR.strings.alert_title),
                                    subtitle = stringResource(ATMR.strings.alert_sub),
                                    icon = MaterialSymbols.Rounded.Notifications,
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        state.alerts.forEach { alert ->
                                            AlertCard(alert) {
                                                viewModel.openAlert(
                                                    alert,
                                                    open = { mangaId, chapterId -> openReader(mangaId, chapterId) },
                                                    fallback = { navigator.push(MangaScreen(it, true)) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Continue reading
                        if (HomeSection.CONTINUE in shown && state.continueReading.isNotEmpty()) {
                            item(key = "continue") {
                                Section(
                                    title = stringResource(ATMR.strings.home_continue),
                                    subtitle = stringResource(ATMR.strings.home_continue_sub),
                                    onSeeAll = { tabNavigator.current = HistoryTab },
                                ) {
                                    val first = state.continueReading.first()
                                    NowReadingCard(
                                        cover = first.coverData,
                                        title = first.title,
                                        chapter = formatChapter(first.chapterNumber),
                                        whenText = first.readAt?.let { relative(it.toEpochMilliseconds()) },
                                        progress = state.progress?.takeIf { it.mangaId == first.mangaId },
                                        onContinue = { openReader(first.mangaId, first.chapterId) },
                                        onDetails = { navigator.push(MangaScreen(first.mangaId)) },
                                        onAsk = { askAi(first.title) },
                                    )
                                    val rest = state.continueReading.drop(1)
                                    if (rest.isNotEmpty()) {
                                        Spacer(Modifier.height(14.dp))
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(rest, key = { it.id }) { history ->
                                                WideCard(
                                                    cover = history.coverData,
                                                    title = history.title,
                                                    chip = stringResource(
                                                        ATMR.strings.home_chapter,
                                                        formatChapter(history.chapterNumber),
                                                    ),
                                                    onClick = { openReader(history.mangaId, history.chapterId) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. New chapters of every series on the home sources (and of the library)
                        if (HomeSection.NEW in shown && (state.fresh.isNotEmpty() || state.newInLibrary.isNotEmpty() || state.freshRunning)) {
                            item(key = "new") {
                                FreshSection(
                                    state = state,
                                    onSeeAll = { navigator.push(eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersScreen()) },
                                    onOpenFresh = { viewModel.openFresh(it, openReader) { id -> navigator.push(MangaScreen(id, true)) } },
                                    onOpenUpdate = { update -> openReader(update.mangaId, update.chapterId) },
                                    onOpenManga = { id -> navigator.push(MangaScreen(id, true)) },
                                )
                            }
                        }

                        if (!state.needsSetup) {
                            // 5. Top 10
                            val top = state.trending.take(10)
                            if (HomeSection.TOP10 in shown && top.isNotEmpty()) {
                                item(key = "top10") {
                                    Section(
                                        title = stringResource(ATMR.strings.home_top10),
                                        subtitle = stringResource(ATMR.strings.home_top10_sub),
                                        icon = MaterialSymbols.RoundedFilled.Whatshot,
                                    ) {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            itemsIndexed(top, key = { _, it -> it.first.id }) { index, pair ->
                                                RankCard(rank = index + 1, manga = pair.first, onClick = { openManga(pair.first) })
                                            }
                                        }
                                    }
                                }
                            }

                            // AI picks with the reason for each one
                            if (HomeSection.RECS in shown) item(key = "recs") {
                                Recommendations(
                                    state = state,
                                    onOpen = openManga,
                                    onRetry = viewModel::refreshRecommendations,
                                    onSetup = { navigator.push(eu.kanade.translation.team.TranslationTeamScreen()) },
                                )
                            }

                            // 6. All sources: latest / popular grid
                            if (HomeSection.FEED in shown) item(key = "feed") {
                                SourcesFeed(state = state, onFilter = viewModel::setSourceFilter, onOpen = openManga)
                            }

                            // 7. Genres
                            if (HomeSection.GENRES in shown) item(key = "genres") {
                                GenreExplorer(all = state.latest + state.popular, onOpen = openManga)
                            }

                            // 8. Completed series
                            val completed = (state.popular + state.latest)
                                .filter { it.first.status.isCompleted() }
                                .distinctBy { it.first.id }
                            if (HomeSection.COMPLETED in shown && completed.isNotEmpty()) {
                                item(key = "completed") {
                                    Section(
                                        title = stringResource(ATMR.strings.home_completed),
                                        subtitle = stringResource(ATMR.strings.home_completed_sub),
                                        icon = MaterialSymbols.Rounded.Verified,
                                    ) {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            items(completed, key = { it.first.id }) { pair ->
                                                PosterCard(pair.first, pair.second.name, width = 150, onClick = { openManga(pair.first) })
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 9. MANHWA AI
                        if (HomeSection.AI in shown) item(key = "ai") { AiPanel(onAsk = askAi) }

                        if (!state.needsSetup && HomeSection.RAILS in shown) {
                            // 10. One rail per source
                            state.visibleFeeds.forEach { feed ->
                                item(key = "source-${feed.source.id}") {
                                    Section(
                                        title = feed.source.name,
                                        subtitle = if (feed.failed) {
                                            stringResource(ATMR.strings.home_source_failed, feed.source.name)
                                        } else {
                                            stringResource(ATMR.strings.home_rail_sub)
                                        },
                                        onSeeAll = { navigator.push(BrowseSourceScreen(feed.source.id, null)) },
                                    ) {
                                        if (feed.loading) {
                                            Loading()
                                        } else {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                items(feed.latest, key = { it.id }) { manga ->
                                                    PosterCard(manga, null, width = 124, onClick = { openManga(manga) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 11. My lists
                        if (HomeSection.LISTS in shown && state.categories.isNotEmpty()) {
                            item(key = "lists") {
                                Section(
                                    title = stringResource(ATMR.strings.home_my_lists),
                                    subtitle = stringResource(ATMR.strings.home_lists_sub),
                                ) {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        itemsIndexed(state.categories, key = { _, it -> it.id }) { index, category ->
                                            ListTile(
                                                name = category.name,
                                                hue = TileHues[index % TileHues.size],
                                                onClick = { tabNavigator.current = LibraryTab },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Header(
                    solid = solidHeader,
                    newCount = state.newInLibrary.size,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSearch = { navigator.push(GlobalSearchScreen("")) },
                    onBell = { navigator.push(eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersScreen()) },
                )
            }
        }

    }

    private fun Long.isCompleted(): Boolean {
        val status = toInt()
        return status == SManga.COMPLETED || status == SManga.PUBLISHING_FINISHED
    }

    // region Smart alerts & recommendations

    @Composable
    private fun AlertCard(alert: HomeIntelligence.SourceAlert, onOpen: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val pulse by rememberInfiniteTransition(label = "alert").animateFloat(
            initialValue = 0.15f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
            label = "pulse",
        )
        val number = formatChapter(alert.chapterNumber)
        val message = when (val days = alert.daysAgo) {
            null, 0 -> stringResource(ATMR.strings.alert_message_today, number, alert.libraryManga.title, alert.source.name)
            else -> stringResource(ATMR.strings.alert_message_days, number, alert.libraryManga.title, alert.source.name, days)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neonGlow(RoundedCornerShape(24.dp), colors.primary, { pulse }, 12.dp)
                .glass(RoundedCornerShape(24.dp), accent = true, alpha = 0.5f)
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(
                alert.libraryManga.asMangaCover(),
                Modifier.width(62.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ManhwaInk.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(ATMR.strings.alert_yours, formatChapter(alert.ourNumber)),
                    style = MaterialTheme.typography.labelSmall,
                    color = ManhwaInk.text.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(10.dp))
                AccentButton(
                    stringResource(ATMR.strings.alert_open, number),
                    MaterialSymbols.RoundedFilled.PlayArrow,
                    Modifier.neonGlow(RoundedCornerShape(14.dp), colors.primary, 0.35f, 10.dp),
                    onOpen,
                )
            }
        }
    }

    @Composable
    private fun Recommendations(
        state: DiscoverViewModel.State,
        onOpen: (Manga) -> Unit,
        onRetry: () -> Unit,
        onSetup: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Section(
            title = stringResource(ATMR.strings.recs_title),
            subtitle = stringResource(ATMR.strings.recs_sub),
            icon = MaterialSymbols.Rounded.AutoAwesome,
        ) {
            when {
                state.recommendations.isNotEmpty() -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.recommendations, key = { it.manga.id }) { rec ->
                        Column(
                            modifier = Modifier
                                .width(230.dp)
                                .glass(RoundedCornerShape(24.dp), alpha = 0.45f)
                                .clickable { onOpen(rec.manga) }
                                .padding(10.dp),
                        ) {
                            Row {
                                Cover(
                                    rec.manga.asMangaCover(),
                                    Modifier.width(86.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        rec.manga.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    SourceBadge(rec.source.name)
                                    val genres = rec.manga.genre.orEmpty().take(2)
                                    if (genres.isNotEmpty()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            genres.joinToString(" • "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            if (rec.reason.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.primary.copy(alpha = 0.14f))
                                        .border(1.dp, colors.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                        .padding(10.dp),
                                ) {
                                    Icon(
                                        MaterialSymbols.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        rec.reason,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.9f),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                state.recsLoading -> Loading()
                state.recsProblem == DiscoverViewModel.RecsProblem.NOT_CONFIGURED -> InfoCard(
                    text = stringResource(ATMR.strings.recs_setup),
                    button = stringResource(ATMR.strings.recs_setup_btn),
                    onClick = onSetup,
                )
                state.recsProblem == DiscoverViewModel.RecsProblem.FAILED -> InfoCard(
                    text = stringResource(ATMR.strings.recs_failed),
                    button = stringResource(ATMR.strings.recs_retry),
                    onClick = onRetry,
                )
                else -> Loading()
            }
        }
    }

    @Composable
    private fun InfoCard(text: String, button: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.45f)
                .padding(18.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text.copy(alpha = 0.85f))
            Spacer(Modifier.height(12.dp))
            AccentButton(button, MaterialSymbols.Rounded.AutoAwesome, Modifier, onClick)
        }
    }

    // endregion

    // region Header & side menu

    @Composable
    private fun Header(solid: Boolean, newCount: Int, onMenu: () -> Unit, onSearch: () -> Unit, onBell: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val bg by animateColorAsState(if (solid) ManhwaInk.background.copy(alpha = 0.95f) else Color.Transparent, label = "header")
        Column(modifier = Modifier.fillMaxWidth().background(bg)) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderButton(MaterialSymbols.Rounded.Menu, onMenu)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "MANHWA",
                        style = MaterialTheme.typography.titleLarge.copy(
                            shadow = Shadow(colors.primary, blurRadius = 28f),
                        ),
                        fontWeight = FontWeight.Black,
                        color = colors.primary,
                        letterSpacing = 4.sp,
                    )
                    Text(
                        stringResource(
                            if (LocalTime.now().hour in 5..16) {
                                ATMR.strings.home_greeting_morning
                            } else {
                                ATMR.strings.home_greeting_evening
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = ManhwaInk.text.copy(alpha = 0.6f),
                    )
                }
                // MANHWA: what is being translated right now, one tap from home
                eu.kanade.translation.queue.TranslatingNowOrb()
                Spacer(Modifier.width(10.dp))
                Box {
                    HeaderButton(MaterialSymbols.Rounded.Notifications, onBell)
                    if (newCount > 0) {
                        Text(
                            if (newCount > 99) "99+" else "$newCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ManhwaInk.text,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .neonGlow(CircleShape, colors.primary, 0.35f, 6.dp)
                                .background(colors.primary, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            // Glass search bar with a glowing search icon
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp, top = 2.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .glass(RoundedCornerShape(18.dp), accent = true, alpha = 0.5f)
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MaterialSymbols.Rounded.Search,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp).neonGlow(CircleShape, colors.primary, 0.25f, 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(ATMR.strings.home_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ManhwaInk.text.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    MaterialSymbols.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = colors.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (solid) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp).background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, colors.primary.copy(alpha = 0.7f), Color.Transparent),
                        ),
                    ),
                )
            }
        }
    }

    @Composable
    private fun HeaderButton(icon: ImageVector, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .glass(RoundedCornerShape(14.dp), alpha = 0.45f)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = ManhwaInk.text, modifier = Modifier.size(22.dp)) }
    }

    private class MenuEntry(
        val icon: ImageVector,
        val label: String,
        val selected: Boolean = false,
        val onClick: () -> Unit,
    )

    private val AccentChoices = listOf(
        0xFFFF3B4E, 0xFFE50914, 0xFFFF7A1A, 0xFFFFC233, 0xFF3DDC97,
        0xFF00D1E0, 0xFF4F8BFF, 0xFF9B5CFF, 0xFFFF4FA3, 0xFFF1E9EA,
    ).map { it.toInt() }

    @Composable
    private fun SideMenu(
        accent: Int,
        showAccent: Boolean,
        onAccent: (Int) -> Unit,
        onClose: () -> Unit,
        entries: List<MenuEntry>,
        onAskAi: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        ModalDrawerSheet(
            drawerContainerColor = ManhwaInk.surface,
            drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(listOf(Ink, lerp(colors.primary, Color.Black, 0.5f), colors.primary)),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.manhwa_logo),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().padding(2.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "MANHWA",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = colors.primary,
                        letterSpacing = 3.sp,
                        modifier = Modifier.weight(1f),
                    )
                    HeaderButton(MaterialSymbols.Rounded.Close, onClose)
                }
                Spacer(Modifier.height(22.dp))
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (entry.selected) Modifier.glass(RoundedCornerShape(16.dp), accent = true) else Modifier,
                            )
                            .clickable(onClick = entry.onClick)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = if (entry.selected) colors.primary else Color.White.copy(alpha = 0.75f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (entry.selected) FontWeight.Bold else FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                AccentButton(
                    stringResource(ATMR.strings.home_ask_ai),
                    MaterialSymbols.Rounded.AutoAwesome,
                    Modifier.fillMaxWidth(),
                    onAskAi,
                )
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                Spacer(Modifier.height(16.dp))
                if (showAccent) {
                    Text(
                        stringResource(ATMR.strings.home_appearance),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                    AccentChoices.chunked(5).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(bottom = 10.dp),
                        ) {
                            row.forEach { choice ->
                                val selected = choice == accent
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .then(
                                            if (selected) {
                                                Modifier.border(2.dp, Color.White, RoundedCornerShape(13.dp))
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(if (selected) 4.dp else 0.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(choice))
                                        .clickable { onAccent(choice) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region Hero

    @Composable
    private fun CinematicHero(items: List<Pair<Manga, Source>>, onOpen: (Manga) -> Unit, onAsk: (String) -> Unit) {
        val pager = rememberPagerState { items.size }
        LaunchedEffect(pager, items.size) {
            while (items.size > 1) {
                delay(6_500)
                pager.animateScrollToPage((pager.currentPage + 1) % items.size)
            }
        }
        val colors = MaterialTheme.colorScheme
        Box(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            // One blurred backdrop for the whole hero, cross-fading between titles: it never splits while swiping
            Crossfade(
                targetState = items.getOrNull(pager.currentPage)?.first,
                animationSpec = tween(700),
                label = "heroBackdrop",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Fade the art out at the bottom so it melts into the screen, no hard edge
                        drawRect(
                            Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) { current ->
                if (current != null) {
                    Cover(
                        data = current.asMangaCover(),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.25f
                                scaleY = 1.25f
                            }
                            .blur(18.dp),
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Ink.copy(alpha = 0.8f),
                        0.32f to Ink.copy(alpha = 0.2f),
                        0.72f to Ink.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = 0.22f), Color.Transparent),
                        radius = 900f,
                    ),
                ),
            )
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val manga = items[page].first
                val source = items[page].second
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                Box(modifier = Modifier.fillMaxSize().clickable { onOpen(manga) }) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 40.dp)
                            .graphicsLayer {
                                alpha = 1f - abs(offset).coerceIn(0f, 1f)
                                translationX = -offset * 160f
                            },
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Cover(
                                data = manga.asMangaCover(),
                                modifier = Modifier
                                    .width(124.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .border(
                                        1.5.dp,
                                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), colors.primary)),
                                        RoundedCornerShape(18.dp),
                                    ),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SourceBadge(source.name)
                                    StatusPill(manga.status)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    manga.title,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 16f),
                                    ),
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val genres = manga.genre.orEmpty().filter { it.isNotBlank() }.take(3)
                                if (genres.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        genres.joinToString("  •  "),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        val description = manga.description?.trim().orEmpty()
                        if (description.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SolidWhiteButton(stringResource(ATMR.strings.home_read), Modifier.weight(1f)) { onOpen(manga) }
                            GhostButton(
                                stringResource(ATMR.strings.home_ai_title),
                                MaterialSymbols.Rounded.AutoAwesome,
                                Modifier.weight(1f),
                            ) { onAsk(manga.title) }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(items.size) { i ->
                    val selected = i == pager.currentPage
                    val width by animateDpAsState(if (selected) 26.dp else 6.dp, label = "dot")
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(if (selected) colors.primary else ManhwaInk.text.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }

    @Composable
    private fun HeroPlaceholder() {
        val heroPrimary = MaterialTheme.colorScheme.primary
        val pulse by rememberInfiniteTransition(label = "hero").animateFloat(
            initialValue = 0.25f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(470.dp)
                .drawBehind {
                    // Read while drawing: the pulse repaints, nothing recomposes
                    drawRect(Brush.verticalGradient(listOf(heroPrimary.copy(alpha = pulse * 0.4f), Ink)))
                },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
    }

    // endregion

    // region Sections

    private class QuickAction(val icon: ImageVector, val label: String, val tint: Color, val onClick: () -> Unit)

    @Composable
    private fun QuickActions(actions: List<QuickAction>) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(actions, key = { it.label }) { action ->
                Row(
                    modifier = Modifier
                        .glass(RoundedCornerShape(18.dp))
                        .clickable(onClick = action.onClick)
                        .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(action.tint.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(action.icon, contentDescription = null, tint = action.tint, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = ManhwaInk.text,
                    )
                }
            }
        }
    }

    @Composable
    private fun Section(
        title: String,
        subtitle: String? = null,
        icon: ImageVector? = null,
        onSeeAll: (() -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(40.dp)
                        .background(
                            Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.15f))),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (icon != null) {
                            Spacer(Modifier.width(6.dp))
                            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (onSeeAll != null) {
                    Row(
                        modifier = Modifier
                            .glass(RoundedCornerShape(50), alpha = 0.35f)
                            .clickable(onClick = onSeeAll)
                            .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(ATMR.strings.home_see_all),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            MaterialSymbols.AutoMirroredRounded.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            content()
        }
    }

    @Composable
    private fun NowReadingCard(
        cover: Any,
        title: String,
        chapter: String,
        whenText: String?,
        progress: DiscoverViewModel.Progress?,
        onContinue: () -> Unit,
        onDetails: () -> Unit,
        onAsk: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(28.dp)
        val fraction = progress?.let { if (it.total > 0) it.read.toFloat() / it.total else 0f } ?: 0f
        val animated by animateFloatAsState(fraction, animationSpec = tween(900), label = "progress")
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .neonGlow(shape, colors.primary, 0.22f, 14.dp)
                .clip(shape)
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), colors.primary, Color.White.copy(alpha = 0.05f))), shape),
        ) {
            Cover(cover, Modifier.matchParentSize().blur(30.dp).graphicsLayer { alpha = 0.6f })
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(Ink.copy(alpha = 0.55f), Ink.copy(alpha = 0.9f))),
                ),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(0f to Color.White.copy(alpha = 0.10f), 0.4f to Color.Transparent),
                ),
            )
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(
                        cover,
                        Modifier
                            .size(92.dp)
                            .neonGlow(RoundedCornerShape(20.dp), colors.primary, 0.3f, 10.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, colors.primary.copy(alpha = 0.8f), RoundedCornerShape(20.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(ATMR.strings.home_now_reading),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(stringResource(ATMR.strings.home_chapter, chapter), whenText).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Glowing progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animated.coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .background(
                                Brush.horizontalGradient(listOf(lerp(colors.primary, Color.Black, 0.3f), colors.primary, Color.White)),
                                RoundedCornerShape(50),
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        progress?.let { stringResource(ATMR.strings.home_progress, it.read, it.total) } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Player controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(MaterialSymbols.Rounded.Info, 48.dp, false, onDetails)
                    RoundControl(MaterialSymbols.RoundedFilled.PlayArrow, 70.dp, true, onContinue)
                    RoundControl(MaterialSymbols.Rounded.AutoAwesome, 48.dp, false, onAsk)
                }
            }
        }
    }

    @Composable
    private fun RoundControl(icon: ImageVector, size: Dp, primary: Boolean, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .size(size)
                .then(
                    if (primary) {
                        Modifier
                            .neonGlow(CircleShape, colors.primary, 0.4f, 16.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.45f))))
                            .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    } else {
                        Modifier.glass(CircleShape, alpha = 0.4f)
                    },
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (primary) Color.White else colors.primary,
                modifier = Modifier.size(if (primary) 36.dp else 22.dp),
            )
        }
    }

    @Composable
    private fun Dashboard(
        stats: DiscoverViewModel.Stats,
        newCount: Int,
        game: eu.kanade.tachiyomi.ui.home.stats.ReadingProgressEngine.Snapshot?,
        onViewAll: () -> Unit,
        onOpen: (Long) -> Unit,
        onContinue: (Long) -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        val total = stats.week.sum()
        // MANHWA: the real streak from the whole reading history (the week alone stopped at 7 days)
        val streak = game?.streak?.days ?: stats.week.reversed().takeWhile { it > 0 }.size
        Section(
            title = stringResource(ATMR.strings.home_dashboard),
            subtitle = stringResource(ATMR.strings.home_dashboard_sub),
            onSeeAll = onViewAll,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(26.dp), accent = true, alpha = 0.45f)
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(ATMR.strings.home_week),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Text(
                            stringResource(ATMR.strings.home_week_series, total),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                shadow = Shadow(colors.primary.copy(alpha = 0.6f), blurRadius = 20f),
                            ),
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                    }
                    val minutes = stats.weekReadMs / 60_000
                    Text(
                        stringResource(ATMR.strings.home_time, (minutes / 60).toInt(), (minutes % 60).toInt()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = colors.primary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                WeekChart(stats.week)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(MaterialSymbols.Rounded.LocalLibrary, "${stats.libraryCount}", stringResource(ATMR.strings.home_in_library), Modifier.weight(1f))
                StatTile(MaterialSymbols.RoundedFilled.Bolt, "$newCount", stringResource(ATMR.strings.home_new_count), Modifier.weight(1f))
                StatTile(MaterialSymbols.RoundedFilled.Whatshot, "$streak", stringResource(ATMR.strings.home_streak), Modifier.weight(1f))
            }
            // MANHWA reading game: hunter rank, streak stages and the level of the manhwa being read
            if (game != null) {
                var details by remember { mutableStateOf<eu.kanade.tachiyomi.ui.home.stats.ReadingProgressEngine.Series?>(null) }
                details?.let { series ->
                    eu.kanade.tachiyomi.ui.home.stats.SeriesDetailsSheet(
                        series = series,
                        onDismiss = { details = null },
                        onContinue = {
                            details = null
                            onContinue(series.manga.id)
                        },
                        onOpen = {
                            details = null
                            onOpen(series.manga.id)
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // A level that went up shows like a notification: a few seconds, then it fades away for good
                    val levelUps = game.series.filter { it.leveledUpAt > 0 }.sortedByDescending { it.leveledUpAt }.take(2)
                    val levelUpIds = levelUps.map { it.manga.id }
                    val levelContext = androidx.compose.ui.platform.LocalContext.current
                    var hiddenLevelUps by remember { mutableStateOf(emptySet<Long>()) }
                    LaunchedEffect(levelUpIds) {
                        if (levelUpIds.isEmpty()) return@LaunchedEffect
                        // Seen once is enough: it won't come back next time the home screen opens
                        runCatching { levelContext.appGraph.readingProgressEngine.acknowledgeLevelUps(levelUpIds) }
                        kotlinx.coroutines.delay(LEVEL_UP_SHOWN_MS)
                        hiddenLevelUps = hiddenLevelUps + levelUpIds
                    }
                    levelUps.forEach { series ->
                        androidx.compose.animation.AnimatedVisibility(
                            visible = series.manga.id !in hiddenLevelUps,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                        ) {
                            eu.kanade.tachiyomi.ui.home.stats.LevelUpBanner(
                                series,
                                onClick = {
                                    details = series
                                    hiddenLevelUps = hiddenLevelUps + series.manga.id
                                },
                            )
                        }
                    }
                    eu.kanade.tachiyomi.ui.home.stats.HunterCard(game.hunter, game.totalChapters)
                    eu.kanade.tachiyomi.ui.home.stats.StreakCard(game.streak)
                    game.series.take(DASHBOARD_LEVELS).forEach { series ->
                        eu.kanade.tachiyomi.ui.home.stats.SeriesLevelCard(
                            series = series,
                            onOpen = { details = series },
                            onContinue = { onContinue(series.manga.id) },
                        )
                    }
                    eu.kanade.presentation.components.ManhwaKit.GhostButton(
                        text = stringResource(ATMR.strings.game_view_all),
                        icon = MaterialSymbols.Rounded.MilitaryTech,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onViewAll,
                    )
                }
            }
        }
    }

    @Composable
    private fun WeekChart(week: List<Int>) {
        val colors = MaterialTheme.colorScheme
        val max = (week.maxOrNull() ?: 0).coerceAtLeast(1)
        val today = java.time.LocalDate.now()
        val locale = java.util.Locale.getDefault()
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            week.forEachIndexed { index, value ->
                val isToday = index == week.lastIndex
                val target = 0.06f + 0.94f * value / max
                val height by animateFloatAsState(target, animationSpec = tween(800), label = "bar")
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .fillMaxHeight(height)
                                .then(
                                    if (value > 0) Modifier.neonGlow(RoundedCornerShape(8.dp), colors.primary, if (isToday) 0.35f else 0.18f, 8.dp) else Modifier,
                                )
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (value > 0) {
                                        Brush.verticalGradient(listOf(if (isToday) Color.White else colors.primary, lerp(colors.primary, Color.Black, 0.55f)))
                                    } else {
                                        Brush.verticalGradient(listOf(ManhwaInk.text.copy(alpha = 0.1f), ManhwaInk.text.copy(alpha = 0.04f)))
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        today.minusDays((week.lastIndex - index).toLong()).dayOfWeek
                            .getDisplayName(java.time.format.TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Black else FontWeight.Normal,
                        color = if (isToday) colors.primary else ManhwaInk.text.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }

    @Composable
    private fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = modifier
                .glass(RoundedCornerShape(22.dp), alpha = 0.45f)
                .padding(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .neonGlow(RoundedCornerShape(11.dp), colors.primary, 0.25f, 8.dp)
                    .background(colors.primary.copy(alpha = 0.18f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = ManhwaInk.text)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = ManhwaInk.text.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun QuickToggles() {
        val context = LocalContext.current
        val graph = remember { context.appGraph }
        val autoTranslate = remember { graph.translationPreferences.autoTranslateAfterDownload() }
        val showTranslations = remember { graph.readerPreferences.showTranslations }
        val incognito = remember { graph.basePreferences.incognitoMode }
        val downloadedOnly = remember { graph.basePreferences.downloadedOnly }
        val autoOn by autoTranslate.collectPrefAsState()
        val showOn by showTranslations.collectPrefAsState()
        val incognitoOn by incognito.collectPrefAsState()
        val downloadedOn by downloadedOnly.collectPrefAsState()
        Section(
            title = stringResource(ATMR.strings.home_toggles),
            subtitle = stringResource(ATMR.strings.home_toggles_sub),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ToggleOrb(MaterialSymbols.Rounded.Translate, stringResource(ATMR.strings.home_t_autotranslate), autoOn) {
                    autoTranslate.set(!autoOn)
                }
                ToggleOrb(MaterialSymbols.RoundedFilled.Visibility, stringResource(ATMR.strings.home_t_show), showOn) {
                    showTranslations.set(!showOn)
                }
                ToggleOrb(MaterialSymbols.RoundedFilled.VisibilityOff, stringResource(ATMR.strings.home_t_incognito), incognitoOn) {
                    incognito.set(!incognitoOn)
                }
                ToggleOrb(MaterialSymbols.Rounded.Download, stringResource(ATMR.strings.home_t_downloaded), downloadedOn) {
                    downloadedOnly.set(!downloadedOn)
                }
            }
        }
    }

    @Composable
    private fun ToggleOrb(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val glow by animateFloatAsState(if (on) 0.45f else 0f, animationSpec = tween(400), label = "orb")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .neonGlow(CircleShape, colors.primary, glow, 18.dp)
                    .then(
                        if (on) {
                            Modifier
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(lerp(colors.primary, Color.White, 0.2f), colors.primary, lerp(colors.primary, Color.Black, 0.5f)),
                                    ),
                                )
                                .border(1.5.dp, ManhwaInk.text.copy(alpha = 0.45f), CircleShape)
                        } else {
                            Modifier.glass(CircleShape, alpha = 0.4f)
                        },
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = if (on) ManhwaInk.text else ManhwaInk.text.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) colors.primary else ManhwaInk.text.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }

    @Composable
    private fun WideCard(cover: Any, title: String, chip: String, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Column(modifier = Modifier.width(210.dp).clickable(onClick = onClick)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ManhwaInk.text.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
            ) {
                Cover(cover, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.4f to Color.Transparent, 1f to Ink.copy(alpha = 0.85f)),
                    ),
                )
                Text(
                    chip,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Ink.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Brush.horizontalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.2f)))),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ManhwaInk.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )
        }
    }

    /** "Fresh off the press": new chapters from every home source, with a tab for each source and the library. */
    @Composable
    private fun FreshSection(
        state: DiscoverViewModel.State,
        onSeeAll: () -> Unit,
        onOpenFresh: (eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersEngine.Item) -> Unit,
        onOpenUpdate: (tachiyomi.domain.updates.model.UpdatesWithRelations) -> Unit,
        onOpenManga: (Long) -> Unit,
    ) {
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (state.fresh.isEmpty()) "lib" else "all") }
        // MANHWA: tapping a card asks: read the chapter, or open the work's page (the play button reads at once)
        var choice by remember { mutableStateOf<Triple<String, String, Pair<() -> Unit, () -> Unit>>?>(null) }
        choice?.let { (title, chapter, actions) ->
            eu.kanade.tachiyomi.ui.home.fresh.OpenChoiceDialog(title, chapter, onRead = actions.first, onManga = actions.second, onDismiss = { choice = null })
        }
        val names = state.availableSources.associate { it.id to it.name }
        val sourceIds = state.fresh.map { it.sourceId }.distinct()
        Section(
            title = stringResource(ATMR.strings.home_new_chapters),
            subtitle = stringResource(ATMR.strings.home_fresh),
            onSeeAll = onSeeAll,
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                item { Chip(stringResource(ATMR.strings.fresh_all), tab == "all") { tab = "all" } }
                item { Chip(stringResource(ATMR.strings.fresh_library, state.newInLibrary.size), tab == "lib") { tab = "lib" } }
                items(sourceIds) { id -> Chip(names[id] ?: "$id", tab == "$id") { tab = "$id" } }
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == "lib") {
                    if (state.newInLibrary.isEmpty()) {
                        Text(stringResource(ATMR.strings.fresh_empty), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                    }
                    state.newInLibrary.take(6).forEach { update ->
                        ChapterRow(
                            cover = update.coverData,
                            title = update.mangaTitle,
                            chapter = update.chapterName,
                            whenText = relative(update.dateFetch.toEpochMilliseconds()),
                            onClick = { choice = Triple(update.mangaTitle, update.chapterName, Pair({ onOpenUpdate(update) }, { onOpenManga(update.mangaId) })) },
                            onPlay = { onOpenUpdate(update) },
                        )
                    }
                } else {
                    val list = state.fresh.filter { tab == "all" || "${it.sourceId}" == tab }
                    if (list.isEmpty()) {
                        Text(
                            stringResource(if (state.freshRunning) ATMR.strings.fresh_checking else ATMR.strings.fresh_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    list.take(8).forEach { item ->
                        ChapterRow(
                            cover = tachiyomi.domain.manga.model.MangaCover(item.mangaId, item.sourceId, false, item.thumbnail, null),
                            title = item.title,
                            chapter = item.chapterName,
                            whenText = relative(item.sortTime) + " • " + (names[item.sourceId] ?: ""),
                            onClick = { choice = Triple(item.title, item.chapterName, Pair({ onOpenFresh(item) }, { onOpenManga(item.mangaId) })) },
                            onPlay = { onOpenFresh(item) },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ChapterRow(cover: Any, title: String, chapter: String, whenText: String, onClick: () -> Unit, onPlay: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .glass(RoundedCornerShape(20.dp), alpha = 0.45f)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(78.dp).fillMaxHeight()) {
                Cover(cover, Modifier.fillMaxSize())
                Text(
                    stringResource(ATMR.strings.home_new_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = ManhwaInk.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(colors.primary)
                        .padding(vertical = 2.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ManhwaInk.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    chapter,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(whenText, style = MaterialTheme.typography.labelSmall, color = ManhwaInk.text.copy(alpha = 0.5f))
            }
            Box(
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(40.dp)
                    .background(
                        Brush.linearGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.5f))),
                        CircleShape,
                    )
                    .clip(CircleShape)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) { Icon(MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null, tint = ManhwaInk.text) }
        }
    }

    @Composable
    private fun RankCard(rank: Int, manga: Manga, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Box(modifier = Modifier.width(178.dp).height(212.dp).clickable(onClick = onClick)) {
            Text(
                "$rank",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 150.sp,
                    drawStyle = Stroke(width = 7f),
                    shadow = Shadow(colors.primary.copy(alpha = 0.8f), blurRadius = 30f),
                ),
                fontWeight = FontWeight.Black,
                color = if (rank <= 3) colors.primary else ManhwaInk.text.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.BottomStart).offset(y = 30.dp),
                maxLines = 1,
            )
            Cover(
                manga.asMangaCover(),
                Modifier
                    .align(Alignment.BottomEnd)
                    .width(122.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, ManhwaInk.text.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
            )
        }
    }

    @Composable
    private fun SourcesFeed(
        state: DiscoverViewModel.State,
        onFilter: (Long?) -> Unit,
        onOpen: (Manga) -> Unit,
    ) {
        var popularTab by rememberSaveable { mutableStateOf(false) }
        var shown by rememberSaveable { mutableIntStateOf(6) }
        val list = if (popularTab) state.trending else state.latest
        Section(
            title = stringResource(ATMR.strings.home_from_sources),
            subtitle = stringResource(ATMR.strings.home_from_sources_sub),
        ) {
            SegmentedTabs(
                left = stringResource(ATMR.strings.home_latest_tab),
                right = stringResource(ATMR.strings.home_popular_tab),
                rightSelected = popularTab,
                onSelect = {
                    popularTab = it
                    shown = 6
                },
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip(stringResource(ATMR.strings.home_all), state.sourceFilter == null) { onFilter(null) } }
                items(state.shownFeeds, key = { it.source.id }) { feed ->
                    Chip(feed.source.name, state.sourceFilter == feed.source.id) { onFilter(feed.source.id) }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (list.isEmpty()) {
                if (state.feeds.any { it.loading }) Loading()
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    list.take(shown).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { pair ->
                                GridCard(pair.first, pair.second.name, Modifier.weight(1f)) { onOpen(pair.first) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (list.size > shown) {
                        Text(
                            stringResource(ATMR.strings.home_show_more),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ManhwaInk.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(16.dp), accent = true, alpha = 0.4f)
                                .clickable { shown += 8 }
                                .padding(vertical = 13.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SegmentedTabs(left: String, right: String, rightSelected: Boolean, onSelect: (Boolean) -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
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
                    color = if (selected) ManhwaInk.text else ManhwaInk.text.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (selected) {
                                Modifier.background(
                                    Brush.horizontalGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.45f))),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(isRight) }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }

    @Composable
    private fun GridCard(manga: Manga, sourceName: String, modifier: Modifier, onClick: () -> Unit) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            modifier = modifier
                .aspectRatio(0.66f)
                .clip(shape)
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(ManhwaInk.text.copy(alpha = 0.2f), MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    ),
                    shape,
                )
                .clickable(onClick = onClick),
        ) {
            Cover(manga.asMangaCover(), Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to Ink.copy(alpha = 0.95f)),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SourceBadge(sourceName)
                StatusPill(manga.status)
            }
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ManhwaInk.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val genres = manga.genre.orEmpty().filter { it.isNotBlank() }.take(2)
                if (genres.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        genres.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = ManhwaInk.text.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .border(1.dp, ManhwaInk.text.copy(alpha = 0.25f), RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GenreExplorer(all: List<Pair<Manga, Source>>, onOpen: (Manga) -> Unit) {
        val genres = remember(all) {
            val names = all.flatMap { it.first.genre.orEmpty() }.map { it.trim() }.filter { it.isNotEmpty() }
            names.groupBy { it.lowercase() }
                .values
                .sortedByDescending { it.size }
                .take(14)
                .map { it.first() }
        }
        if (genres.size < 3) return
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        val current = selected?.takeIf { it in genres } ?: genres.first()
        val matches = remember(all, current) {
            all.filter { pair -> pair.first.genre.orEmpty().any { it.trim().equals(current, ignoreCase = true) } }
                .distinctBy { it.first.id }
        }
        Section(
            title = stringResource(ATMR.strings.home_explore),
            subtitle = stringResource(ATMR.strings.home_explore_sub),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(genres, key = { it }) { genre ->
                    Chip("# $genre", genre == current) { selected = genre }
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(matches, key = { it.first.id }) { pair ->
                    PosterCard(pair.first, pair.second.name, width = 132, onClick = { onOpen(pair.first) })
                }
            }
        }
    }

    @Composable
    private fun AiPanel(onAsk: (String?) -> Unit) {
        val colors = MaterialTheme.colorScheme
        val transition = rememberInfiniteTransition(label = "ai")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(5_000, easing = LinearEasing)),
            label = "angle",
        )
        val glow by transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
            label = "glow",
        )
        val shape = RoundedCornerShape(28.dp)
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(shape)
                .clickable { onAsk(null) },
        ) {
            // A rotating light under the panel shows as an animated rim.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        rotationZ = angle
                        scaleX = 2.2f
                        scaleY = 2.2f
                    }
                    .background(
                        Brush.sweepGradient(
                            listOf(colors.primary, Color.Transparent, Color(0xFF9B5CFF), Color.Transparent, colors.primary),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .padding(1.5.dp)
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF0C0A10), lerp(colors.primary, Ink, 0.82f))),
                        RoundedCornerShape(27.dp),
                    )
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .graphicsLayer {
                                scaleX = 0.92f + glow * 0.08f
                                scaleY = 0.92f + glow * 0.08f
                            }
                            .drawBehind {
                                drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = glow * 0.6f), Color.Transparent)))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Brush.linearGradient(listOf(colors.primary, Color(0xFF9B5CFF))), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = null, tint = Color.White) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(ATMR.strings.home_ai_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                        Text(
                            stringResource(ATMR.strings.home_ai_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                listOf(ATMR.strings.home_ai_p1, ATMR.strings.home_ai_p2, ATMR.strings.home_ai_p3).forEach { res ->
                    val prompt = stringResource(res)
                    Row(
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(14.dp), alpha = 0.3f)
                            .clickable { onAsk(prompt) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            MaterialSymbols.RoundedFilled.Star,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ManhwaInk.text,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            MaterialSymbols.AutoMirroredRounded.ArrowForward,
                            contentDescription = null,
                            tint = ManhwaInk.text.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    private val TileHues = listOf(
        Color(0xFFFF3B4E),
        Color(0xFF9B5CFF),
        Color(0xFF00B8D4),
        Color(0xFFFF7A1A),
        Color(0xFF3DDC97),
        Color(0xFFFF4FA3),
    )

    @Composable
    private fun ListTile(name: String, hue: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .width(150.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(hue.copy(alpha = 0.85f), lerp(hue, Ink, 0.75f))))
                .border(1.dp, ManhwaInk.text.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Icon(
                MaterialSymbols.Rounded.LocalLibrary,
                contentDescription = null,
                tint = ManhwaInk.text.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
            )
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = ManhwaInk.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }

    @Composable
    private fun SetupCard(onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(28.dp), accent = true)
                .clickable(onClick = onClick)
                .padding(22.dp),
        ) {
            Icon(
                MaterialSymbols.Rounded.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(ATMR.strings.home_setup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = ManhwaInk.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(ATMR.strings.home_setup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = ManhwaInk.text.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(16.dp))
            AccentButton(stringResource(ATMR.strings.home_my_sources), MaterialSymbols.Rounded.Explore, Modifier, onClick)
        }
    }

    // endregion

    // region Small pieces

    @Composable
    private fun Cover(data: Any?, modifier: Modifier) {
        AsyncImage(
            model = data,
            placeholder = ColorPainter(Color(0x1F888888)),
            error = ColorPainter(Color(0xFF1A1418)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }

    @Composable
    private fun PosterCard(manga: Manga, badge: String?, width: Int, onClick: () -> Unit) {
        Column(modifier = Modifier.width(width.dp).clickable(onClick = onClick)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, ManhwaInk.text.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
            ) {
                Cover(manga.asMangaCover(), Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.6f to Color.Transparent, 1f to Ink.copy(alpha = 0.8f)),
                    ),
                )
                if (badge != null) {
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { SourceBadge(badge) }
                }
            }
            Text(
                manga.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ManhwaInk.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 7.dp, start = 2.dp),
            )
        }
    }

    @Composable
    private fun StatusPill(status: Long) {
        val ongoing = status.toInt() == SManga.ONGOING
        if (!ongoing && !status.isCompleted()) return
        val label = stringResource(if (ongoing) ATMR.strings.home_ongoing else ATMR.strings.home_completed_badge)
        val dot = if (ongoing) Color(0xFF3DDC97) else Color(0xFF4FC3F7)
        Row(
            modifier = Modifier
                .background(Ink.copy(alpha = 0.7f), RoundedCornerShape(50))
                .border(1.dp, dot.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(dot, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = dot)
        }
    }

    @Composable
    private fun SourceBadge(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ManhwaInk.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }

    @Composable
    private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(50)
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = ManhwaInk.text.copy(alpha = if (selected) 1f else 0.75f),
            modifier = Modifier
                .then(
                    if (selected) {
                        Modifier
                            .clip(shape)
                            .background(
                                Brush.horizontalGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.45f))),
                            )
                    } else {
                        Modifier.glass(shape, alpha = 0.35f)
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }

    @Composable
    private fun AccentButton(text: String, icon: ImageVector?, modifier: Modifier, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.4f))))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun SolidWhiteButton(text: String, modifier: Modifier, onClick: () -> Unit) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(ManhwaInk.text)
                .clickable(onClick = onClick)
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MaterialSymbols.RoundedFilled.PlayArrow, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Ink)
        }
    }

    @Composable
    private fun GhostButton(text: String, icon: ImageVector?, modifier: Modifier, onClick: () -> Unit) {
        Row(
            modifier = modifier
                .glass(RoundedCornerShape(14.dp), alpha = 0.35f)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = if (icon == null) 11.dp else 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ManhwaInk.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ManhwaInk.text,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun Loading() {
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }

    // endregion

    private fun relative(millis: Long): String =
        DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    private fun formatChapter(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}
