package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MANHWA neon glass: smoked translucent panel with a glass reflection on top, a glowing neon rim and a soft
 * light bleeding around the edge, which gives cards depth without a live backdrop blur.
 */
fun Modifier.glass(shape: Shape, accent: Boolean = false, alpha: Float = 0.55f): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val fill = Brush.verticalGradient(
        listOf(
            colors.surfaceContainerHighest.copy(alpha = alpha + 0.1f),
            colors.surfaceContainer.copy(alpha = alpha),
        ),
    )
    val sheen = Brush.linearGradient(
        0f to Color.White.copy(alpha = 0.09f),
        0.35f to Color.Transparent,
        1f to Color.Transparent,
    )
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (accent) 0.4f else 0.22f),
            colors.primary.copy(alpha = if (accent) 0.95f else 0.5f),
            Color.White.copy(alpha = 0.06f),
        ),
    )
    this
        .neonGlow(shape, colors.primary, if (accent) 0.22f else 0.1f)
        .clip(shape)
        .background(fill, shape)
        .background(sheen, shape)
        .border(1.dp, rim, shape)
}

/** Soft neon light around [shape], drawn as fading strokes outside the edge. */
fun Modifier.neonGlow(shape: Shape, color: Color, strength: Float = 0.18f, spread: Dp = 10.dp): Modifier =
    drawBehind {
        if (strength <= 0f) return@drawBehind
        val outline = shape.createOutline(size, layoutDirection, this)
        val step = spread.toPx() / 4f
        for (i in 4 downTo 1) {
            drawOutline(outline, color.copy(alpha = strength / i), style = Stroke(width = step * i * 2f))
        }
    }

/** Near-black backdrop with red ambient light and soft bokeh, used behind glass screens. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030304))
            .drawBehind {
                val w = size.width
                val h = size.height
                // Ambient red light from the top corner and the bottom
                drawRect(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(w * 0.95f, h * 0.04f),
                        radius = w * 1.05f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.16f), Color(0xFF3A0610).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(w * 0.05f, h * 0.92f),
                        radius = w * 1.2f,
                    ),
                )
                // Bokeh
                Bokeh.forEach { (x, y, r, a) ->
                    val center = Offset(w * x, h * y)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(primary.copy(alpha = a), primary.copy(alpha = a * 0.35f), Color.Transparent),
                            center = center,
                            radius = w * r,
                        ),
                        radius = w * r,
                        center = center,
                    )
                }
                // Vignette
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        center = Offset(w / 2f, h / 2f),
                        radius = maxOf(w, h) * 0.75f,
                    ),
                )
            },
        content = content,
    )
}

private data class Dot(val x: Float, val y: Float, val r: Float, val a: Float)

private val Bokeh = listOf(
    Dot(0.82f, 0.18f, 0.10f, 0.16f),
    Dot(0.12f, 0.30f, 0.06f, 0.12f),
    Dot(0.65f, 0.46f, 0.04f, 0.14f),
    Dot(0.25f, 0.62f, 0.12f, 0.08f),
    Dot(0.90f, 0.72f, 0.07f, 0.12f),
    Dot(0.45f, 0.86f, 0.05f, 0.14f),
    Dot(0.05f, 0.08f, 0.08f, 0.10f),
)
