package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * MANHWA: the three pro looks.
 *
 * - [ManhwaStyle.DOCK] (Floating Glass): frosted glass panels with a thin iridescent rim over drifting aurora
 *   lights, and the navigation as a vertical glass capsule floating at the side instead of the bottom dock.
 * - [ManhwaStyle.TACTILE] (Neo Tactile 3D): extruded panels lit from the top left with two soft shadows, so they
 *   look raised and touchable; the highlighted ones get an electric cyan rim, and the press sinks them in.
 * - [ManhwaStyle.HUNTER] (Hunter System): the "system window" of hunter manhwa: chamfered panels whatever shape
 *   is asked, glowing corner brackets, a blue to cyan gradient on the main buttons, a grid and scan lines behind.
 */
object ProTokens {
    // Floating Glass
    val DockBackground = Color(0xFF06070F)
    val DockSurface = Color(0xFF131729)
    val DockInk = Color(0xFFF3F5FF)
    val DockMuted = Color(0xFF8E95B5)
    val DockAccent = Color(0xFF7B8CFF)

    // Neo Tactile
    val TactileBackground = Color(0xFF1B1F27)
    val TactileSurface = Color(0xFF1F242D)
    val TactileRaised = Color(0xFF262C36)
    val TactileInk = Color(0xFFE9EEF5)
    val TactileMuted = Color(0xFF8792A3)
    val TactileCyan = Color(0xFF22D3EE)

    // Hunter System
    val HunterBackground = Color(0xFF050912)
    val HunterSurface = Color(0xFF0B1324)
    val HunterRaised = Color(0xFF111D36)
    val HunterInk = Color(0xFFE6F1FF)
    val HunterMuted = Color(0xFF7F95B8)
    val HunterBlue = Color(0xFF2F7BFF)
    val HunterCyan = Color(0xFF36E2FF)

    /** The cut of Hunter System's corners. */
    val HunterCut = CutCornerShape(10.dp)
    val HunterCutSmall = CutCornerShape(6.dp)
}

/** A look's own scheme: its accent (not the side menu's) on its surfaces. */
fun proDarkScheme(base: ColorScheme, accent: Color, background: Color, surface: Color, raised: Color, ink: Color, muted: Color): ColorScheme =
    base.copy(
        primary = accent,
        onPrimary = if (accent.luminanceOf() > 0.6f) Color(0xFF041018) else Color.White,
        primaryContainer = lerp(accent, Color.Black, 0.55f),
        onPrimaryContainer = lerp(accent, Color.White, 0.8f),
        secondary = lerp(accent, Color.White, 0.2f),
        onSecondary = Color.White,
        secondaryContainer = lerp(accent, background, 0.75f),
        onSecondaryContainer = lerp(accent, Color.White, 0.85f),
        tertiary = lerp(accent, Color.White, 0.3f),
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = surface,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(background, surface, 0.5f),
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = lerp(raised, accent, 0.06f),
        outline = muted.copy(alpha = 0.5f),
        outlineVariant = ink.copy(alpha = 0.08f),
    )

private fun Color.luminanceOf(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ------------------------------------------------------------------ surfaces

/** Floating Glass: frosted panel, a top sheen, and a 1px rim turning through the accent's neighbours. */
fun Modifier.dockSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    val brushes = remember(primary, accent, look) {
        Triple(
            Brush.verticalGradient(listOf(look.surface.copy(alpha = if (accent) 0.82f else 0.68f), look.surface.copy(alpha = if (accent) 0.6f else 0.45f))),
            Brush.linearGradient(0f to Color.White.copy(alpha = 0.10f), 0.4f to Color.Transparent, 1f to Color.Transparent),
            Brush.sweepGradient(
                listOf(
                    primary.copy(alpha = if (accent) 0.9f else 0.45f),
                    primary.hueShift(60f).copy(alpha = if (accent) 0.8f else 0.35f),
                    Color.White.copy(alpha = 0.35f),
                    primary.hueShift(-60f).copy(alpha = if (accent) 0.8f else 0.35f),
                    primary.copy(alpha = if (accent) 0.9f else 0.45f),
                ),
            ),
        )
    }
    this
        .neonGlow(shape, primary, if (accent) 0.14f else 0f, 8.dp)
        .clip(shape)
        .background(brushes.first, shape)
        .background(brushes.second, shape)
        .border(1.dp, brushes.third, shape)
}

/**
 * Neo Tactile: the panel is the page raised: a light shadow up and left, a dark one down and right, both soft.
 * A highlighted panel is ringed with electric cyan.
 */
fun Modifier.tactileSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val offset = with(density) { 5.dp.toPx() }
    val blur = with(density) { 10.dp.toPx() }
    this
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }.asAndroidPath()
            fun soft(color: Color) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.toArgb()
                maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            val light = soft(Color.White.copy(alpha = 0.06f))
            val dark = soft(Color.Black.copy(alpha = 0.55f))
            onDrawBehind {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save(); native.translate(-offset, -offset); native.drawPath(path, light); native.restore()
                    native.save(); native.translate(offset, offset); native.drawPath(path, dark); native.restore()
                }
            }
        }
        .neonGlow(shape, primary, if (accent) 0.28f else 0f, 10.dp)
        .clip(shape)
        .background(
            Brush.linearGradient(listOf(lerp(look.surfaceRaised, Color.White, 0.03f), look.surface)),
            shape,
        )
        .border(
            if (accent) 1.5.dp else 1.dp,
            if (accent) {
                Brush.linearGradient(listOf(primary, primary.copy(alpha = 0.4f)))
            } else {
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Black.copy(alpha = 0.25f)))
            },
            shape,
        )
}

/**
 * Hunter System: whatever the caller's shape, a chamfered window, a cool blue fill, a thin cyan edge and four
 * glowing corner brackets (brighter on a highlighted window).
 */
fun Modifier.hunterSurface(accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    val shape = ProTokens.HunterCut
    val bracket = with(LocalDensity.current) { 12.dp.toPx() }
    val stroke = with(LocalDensity.current) { 2.dp.toPx() }
    val cut = with(LocalDensity.current) { 10.dp.toPx() }
    this
        .neonGlow(shape, ProTokens.HunterCyan, if (accent) 0.2f else 0.06f, 8.dp)
        .clip(shape)
        .background(
            Brush.verticalGradient(listOf(look.surfaceRaised.copy(alpha = 0.92f), look.surface.copy(alpha = 0.86f))),
            shape,
        )
        .border(1.dp, (if (accent) ProTokens.HunterCyan else primary).copy(alpha = if (accent) 0.85f else 0.35f), shape)
        .drawBehind {
            // Corner brackets just inside the cuts
            val c = ProTokens.HunterCyan.copy(alpha = if (accent) 1f else 0.7f)
            val w = size.width
            val h = size.height
            val i = cut * 0.55f
            fun l(a: Offset, b: Offset) = drawLine(c, a, b, stroke, StrokeCap.Square)
            l(Offset(i, cut + bracket), Offset(i, cut)); l(Offset(cut, i), Offset(cut + bracket, i))
            l(Offset(w - i, cut + bracket), Offset(w - i, cut)); l(Offset(w - cut, i), Offset(w - cut - bracket, i))
            l(Offset(i, h - cut - bracket), Offset(i, h - cut)); l(Offset(cut, h - i), Offset(cut + bracket, h - i))
            l(Offset(w - i, h - cut - bracket), Offset(w - i, h - cut)); l(Offset(w - cut, h - i), Offset(w - cut - bracket, h - i))
        }
}

/** The filled main button of each pro look. */
fun Modifier.proAccentFill(style: ManhwaStyle, primary: Color, shape: Shape, glow: Float): Modifier = when (style) {
    ManhwaStyle.HUNTER -> this
        .neonGlow(ProTokens.HunterCutSmall, ProTokens.HunterCyan, glow * 0.8f, 10.dp)
        .clip(ProTokens.HunterCutSmall)
        .background(Brush.horizontalGradient(listOf(ProTokens.HunterBlue, ProTokens.HunterCyan)), ProTokens.HunterCutSmall)
    ManhwaStyle.TACTILE -> this
        .neonGlow(shape, primary, glow * 1.2f, 12.dp)
        .clip(shape)
        .background(Brush.verticalGradient(listOf(lerp(primary, Color.White, 0.18f), lerp(primary, Color.Black, 0.12f))), shape)
        .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
    else -> this
        .neonGlow(shape, primary, glow * 0.7f, 10.dp)
        .clip(shape)
        .background(Brush.linearGradient(listOf(primary, primary.hueShift(40f))), shape)
        .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
}

// ------------------------------------------------------------------ backdrops

/** Hunter System's page: a faint blue grid and slow scan lines, with a cold glow from the top. */
@Composable
fun BoxScope.HunterGrid(primary: Color) {
    val clock = LocalLookTime.current
    Spacer(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val step = 28.dp.toPx()
                val line = primary.copy(alpha = 0.06f)
                val glow = Brush.radialGradient(
                    listOf(ProTokens.HunterCyan.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width / 2f, 0f),
                    radius = size.width,
                )
                onDrawBehind {
                    drawRect(glow)
                    var x = 0f
                    while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                    var y = 0f
                    while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
                    // One scan line going down, once every 7 seconds
                    val t = (clock.value % 7_000L) / 7_000f
                    val sy = t * size.height
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, ProTokens.HunterCyan.copy(alpha = 0.08f), Color.Transparent), startY = sy - 60f, endY = sy + 60f),
                        topLeft = Offset(0f, sy - 60f),
                        size = androidx.compose.ui.geometry.Size(size.width, 120f),
                    )
                }
            },
    )
}

/** Neo Tactile's page: one soft light from the top left. */
@Composable
fun BoxScope.TactileLight(primary: Color) {
    Spacer(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val light = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = size.maxDimension,
                )
                val tint = Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(size.width, size.height),
                    radius = size.maxDimension * 0.8f,
                )
                onDrawBehind {
                    drawRect(light)
                    drawRect(tint)
                }
            },
    )
}
