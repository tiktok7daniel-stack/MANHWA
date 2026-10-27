package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.home.stats.ReadingLevels
import eu.kanade.tachiyomi.ui.home.stats.rankColor

/**
 * MANHWA: the light around a cover. It shows only once the work is saved, takes the colour of its letter rank (E grey
 * to S gold) from the chapters read, and grows wider, brighter and faster as the level climbs.
 */
@Composable
fun CoverAura(saved: Boolean, chaptersRead: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val level = ReadingLevels.level(chaptersRead)
    val color = rankColor(level.rank)
    // 0 for a level-1 work, 1 from level 50 (rank S) on
    val power by animateFloatAsState(if (saved) 0.25f + 0.75f * ((level.level - 1) / 49f).coerceIn(0f, 1f) else 0f, tween(700), label = "aura")
    val spin = rememberInfiniteTransition(label = "aura")
    val angle by spin.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "angle",
    )
    val pulse by spin.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulse")
    Box(
        modifier
            .padding(14.dp)
            .drawBehind {
                if (power <= 0.01f) return@drawBehind
                val radius = CornerRadius(10.dp.toPx())
                // Soft halo: a few strokes, each wider and fainter, out from the frame
                val rings = 6
                val reach = (6.dp + 10.dp * power).toPx()
                for (i in rings downTo 1) {
                    val grow = reach * i / rings
                    drawRoundRect(
                        color = color.copy(alpha = 0.10f * power * pulse * (1f - (i - 1f) / rings)),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(radius.x + grow),
                        style = Stroke(reach / rings * 2),
                    )
                }
                // The beam running round the frame: a turning sweep of light, clipped to a thin ring on the frame
                val width = (2.dp + 2.dp * power).toPx()
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(RoundRect(-width, -width, size.width + width, size.height + width, CornerRadius(radius.x + width)))
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius))
                }
                val beam = Brush.sweepGradient(
                    listOf(
                        color.copy(alpha = 0.35f * power),
                        color,
                        Color.White.copy(alpha = 0.6f + 0.4f * power),
                        color,
                        color.copy(alpha = 0.35f * power),
                        color.copy(alpha = 0.35f * power),
                    ),
                    center = center,
                )
                clipPath(ring) {
                    rotate(angle, center) {
                        drawCircle(beam, radius = size.maxDimension, center = center)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
