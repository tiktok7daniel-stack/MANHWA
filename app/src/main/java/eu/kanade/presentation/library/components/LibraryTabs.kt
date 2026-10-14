package eu.kanade.presentation.library.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import tachiyomi.domain.category.model.Category

/** MANHWA library categories: glass capsules that light up when selected, each with its series count. */
@Composable
internal fun LibraryTabs(
    categories: List<Category>,
    pagerState: PagerState,
    getItemCountForCategory: (Category) -> Int?,
    onTabItemClick: (Int) -> Unit,
) {
    val currentPageIndex = pagerState.currentPage.coerceAtMost(categories.lastIndex)
    val listState = rememberLazyListState()
    LaunchedEffect(currentPageIndex) {
        if (currentPageIndex >= 0) listState.animateScrollToItem(currentPageIndex.coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(categories, key = { _, it -> it.id }) { index, category ->
            val selected = index == currentPageIndex
            val lift by animateFloatAsState(if (selected) 1.06f else 1f, label = "lift")
            val textColor by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.7f), label = "text")
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                    }
                    .then(
                        if (selected) {
                            Modifier
                                .neonGlow(shape, MaterialTheme.colorScheme.primary, 0.28f, 10.dp)
                                .clip(shape)
                                .background(ManhwaKit.accentBrush())
                        } else {
                            Modifier.glass(shape, alpha = 0.38f)
                        },
                    )
                    .clickable { onTabItemClick(index) }
                    .padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    category.visualName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                )
                val count = getItemCountForCategory(category)
                if (count != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                } else {
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}
