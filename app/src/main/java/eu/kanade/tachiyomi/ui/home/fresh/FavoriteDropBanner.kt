package eu.kanade.tachiyomi.ui.home.fresh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.NotificationsActive
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The in-app drop banner: when a favorite gets a new chapter while the app is open, a glass banner slides down
 * from the top with the cover, the chapter and a "Read now" button. Drops arriving together wait their turn.
 */
@Composable
fun BoxScope.FavoriteDropBanner() {
    val context = LocalContext.current
    val drops = remember { context.appGraph.favoriteDrops }
    var current by remember { mutableStateOf<FavoriteDrops.Drop?>(null) }
    var visible by remember { mutableStateOf(false) }
    val queue = remember { ArrayDeque<FavoriteDrops.Drop>() }

    LaunchedEffect(drops) {
        drops.live.collect { queue.addLast(it) }
    }
    LaunchedEffect(drops) {
        while (true) {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                delay(POLL_MS)
                continue
            }
            current = next
            visible = true
            var shown = 0L
            while (visible && shown < SHOW_MS) {
                delay(POLL_MS)
                shown += POLL_MS
            }
            visible = false
            delay(GAP_MS)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        val drop = current ?: return@AnimatedVisibility
        DropCard(
            drop = drop,
            onRead = {
                visible = false
                context.startActivity(ReaderActivity.newIntent(context, drop.mangaId, drop.chapterId))
            },
            onClose = { visible = false },
        )
    }
}

@Composable
private fun DropCard(drop: FavoriteDrops.Drop, onRead: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .neonGlow(shape, colors.primary, 0.45f, 18.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xF00B0A0E), Color(0xF01A070C))))
            .border(1.dp, Brush.linearGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.2f))), shape)
            .clickable(onClick = onRead)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 70.dp)
                .neonGlow(RoundedCornerShape(12.dp), colors.primary, 0.35f, 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.primary.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = MangaCover(drop.mangaId, 0L, true, drop.thumbnail, null),
                contentDescription = null,
                placeholder = ColorPainter(Color(0xFF1A1418)),
                error = ColorPainter(Color(0xFF1A1418)),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Rounded.NotificationsActive, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(ATMR.strings.drop_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(drop.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (drop.chapterNumber >= 0) stringResource(ATMR.strings.history_chapter, formatChapterNumber(drop.chapterNumber)) else drop.chapterName,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            ManhwaKit.NeonButton(
                text = stringResource(ATMR.strings.drop_read_now),
                icon = MaterialSymbols.RoundedFilled.PlayArrow,
                modifier = Modifier.height(32.dp),
                onClick = onRead,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Top)
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaterialSymbols.Rounded.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

private const val SHOW_MS = 7_000L
private const val POLL_MS = 250L
private const val GAP_MS = 600L
