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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * MANHWA neon glass: smoked translucent panel with a glass reflection on top, a glowing neon rim and a soft
 * light bleeding around the edge, which gives cards depth without a live backdrop blur.
 */
fun Modifier.glass(shape: Shape, accent: Boolean = false, alpha: Float = 0.55f): Modifier = composed {
    val look = LocalManhwaLook.current
    // MANHWA Obsidian Royal: a solid lit surface instead of the glass; the other looks draw their own panels
    when (look.style) {
        ManhwaStyle.OBSIDIAN -> return@composed obsidianSurface(shape, accent, look)
        ManhwaStyle.AURORA -> return@composed auroraSurface(shape, accent, look)
        ManhwaStyle.INK -> return@composed inkSurface(shape, accent, look)
        ManhwaStyle.HOLO -> return@composed holoSurface(shape, accent, look)
        ManhwaStyle.GLASS -> Unit
    }
    val colors = MaterialTheme.colorScheme
    // Built once per look, not on every recomposition of every card
    val brushes = remember(colors.primary, colors.surfaceContainerHighest, colors.surfaceContainer, accent, alpha) {
        Triple(
            Brush.verticalGradient(
                listOf(
                    colors.surfaceContainerHighest.copy(alpha = alpha + 0.1f),
                    colors.surfaceContainer.copy(alpha = alpha),
                ),
            ),
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.09f),
                0.35f to Color.Transparent,
                1f to Color.Transparent,
            ),
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = if (accent) 0.4f else 0.22f),
                    colors.primary.copy(alpha = if (accent) 0.95f else 0.5f),
                    Color.White.copy(alpha = 0.06f),
                ),
            ),
        )
    }
    this
        .neonGlow(shape, colors.primary, if (accent) 0.22f else 0.1f)
        .clip(shape)
        .background(brushes.first, shape)
        .background(brushes.second, shape)
        .border(1.dp, brushes.third, shape)
}

/**
 * MANHWA Obsidian Royal surface: a solid card (lightly tinted by the accent) with a hairline rim lit from above
 * (bright at the top, nearly gone at the bottom), and a very soft shadow on paper. No blur, no glow: almost nothing
 * for the GPU to draw.
 */
fun Modifier.obsidianSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val hairline = (1f / LocalDensity.current.density).dp
    val rim = remember(look, accent) {
        Brush.verticalGradient(listOf(if (accent) look.rimAccentTop else look.rimTop, look.rimBottom))
    }
    this
        .then(if (look.light) Modifier.shadow(2.dp, shape, ambientColor = ShadowTint, spotColor = ShadowTint) else Modifier)
        .clip(shape)
        .background(if (accent) look.surfaceRaised else look.surface, shape)
        .border(hairline, rim, shape)
}

private val ShadowTint = Color.Black.copy(alpha = 0.18f)

/** Soft neon light around [shape], drawn as fading strokes outside the edge. The outline is cached per size. */
fun Modifier.neonGlow(shape: Shape, color: Color, strength: Float = 0.18f, spread: Dp = 10.dp): Modifier =
    if (strength <= 0f) this else neonGlow(shape, color, { strength }, spread)

/**
 * Animated neon light: [strength] is read while drawing only, so a breathing glow redraws the glow alone and
 * never recomposes the card under it.
 */
fun Modifier.neonGlow(shape: Shape, color: Color, strength: () -> Float, spread: Dp = 10.dp): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val step = spread.toPx() / 4f
        val strokes = (4 downTo 1).map { i -> i to Stroke(width = step * i * 2f) }
        onDrawBehind {
            // MANHWA Obsidian Royal draws no glow
            if (!ManhwaStyleState.glow) return@onDrawBehind
            val value = strength()
            if (value <= 0f) return@onDrawBehind
            strokes.forEach { (i, stroke) -> drawOutline(outline, color.copy(alpha = value / i), style = stroke) }
        }
    }

/** Near-black backdrop with red ambient light and soft bokeh, used behind glass screens. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val look = LocalManhwaLook.current
    if (look.isObsidian) {
        // MANHWA Obsidian Royal: one deep, still colour (obsidian, or silk paper), nothing animated behind
        Box(modifier = modifier.fillMaxSize().background(look.background), content = content)
        return
    }
    // Aurora Veil, Manhwa Ink, Monarch Holo: their own sky, page or field
    if (look.isSolid) {
        LookBackdrop(look, modifier, content)
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF030304))) {
        // Its own layer, painted once per size: scrolling the content above never repaints the light
        Spacer(
            Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val top = Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(w * 0.95f, h * 0.04f),
                        radius = w * 1.05f,
                    )
                    val bottom = Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.16f), Color(0xFF3A0610).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(w * 0.05f, h * 0.92f),
                        radius = w * 1.2f,
                    )
                    val dots = Bokeh.map { (x, y, r, a) ->
                        val center = Offset(w * x, h * y)
                        Triple(
                            Brush.radialGradient(
                                listOf(primary.copy(alpha = a), primary.copy(alpha = a * 0.35f), Color.Transparent),
                                center = center,
                                radius = w * r,
                            ),
                            center,
                            w * r,
                        )
                    }
                    val vignette = Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        center = Offset(w / 2f, h / 2f),
                        radius = maxOf(w, h) * 0.75f,
                    )
                    onDrawBehind {
                        drawRect(top)
                        drawRect(bottom)
                        dots.forEach { (brush, center, radius) -> drawCircle(brush, radius = radius, center = center) }
                        drawRect(vignette)
                    }
                },
        )
        content()
    }
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
