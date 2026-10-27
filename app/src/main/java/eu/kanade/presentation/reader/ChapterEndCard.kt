package eu.kanade.presentation.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.home.stats.ReadingLevels
import eu.kanade.tachiyomi.ui.home.stats.rankColor
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA: when the chapter on screen was opened, for the chapter-end card. */
object ReadingClock {
    @Volatile
    var startedAt: Long = 0L
}

/**
 * MANHWA: the card at the end of a chapter. How long it took, the work's level after it (with the bar filling up to
 * the new point), and a button on to the next chapter. A short buzz marks the finish.
 */
@Composable
fun ChapterEndCard(finished: Chapter, hasNext: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val accent = MaterialTheme.colorScheme.primary
    val minutes = remember(finished.id) {
        ReadingClock.startedAt.takeIf { it > 0 }?.let { ((System.currentTimeMillis() - it) / 60_000L).toInt().coerceAtLeast(1) }
    }
    val readCount by produceState<Int?>(null, finished.id) {
        value = runCatching {
            val chapters = context.appGraph.getChaptersByMangaId.await(finished.mangaId)
            chapters.count { it.read || it.id == finished.id }
        }.getOrNull()
    }
    LaunchedEffect(finished.id) {
        if (!context.appGraph.readerPreferences.haptics.get()) return@LaunchedEffect
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
        )
    }
    val appear = remember(finished.id) { Animatable(0f) }
    LaunchedEffect(finished.id) { appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    val level = readCount?.let { ReadingLevels.level(it) }
    val color = level?.let { rankColor(it.rank) } ?: accent
    val fill = remember(finished.id) { Animatable(0f) }
    LaunchedEffect(level?.fraction) {
        level?.let { fill.animateTo(if (it.into == 0) 1f else it.fraction, tween(1100, delayMillis = 300, easing = FastOutSlowInEasing)) }
    }

    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1 - appear.value) * 30.dp.toPx()
                scaleX = 0.94f + 0.06f * appear.value
                scaleY = 0.94f + 0.06f * appear.value
            }
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color(0xFF0D0D12))))
            .border(1.dp, Brush.linearGradient(listOf(color, Color.White.copy(alpha = 0.15f), color.copy(alpha = 0.4f))), RoundedCornerShape(24.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MaterialSymbols.RoundedFilled.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(ATMR.strings.end_card_title),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = finished.name,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(minutes?.let { stringResource(ATMR.strings.end_card_minutes, it) } ?: "-", stringResource(ATMR.strings.end_card_time))
            Stat(level?.let { "Lv. ${it.level}" } ?: "-", stringResource(ATMR.strings.end_card_level))
            Stat(level?.rank?.name ?: "-", stringResource(ATMR.strings.end_card_rank), color)
        }
        if (level != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = r)
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color)),
                            size = Size(size.width * fill.value, size.height),
                            cornerRadius = r,
                        )
                    },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(ATMR.strings.end_card_to_next, level.toNext),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
        }
        if (hasNext) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(color, accent)))
                    .clickable { context.readerActivity()?.goToNextChapter() }
                    .padding(horizontal = 22.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(ATMR.strings.end_card_next),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.size(8.dp))
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun android.content.Context.readerActivity(): ReaderActivity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is ReaderActivity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun Stat(value: String, label: String, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}
