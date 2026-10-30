package eu.kanade.tachiyomi.novel.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.neonGlow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.MenuBook
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.absoluteValue

/**
 * MANHWA novel bridge: the manhwa and its novel as two covers over the chapter panel. The one in front is the one
 * shown below it; a swipe brings the other forward (the switch under them follows, and the other way round). The
 * one behind leans back, smaller and dimmer, peeking at the side.
 */
@Composable
fun DualCovers(manga: Manga, ui: NovelBridgeUi, tab: Int, onTab: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The link as kept, without loading the novel's chapters until its side is opened
    val stored by produceState<NovelBridge.Link?>(null, manga.id) { value = NovelBridge.link(context, manga.id) }
    val link = ui.link ?: stored
    val pager = rememberPagerState(initialPage = tab) { 2 }
    LaunchedEffect(tab) { if (pager.currentPage != tab) pager.animateScrollToPage(tab) }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage != tab) onTab(pager.settledPage) }
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 96.dp),
            pageSpacing = (-28).dp,
            modifier = Modifier.fillMaxWidth().height(250.dp),
        ) { page ->
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = lerp(1f, 0.8f, offset)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.55f, offset)
                        rotationY = (if (page < pager.currentPage) 1 else -1) * offset * 18f
                        cameraDistance = 12f * density
                    }
                    .clickable { onTab(page) },
                contentAlignment = Alignment.Center,
            ) {
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .padding(vertical = 8.dp)
                        .aspectRatio(2f / 3f)
                        .then(if (offset < 0.5f) Modifier.neonGlow(shape, accent, 0.35f, 16.dp) else Modifier)
                        .clip(shape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (page == 0) {
                        AsyncImage(model = manga, contentDescription = manga.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else if (link != null && link.cover.isNotBlank()) {
                        AsyncImage(model = link.cover, contentDescription = link.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        // No novel linked yet: its side looks for it when brought forward
                        Icon(MaterialSymbols.Rounded.MenuBook, contentDescription = null, tint = accent, modifier = Modifier.width(48.dp).aspectRatio(1f))
                    }
                    // Which side this is, on a shade at the bottom of the cover
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(if (page == 0) ATMR.strings.bridge_tab_manhwa else ATMR.strings.bridge_tab_novel),
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        // The novel's own title under the covers, when it goes by another name
        val novelTitle = link?.title?.takeIf { it.isNotBlank() && !it.equals(manga.title, ignoreCase = true) }
        if (novelTitle != null && tab == 1) {
            Text(
                novelTitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
    }
}

