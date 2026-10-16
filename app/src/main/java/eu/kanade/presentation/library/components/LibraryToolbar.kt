package eu.kanade.presentation.library.components

import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState as collectAsStateFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import eu.kanade.presentation.components.ManhwaKit
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.roundedfilled.ViewModule
import mihon.icons.materialsymbols.automirroredrounded.FormatListBulleted
import tachiyomi.i18n.at.ATMR
import mihon.icons.materialsymbols.rounded.AutoAwesome
import eu.kanade.translation.search.AiChatScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun LibraryToolbar(
    hasActiveFilters: Boolean,
    selectedCount: Int,
    title: LibraryToolbarTitle,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) = when {
    selectedCount > 0 -> LibrarySelectionToolbar(
        selectedCount = selectedCount,
        onClickUnselectAll = onClickUnselectAll,
        onClickSelectAll = onClickSelectAll,
        onClickInvertSelection = onClickInvertSelection,
    )
    else -> LibraryRegularToolbar(
        title = title,
        hasFilters = hasActiveFilters,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onClickFilter = onClickFilter,
        onClickRefresh = onClickRefresh,
        onClickGlobalUpdate = onClickGlobalUpdate,
        onClickOpenRandomManga = onClickOpenRandomManga,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun LibraryRegularToolbar(
    title: LibraryToolbarTitle,
    hasFilters: Boolean,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: () -> Unit,
    onClickOpenRandomManga: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    SearchToolbar(
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, false),
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.numberOfManga != null) {
                    Pill(
                        text = "${title.numberOfManga}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                        fontSize = 14.sp,
                    )
                }
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        // MANHWA: the glass orbs of the home screen, with the organizer's own button
        searchEnabled = searchQuery != null,
        actions = {
            val navigator = LocalNavigator.current
            val context = androidx.compose.ui.platform.LocalContext.current
            val displayModePref = remember { context.appGraph.libraryPreferences.displayMode }
            val displayMode by displayModePref.collectAsState()
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 6.dp),
            ) {
                if (searchQuery == null) {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Search, size = ORB) { onSearchQueryChange("") }
                }
                OrganizeOrb()
                ManhwaKit.IconOrb(MaterialSymbols.Rounded.AutoAwesome, size = ORB) {
                    navigator?.push(eu.kanade.tachiyomi.ui.library.ai.AiLibraryOrganizerScreen())
                }
                // Quick switch between the card grid and the detailed list
                ManhwaKit.IconOrb(
                    if (displayMode == tachiyomi.domain.library.model.LibraryDisplayMode.List) {
                        MaterialSymbols.RoundedFilled.ViewModule
                    } else {
                        MaterialSymbols.AutoMirroredRounded.FormatListBulleted
                    },
                    size = ORB,
                ) {
                    displayModePref.set(
                        if (displayMode == tachiyomi.domain.library.model.LibraryDisplayMode.List) {
                            tachiyomi.domain.library.model.LibraryDisplayMode.ComfortableGrid
                        } else {
                            tachiyomi.domain.library.model.LibraryDisplayMode.List
                        },
                    )
                }
                // Sort and filter live in the same sheet; the orb glows while a filter is on
                ManhwaKit.IconOrb(MaterialSymbols.Rounded.FilterList, active = hasFilters, size = ORB, onClick = onClickFilter)
                var menu by remember { mutableStateOf(false) }
                Box {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.MoreVert, size = ORB) { menu = true }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(
                            MR.strings.action_update_library to onClickGlobalUpdate,
                            MR.strings.action_update_category to onClickRefresh,
                            MR.strings.action_open_random_manga to onClickOpenRandomManga,
                        ).forEach { (label, action) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    menu = false
                                    action()
                                },
                            )
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

private const val ORB = 36

/**
 * MANHWA "organize the library": spins while the organizer goes through the whole library, turns into a check
 * when it is done, and says what changed (works moved, categories made, categories removed).
 */
@Composable
private fun OrganizeOrb() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val organizer = remember { context.appGraph.libraryOrganizer }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val running by organizer.running.collectAsStateFlow()
    var done by remember { mutableStateOf(false) }
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "organize")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    val pop by androidx.compose.animation.core.animateFloatAsState(
        if (done) 1f else 0.6f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "pop",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                rotationZ = if (running) angle else 0f
                val scale = if (done) pop else 1f
                scaleX = scale
                scaleY = scale
            },
    ) {
        ManhwaKit.IconOrb(
            if (done) MaterialSymbols.Rounded.Check else MaterialSymbols.Rounded.Autorenew,
            active = running || done,
            size = ORB,
        ) {
            if (running) return@IconOrb
            scope.launch {
                val result = organizer.reorganize() ?: return@launch
                done = true
                android.widget.Toast.makeText(
                    context,
                    if (result.changed + result.created + result.removed == 0) {
                        context.stringResource(ATMR.strings.lib_organize_nothing)
                    } else {
                        context.stringResource(ATMR.strings.lib_organize_done, result.changed, result.created, result.removed)
                    },
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                kotlinx.coroutines.delay(2_500)
                done = false
            }
        }
    }
}

@Composable
private fun LibrarySelectionToolbar(
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            AppBarActions(
                listOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = MaterialSymbols.Rounded.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = MaterialSymbols.Rounded.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = onClickUnselectAll,
    )
}

@Immutable
data class LibraryToolbarTitle(
    val text: String,
    val numberOfManga: Int? = null,
)
