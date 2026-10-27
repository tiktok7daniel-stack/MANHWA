package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.util.rememberAnimatedVectorPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object BrowseTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val enter = AnimatedImageVector.animatedVectorResource(R.drawable.animated_browse_enter)
            val exit = AnimatedImageVector.animatedVectorResource(R.drawable.animated_browse_exit)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(enter, exit, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = Channel<Unit>(1, BufferOverflow.DROP_OLDEST)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = cafe.adriel.voyager.navigator.LocalNavigator.currentOrThrow

        // Hoisted for extensions tab's search bar
        val extensionsViewModel = metroViewModel<ExtensionsViewModel>()
        val extensionsSearchQuery by extensionsViewModel.searchQuery.collectAsStateWithLifecycle()

        // MANHWA anime: Sources and Extensions each have a manhwa side and an anime side, switched on top
        // MANHWA: and novels, the third side
        var kind by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(eu.kanade.tachiyomi.novel.KIND_MANGA) }
        val sources = sourcesTab()
        val extensions = extensionsTab(extensionsViewModel)
        val tabs = listOf(
            sources.copy(
                content = { padding, snackbar ->
                    eu.kanade.tachiyomi.novel.MediaSwitch(kind, { kind = it }, padding) { inner ->
                        when (kind) {
                            eu.kanade.tachiyomi.novel.KIND_ANIME -> eu.kanade.tachiyomi.anime.AnimeSourcesList(inner)
                            eu.kanade.tachiyomi.novel.KIND_NOVEL -> eu.kanade.tachiyomi.novel.NovelSourcesList(inner)
                            else -> sources.content(inner, snackbar)
                        }
                    }
                },
            ),
            extensions.copy(
                // MANHWA: the ⋮ menu up top opens the repositories of the side that is shown
                actions = when (kind) {
                    eu.kanade.tachiyomi.novel.KIND_NOVEL -> listOf(
                        eu.kanade.presentation.components.AppBar.OverflowAction(
                            title = stringResource(tachiyomi.i18n.at.ATMR.strings.novel_repos),
                            onClick = { navigator.push(eu.kanade.tachiyomi.novel.NovelReposScreen()) },
                        ),
                    )
                    eu.kanade.tachiyomi.novel.KIND_ANIME -> listOf(
                        eu.kanade.presentation.components.AppBar.OverflowAction(
                            title = stringResource(tachiyomi.i18n.at.ATMR.strings.anime_repos),
                            onClick = { navigator.push(eu.kanade.tachiyomi.anime.AnimeReposScreen()) },
                        ),
                    )
                    else -> extensions.actions
                },
                content = { padding, snackbar ->
                    eu.kanade.tachiyomi.novel.MediaSwitch(kind, { kind = it }, padding) { inner ->
                        when (kind) {
                            eu.kanade.tachiyomi.novel.KIND_ANIME -> eu.kanade.tachiyomi.anime.AnimeExtensionsList(inner, extensionsSearchQuery)
                            eu.kanade.tachiyomi.novel.KIND_NOVEL -> eu.kanade.tachiyomi.novel.NovelExtensionsList(inner, extensionsSearchQuery)
                            else -> extensions.content(inner, snackbar)
                        }
                    }
                },
            ),
            migrateSourceTab(),
        )

        val state = rememberPagerState { tabs.size }

        TabbedScreen(
            titleRes = MR.strings.browse,
            tabs = tabs,
            state = state,
            searchQuery = extensionsSearchQuery,
            onChangeSearchQuery = extensionsViewModel::search,
        )
        LaunchedEffect(Unit) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest { state.scrollToPage(1) }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
