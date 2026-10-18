package eu.kanade.presentation.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/** A screen with tabs (Browse and the like) in the MANHWA glass look: glowing title, glass search, pill tabs. */
@Composable
fun TabbedScreen(
    titleRes: StringResource,
    tabs: List<TabContent>,
    state: PagerState = rememberPagerState { tabs.size },
    searchQuery: String? = null,
    onChangeSearchQuery: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme

    GlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                val tab = tabs[state.currentPage]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(titleRes),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                shadow = Shadow(colors.primary.copy(alpha = 0.6f), blurRadius = 24f),
                            ),
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        AppBarActions(tab.actions)
                    }
                    if (tab.searchEnabled) {
                        Spacer(Modifier.height(10.dp))
                        ManhwaKit.SearchField(
                            value = searchQuery.orEmpty(),
                            hint = stringResource(MR.strings.action_search_hint),
                            onValueChange = { onChangeSearchQuery(it.ifEmpty { null }) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                            .glass(RoundedCornerShape(50), alpha = 0.4f)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tabs.forEachIndexed { index, item ->
                            TabPill(
                                text = stringResource(item.titleRes),
                                badge = item.badgeNumber,
                                selected = state.currentPage == index,
                                modifier = Modifier.weight(1f),
                                onClick = { scope.launch { state.animateScrollToPage(index) } },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            HorizontalPager(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentPadding.calculateTopPadding()),
                state = state,
                verticalAlignment = Alignment.Top,
            ) { page ->
                tabs[page].content(
                    PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    snackbarHostState,
                )
            }
        }
    }
}

@Composable
private fun TabPill(text: String, badge: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.neonGlow(shape, colors.primary, 0.25f, 8.dp).clip(shape).background(ManhwaKit.accentBrush())
                } else {
                    Modifier.clip(shape)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) ManhwaInk.text else ManhwaInk.text.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (badge != null && badge > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .neonGlow(CircleShape, colors.primary, 0.4f, 6.dp)
                    .clip(CircleShape)
                    .background(if (selected) ManhwaInk.text else colors.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$badge",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = if (selected) colors.primary else ManhwaInk.text,
                )
            }
        }
    }
}

data class TabContent(
    val titleRes: StringResource,
    val badgeNumber: Int? = null,
    val searchEnabled: Boolean = false,
    val actions: List<AppBar.AppBarAction> = listOf(),
    val content: @Composable (contentPadding: PaddingValues, snackbarHostState: SnackbarHostState) -> Unit,
)
