package eu.kanade.presentation.manga.components

import eu.kanade.presentation.components.swing
import eu.kanade.presentation.components.loop
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.animateFloatAsState
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
    // MANHWA: the light takes the cover's own colour (the rank's colour runs in the beam)
    val rank = rankColor(level.rank)
    val color = LocalCoverPalette.current?.primary ?: rank
    // 0 for a level-1 work, 1 from level 50 (rank S) on
    val power by animateFloatAsState(if (saved) 0.25f + 0.75f * ((level.level - 1) / 49f).coerceIn(0f, 1f) else 0f, tween(700), label = "aura")
    // MANHWA battery: on the app's one decoration clock, read only while drawing (and not at all with no aura)
    val decor = eu.kanade.presentation.components.LocalDecorTime.current
    val angle by remember(decor) { derivedStateOf { decor.loop(6f) * 360f } }
    val pulse by remember(decor) { derivedStateOf { 0.75f + 0.25f * decor.swing(3.2f) } }
    val flare by remember(decor) { derivedStateOf { decor.loop(2.4f) } }
    val sparks = androidx.compose.runtime.remember { List(28) { Triple(kotlin.random.Random.nextFloat(), kotlin.random.Random.nextFloat(), kotlin.random.Random.nextFloat()) } }
    Box(
        modifier
            .padding(14.dp)
            .drawBehind {
                if (power <= 0.01f) return@drawBehind
                val radius = CornerRadius(10.dp.toPx())
                // The corona: a sun of the cover's colour behind it, breathing
                val corona = size.maxDimension * (0.75f + 0.12f * pulse * power)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.55f * power), color.copy(alpha = 0.18f * power), Color.Transparent),
                        center = center,
                        radius = corona,
                    ),
                    radius = corona,
                    center = center,
                )
                // Rays turning slowly round it
                rotate(angle * 0.25f, center) {
                    for (i in 0 until 16) {
                        val a = (i / 16f) * 2 * kotlin.math.PI.toFloat()
                        val inner = size.minDimension * 0.45f
                        val outer = size.maxDimension * (0.62f + 0.18f * ((i % 3) / 2f)) * (0.8f + 0.2f * pulse)
                        val from = Offset(center.x + kotlin.math.cos(a) * inner, center.y + kotlin.math.sin(a) * inner)
                        val to = Offset(center.x + kotlin.math.cos(a) * outer, center.y + kotlin.math.sin(a) * outer)
                        drawLine(
                            Brush.linearGradient(listOf(color.copy(alpha = 0.35f * power), Color.Transparent), from, to),
                            from,
                            to,
                            strokeWidth = (2.dp + 3.dp * power).toPx(),
                        )
                    }
                }
                // Sparks thrown off the frame, flying out and fading
                sparks.forEach { (edge, seed, speed) ->
                    val life = (flare * (0.6f + speed * 0.8f) + seed) % 1f
                    // A point on the frame's edge
                    val per = 2 * (size.width + size.height)
                    val d = edge * per
                    val start = when {
                        d < size.width -> Offset(d, 0f)
                        d < size.width + size.height -> Offset(size.width, d - size.width)
                        d < 2 * size.width + size.height -> Offset(size.width - (d - size.width - size.height), size.height)
                        else -> Offset(0f, size.height - (d - 2 * size.width - size.height))
                    }
                    val dir = Offset(start.x - center.x, start.y - center.y)
                    val len = kotlin.math.sqrt(dir.x * dir.x + dir.y * dir.y).coerceAtLeast(1f)
                    val travel = (18.dp + 46.dp * power).toPx() * life
                    val at = Offset(start.x + dir.x / len * travel, start.y + dir.y / len * travel)
                    val fade = (1f - life) * power
                    drawCircle(Color.White.copy(alpha = 0.9f * fade), radius = (1.2.dp + 1.6.dp * (1f - life)).toPx(), center = at)
                    drawCircle(color.copy(alpha = 0.5f * fade), radius = (3.dp + 3.dp * (1f - life)).toPx(), center = at)
                }
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
                        rank,
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
