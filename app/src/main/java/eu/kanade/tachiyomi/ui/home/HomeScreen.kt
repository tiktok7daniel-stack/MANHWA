package eu.kanade.tachiyomi.ui.home

import eu.kanade.presentation.components.ManhwaInk

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxHeight

import eu.kanade.presentation.components.neonGlow
import androidx.compose.foundation.layout.offset

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer

import eu.kanade.tachiyomi.ui.home.discover.DiscoverTab
import eu.kanade.translation.search.AiChatScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.components.glass
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 200

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    /** The dock's slots: four tabs and the AI orb, all the same width. */
    private const val DOCK_SLOTS = 5

    private val TABS = listOf(
        DiscoverTab,
        LibraryTab,
        // Updates live on the home screen now ("New chapters for you" and its full list)
        BrowseTab,
        MoreTab,
    )

    @Composable
    override fun Content() {
        // MANHWA: this screen follows the chosen style (and its light version)
        // MANHWA: the second system is a whole app of its own (its home, tabs and bar)
        val system = eu.kanade.tachiyomi.ui.nova.rememberAppSystem()
        if (system != eu.kanade.tachiyomi.ui.nova.AppSystem.CLASSIC) {
            // Nova and Inkverse answer the same requests as the original home: open a tab, search the library, hide the bar
            val navigator = LocalNavigator.currentOrThrow
            var showBar by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { showBottomNavEvent.receiveAsFlow().collectLatest { showBar = it } }
            if (system == eu.kanade.tachiyomi.ui.nova.AppSystem.INK) {
                eu.kanade.tachiyomi.ui.ink.InkShell(showBar = showBar) { tabNavigator ->
                    TabEvents(tabNavigator, navigator, home = eu.kanade.tachiyomi.ui.ink.InkHomeTab)
                }
            } else {
                eu.kanade.tachiyomi.ui.nova.NovaShell(showBar = showBar) { tabNavigator ->
                    TabEvents(tabNavigator, navigator, home = eu.kanade.tachiyomi.ui.nova.NovaHomeTab)
                }
            }
            return
        }
        eu.kanade.presentation.components.ManhwaStyledScreen { HomeContent() }
    }

    @Composable
    private fun HomeContent() {
        val navigator = LocalNavigator.currentOrThrow
        TabNavigator(
            tab = DiscoverTab,
            key = TabNavigatorKey,
        ) { tabNavigator ->
            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                val tabletUi = isTabletUi()
                val navigationSuiteType = if (tabletUi) {
                    NavigationSuiteType.NavigationRail
                } else {
                    NavigationSuiteType.NavigationBar
                }
                val navigationSuiteState = rememberNavigationSuiteScaffoldState()
                var showBar by remember { mutableStateOf(true) }
                LaunchedEffect(navigationSuiteState, tabletUi) {
                    if (tabletUi) navigationSuiteState.show()
                    showBottomNavEvent.receiveAsFlow().collectLatest { show ->
                        showBar = show
                        if (tabletUi || show) {
                            navigationSuiteState.show()
                        } else {
                            navigationSuiteState.hide()
                        }
                    }
                }

                val tabContent: @Composable () -> Unit = {
                    AnimatedContent(
                        targetState = tabNavigator.current,
                        transitionSpec = {
                            materialFadeThroughIn(
                                initialScale = 1f,
                                durationMillis = TabFadeDuration,
                            ) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)
                        },
                        label = "tabContent",
                    ) {
                        tabNavigator.saveableState(key = "currentTab", it) {
                            it.Content()
                        }
                    }
                }
                if (tabletUi) {
                    NavigationSuiteScaffold(
                        navigationSuiteType = navigationSuiteType,
                        state = navigationSuiteState,
                        navigationSuiteColors = NavigationSuiteDefaults.colors(
                            navigationRailContainerColor = MaterialTheme.colorScheme
                                .surfaceColorAtElevation(3.dp),
                        ),
                        navigationItemVerticalArrangement = Arrangement.Center,
                        navigationItems = {
                            TABS.fastForEach { NavigationSuiteItem(it, navigationSuiteType) }
                        },
                        content = tabContent,
                    )
                } else if (eu.kanade.presentation.components.LocalManhwaLook.current.style == eu.kanade.presentation.components.ManhwaStyle.DOCK) {
                    // MANHWA Floating Glass: the navigation is a glass capsule floating at the side of the page
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(Modifier.fillMaxSize()) { tabContent() }
                        AnimatedVisibility(
                            visible = showBar,
                            enter = fadeIn() + androidx.compose.animation.slideInHorizontally { it },
                            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally { it },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) { SideCapsule() }
                    }
                } else {
                    // MANHWA: floating glass navigation bar
                    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                        ) { tabContent() }
                        AnimatedVisibility(
                            visible = showBar,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) { GlassNavigationBar() }
                    }
                }
            }

            BackHandler(enabled = tabNavigator.current != DiscoverTab, onBack = { tabNavigator.current = DiscoverTab })

            TabEvents(tabNavigator, navigator, home = DiscoverTab)
        }
    }

    /** The requests from outside a home (a notification, a shortcut, the library's search) acted on in [tabNavigator]. */
    @Composable
    private fun TabEvents(
        tabNavigator: TabNavigator,
        navigator: cafe.adriel.voyager.navigator.Navigator,
        home: cafe.adriel.voyager.navigator.tab.Tab,
    ) {
        val goToLibraryTab = { tabNavigator.current = LibraryTab }
        LaunchedEffect(Unit) {
            launch {
                librarySearchEvent.receiveAsFlow().collectLatest {
                    goToLibraryTab()
                    LibraryTab.search(it)
                }
            }
            launch {
                openTabEvent.receiveAsFlow().collectLatest {
                    tabNavigator.current = when (it) {
                        is Tab.Library -> LibraryTab
                        Tab.Updates -> home
                        Tab.History -> HistoryTab
                        is Tab.Browse -> {
                            if (it.toExtensions) {
                                BrowseTab.showExtension()
                            }
                            BrowseTab
                        }
                        is Tab.More -> MoreTab
                    }

                    if (it is Tab.Library && it.mangaIdToOpen != null) {
                        navigator.push(MangaScreen(it.mangaIdToOpen))
                    }
                    if (it == Tab.Updates) {
                        navigator.push(eu.kanade.tachiyomi.ui.home.fresh.FreshChaptersScreen())
                    }
                    if (it is Tab.More && it.toDownloads) {
                        navigator.push(DownloadQueueScreen)
                    }
                }
            }
        }
    }

    @Composable
    private fun NavigationSuiteItem(
        tab: eu.kanade.presentation.util.Tab,
        navigationSuiteType: NavigationSuiteType,
    ) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        NavigationSuiteItem(
            navigationSuiteType = navigationSuiteType,
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = {
                Icon(
                    painter = tab.options.icon!!,
                    contentDescription = tab.options.title,
                )
            },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            badge = tabBadge(tab),
        )
    }

    @Composable
    private fun GlassNavigationBar() {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val colors = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val look = eu.kanade.presentation.components.LocalManhwaLook.current
            // The glass is drawn behind the row, so nothing in the row (the AI orb's glow) gets clipped.
            // Obsidian Royal: a floating pill dock, solid, its top edge lit by a hairline
            Box(
                Modifier.matchParentSize().then(
                    if (look.isSolid) {
                        // Each solid look draws the pill dock in its own panel
                        Modifier.glass(RoundedCornerShape(50), accent = false)
                    } else {
                        Modifier.glass(RoundedCornerShape(28.dp), accent = true, alpha = 0.85f)
                    },
                ),
            )
            // The solid looks mark the open tab with one pill that slides to the next tab with a spring
            // (interruptible: tapping again mid-way turns it round from where it is)
            if (look.isSolid) DockIndicator(look)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TABS.forEachIndexed { index, tab ->
                    if (index == 2) AiCenterButton(onClick = { navigator.push(AiChatScreen()) })
                    val selected = tabNavigator.current::class == tab::class
                    val badge = tabBadge(tab)
                    val tint by animateColorAsState(
                        if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.8f),
                        label = "tab",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable {
                                if (!selected) tabNavigator.current = tab else scope.launch { tab.onReselect(navigator) }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Glowing line over the selected tab (Obsidian Royal marks it with a pill around the icon)
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .width(if (selected && !look.isSolid) 22.dp else 0.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Brush.horizontalGradient(listOf(colors.primary.copy(alpha = 0.4f), colors.primary, colors.primary.copy(alpha = 0.4f)))),
                        )
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BadgedBox(badge = { badge?.invoke() }) {
                                    Icon(
                                        painter = tab.options.icon!!,
                                        contentDescription = tab.options.title,
                                        tint = tint,
                                        modifier = Modifier
                                            .neonGlow(CircleShape, colors.primary, if (selected) 0.22f else 0f, 10.dp)
                                            .then(
                                                if (look.isSolid) {
                                                    // The sliding pill behind marks the tab; the icon keeps its place
                                                    Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                }
                                Text(
                                    text = tab.options.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * MANHWA Floating Glass: the tabs and the AI orb in a vertical glass capsule at the side, with a glowing round
     * light that slides (with a spring) behind the open tab.
     */
    @Composable
    private fun SideCapsule() {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val colors = MaterialTheme.colorScheme
        val itemHeight = 56.dp
        val index = TABS.indexOfFirst { it::class == tabNavigator.current::class }
        val slot = if (index < 2) index else index + 1
        val still = eu.kanade.presentation.components.LocalReduceMotion.current
        val y by androidx.compose.animation.core.animateDpAsState(
            targetValue = itemHeight * slot.coerceAtLeast(0),
            animationSpec = if (still) {
                androidx.compose.animation.core.snap()
            } else {
                androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f)
            },
            label = "capsule",
        )
        Box(
            Modifier
                .navigationBarsPadding()
                .padding(end = 10.dp)
                .width(64.dp),
        ) {
            Box(Modifier.matchParentSize().glass(RoundedCornerShape(50), accent = false))
            if (index >= 0) {
                Box(
                    Modifier
                        .padding(vertical = 10.dp)
                        .offset(y = y)
                        .align(Alignment.TopCenter)
                        .size(48.dp)
                        .neonGlow(CircleShape, colors.primary, 0.3f, 10.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.55f), colors.primary.copy(alpha = 0.15f))), CircleShape)
                        .border(1.dp, colors.primary.copy(alpha = 0.8f), CircleShape),
                )
            }
            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TABS.forEachIndexed { i, tab ->
                    if (i == 2) {
                        Box(Modifier.size(64.dp, itemHeight), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .neonGlow(CircleShape, colors.primary, 0.3f, 8.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(colors.primary, Color(0xFF9B5CFF))), CircleShape)
                                    .clickable { navigator.push(AiChatScreen()) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = "MANHWA AI", tint = ManhwaInk.text, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    val selected = tabNavigator.current::class == tab::class
                    val badge = tabBadge(tab)
                    val tint by animateColorAsState(if (selected) Color.White else colors.onSurfaceVariant.copy(alpha = 0.85f), label = "side")
                    Box(
                        Modifier
                            .size(64.dp, itemHeight)
                            .clip(CircleShape)
                            .clickable {
                                if (!selected) tabNavigator.current = tab else scope.launch { tab.onReselect(navigator) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BadgedBox(badge = { badge?.invoke() }) {
                            Icon(painter = tab.options.icon!!, contentDescription = tab.options.title, tint = tint)
                        }
                    }
                }
            }
        }
    }

    /**
     * The pill under the open tab of the dock, sliding between tabs. The dock has five equal slots (two tabs, the AI
     * orb, two tabs); the pill sits on the open tab's slot. Manhwa Ink draws it as an inked sticker.
     */
    @Composable
    private fun BoxScope.DockIndicator(look: eu.kanade.presentation.components.ManhwaLook) {
        val tabNavigator = LocalTabNavigator.current
        val colors = MaterialTheme.colorScheme
        val index = TABS.indexOfFirst { it::class == tabNavigator.current::class }
        if (index < 0) return
        val slot = if (index < 2) index else index + 1
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize().padding(horizontal = 4.dp)) {
            val width = maxWidth / DOCK_SLOTS
            val still = eu.kanade.presentation.components.LocalReduceMotion.current
            val x by androidx.compose.animation.core.animateDpAsState(
                targetValue = width * slot,
                animationSpec = if (still) {
                    androidx.compose.animation.core.snap()
                } else {
                    androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 420f)
                },
                label = "dock",
            )
            val hunter = look.style == eu.kanade.presentation.components.ManhwaStyle.HUNTER
            // Hunter System: a cut segment lit from its top edge
            val shape = if (hunter) {
                androidx.compose.foundation.shape.CutCornerShape(6.dp)
            } else {
                RoundedCornerShape(if (look.style == eu.kanade.presentation.components.ManhwaStyle.INK) 8.dp else 50.dp)
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = x)
                    .padding(horizontal = 4.dp)
                    .width(width - 8.dp)
                    .height(50.dp)
                    .clip(shape)
                    .background(colors.primary.copy(alpha = if (look.style == eu.kanade.presentation.components.ManhwaStyle.INK) 0.22f else 0.16f), shape)
                    .then(
                        if (look.style == eu.kanade.presentation.components.ManhwaStyle.INK) {
                            Modifier.border(1.5.dp, look.text, shape)
                        } else if (hunter) {
                            Modifier
                                .border(1.dp, eu.kanade.presentation.components.ProTokens.HunterCyan.copy(alpha = 0.7f), shape)
                                .drawBehind {
                                    drawRect(
                                        eu.kanade.presentation.components.ProTokens.HunterCyan,
                                        size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()),
                                    )
                                }
                        } else {
                            Modifier.border(0.75.dp, colors.primary.copy(alpha = 0.35f), shape)
                        },
                    ),
            )
        }
    }

    /** MANHWA AI orb in the middle of the bar, with a slow pulsing halo. */
    @Composable
    private fun RowScope.AiCenterButton(onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val halo by eu.kanade.presentation.components.decorSwing(0.9f, 1.08f, 1_600)
        // Same width as every tab, so the orb sits exactly in the middle of the bar
        val calm = eu.kanade.presentation.components.LocalManhwaLook.current.isCalm
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            // The breathing halo belongs to MANHWA Glass; Obsidian Royal keeps the orb still
            if (!calm) Box(
                modifier = Modifier
                    .size(60.dp)
                    .graphicsLayer {
                        scaleX = halo
                        scaleY = halo
                    }
                    .background(
                        Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.55f), Color.Transparent)),
                        CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .neonGlow(CircleShape, colors.primary, 0.35f, 10.dp)
                    .border(1.5.dp, ManhwaInk.text.copy(alpha = 0.35f), CircleShape)
                    .background(
                        Brush.linearGradient(listOf(colors.primary, Color(0xFF9B5CFF))),
                        CircleShape,
                    )
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MaterialSymbols.Rounded.AutoAwesome, contentDescription = "MANHWA AI", tint = ManhwaInk.text)
            }
        }
    }

    @Composable
    private fun tabBadge(tab: eu.kanade.presentation.util.Tab): (@Composable () -> Unit)? {
        val context = LocalContext.current
        val count by produceState(initialValue = 0, tab) {
            val graph = context.appGraph
            when (tab) {
                is UpdatesTab, is DiscoverTab -> {
                    combine(
                        graph.libraryPreferences.newShowUpdatesCount.changes(),
                        graph.libraryPreferences.newUpdatesCount.changes(),
                    ) { show, count ->
                        if (show) count else 0
                    }
                        .collectLatest { value = it }
                }

                is BrowseTab -> {
                    graph.sourcePreferences.extensionUpdatesCount.changes()
                        .collectLatest { value = it }
                }

                else -> value = 0
            }
        }
        if (count <= 0) return null
        return {
            Badge {
                val desc = when (tab) {
                    is UpdatesTab, is DiscoverTab -> pluralStringResource(
                        MR.plurals.notification_chapters_generic,
                        count = count,
                        count,
                    )

                    is BrowseTab -> pluralStringResource(
                        MR.plurals.update_check_notification_ext_updates,
                        count = count,
                        count,
                    )

                    else -> null
                }
                Text(
                    text = count.toString(),
                    modifier = Modifier.semantics {
                        if (desc != null) contentDescription = desc
                    },
                )
            }
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface Tab {
        data class Library(val mangaIdToOpen: Long? = null) : Tab
        data object Updates : Tab
        data object History : Tab
        data class Browse(val toExtensions: Boolean = false) : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}
