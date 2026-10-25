package eu.kanade.presentation.components

import android.graphics.Matrix
import android.graphics.SweepGradient
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * MANHWA: the three looks added beside MANHWA Glass and Obsidian Royal. Each has its own surfaces, backdrop and
 * touch, all read from [ManhwaLook] by the same building blocks ([glass], [GlassBackdrop], [pressClickable]):
 *
 * - Aurora Veil: a midnight sky where three soft lights of the accent's family drift very slowly; frosted panels
 *   with an iridescent rim; a soft spring under the finger.
 * - Manhwa Ink: the page of a comic. Halftone dots and speed lines on manga paper (or night ink), panels with a
 *   bold ink border and a hard offset shadow, square-ish corners; a crisp press.
 * - Monarch Holo: the strongest. A slow holographic halo turning over the screen, embers rising, a faint system
 *   grid below; panels with a holographic rim (turning around the highlighted ones), a spring with a slight tilt.
 *
 * Motion is decorative and seen often, so it is slow, never moves the content, runs in its own layer and is read
 * while drawing only (nothing recomposes). One clock for the whole app ([ProvideLookClock]) ticks about 30 times a
 * second, only for the looks that move, and stops with the screen; every backdrop and rim reads its phase from it,
 * so screens keep the same phase when switching. With the phone's animations turned off it stands still.
 */

/** The fixed values of the three looks. */
object LookTokens {
    // Slate
    val SlateBackground = Color(0xFF111718)
    val SlateSurface = Color(0xFF1C2427)
    val SlateRaised = Color(0xFF252E31)
    val SlateInk = Color(0xFFF1F4F5)
    val SlateMuted = Color(0xFF8B979B)
    val SlateBlue = Color(0xFF3D7BF6)

    // Aurora Veil
    val AuroraBackground = Color(0xFF05060D)
    val AuroraSurface = Color(0xFF121627)
    val AuroraInk = Color(0xFFF2F4FF)
    val AuroraMuted = Color(0xFF8D93B0)

    // Manhwa Ink
    val InkPaper = Color(0xFFF4EFE3)
    val InkPaperSurface = Color(0xFFFFFCF5)
    val InkBlack = Color(0xFF141414)
    val InkPaperMuted = Color(0xFF6B655A)
    val InkNight = Color(0xFF0E0E10)
    val InkNightSurface = Color(0xFF19191D)
    val InkNightLine = Color(0xFFECE6D8)
    val InkNightMuted = Color(0xFF9A958B)

    // Monarch Holo
    val HoloBackground = Color(0xFF040407)
    val HoloSurface = Color(0xFF0B0B13)
    val HoloInk = Color(0xFFF7F7FF)
    val HoloMuted = Color(0xFF8B8CA3)
}

/** The colour [degrees] around the colour wheel from [color] (same saturation and brightness). */
fun Color.hueShift(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Whether the reader turned the phone's animations off: the looks then stand still. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** The looks' clock (milliseconds), read while drawing only. */
val LocalLookTime = staticCompositionLocalOf<State<Long>> { mutableLongStateOf(0L) }

/** The phone's animations are off: no motion, no bounce. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The one clock of the moving looks: ticks about 30 times a second while [moving] (Aurora Veil, Monarch Holo) and the
 * screen is drawn, from the phone's uptime, so every backdrop and rim keeps its phase across screens.
 */
@Composable
fun ProvideLookClock(moving: Boolean, content: @Composable () -> Unit) {
    val reduce = rememberReduceMotion()
    val time = remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    if (moving && !reduce) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameMillis { frame ->
                    if (frame - last >= TICK_MS) {
                        last = frame
                        time.longValue = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalLookTime provides time, LocalReduceMotion provides reduce, content = content)
}

/** Where a loop of [millis] stands at [time], 0 to 1. */
private fun phase(time: Long, millis: Int): Float = (time % millis) / millis.toFloat()

// ------------------------------------------------------------------ surfaces

/** Aurora Veil panel: frosted midnight, a sheen from the top, a rim going from the accent to its neighbour hue. */
fun Modifier.auroraSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    val brushes = remember(primary, accent, look) {
        Triple(
            Brush.verticalGradient(
                listOf(
                    lerp(look.surfaceRaised, primary, 0.10f).copy(alpha = if (accent) 0.86f else 0.74f),
                    look.surface.copy(alpha = if (accent) 0.80f else 0.62f),
                ),
            ),
            Brush.linearGradient(0f to Color.White.copy(alpha = 0.10f), 0.4f to Color.Transparent, 1f to Color.Transparent),
            Brush.linearGradient(
                listOf(
                    primary.copy(alpha = if (accent) 0.85f else 0.45f),
                    Color.White.copy(alpha = 0.14f),
                    primary.hueShift(50f).copy(alpha = if (accent) 0.7f else 0.3f),
                ),
            ),
        )
    }
    this
        .clip(shape)
        .background(brushes.first, shape)
        .background(brushes.second, shape)
        .border(1.dp, brushes.third, shape)
}

/**
 * Manhwa Ink panel: flat paper (or night ink) with a bold ink border and a hard shadow offset down and to the
 * reading side, as a comic panel; the highlighted one throws its shadow in the accent.
 */
fun Modifier.inkSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val shift = with(density) { (if (accent) 5.dp else 4.dp).toPx() }
    val line = if (look.light) LookTokens.InkBlack else LookTokens.InkNightLine
    // Paper throws an ink shadow; night ink throws the accent, so the panels pop off the dark page
    val shadow = when {
        accent -> primary
        look.light -> LookTokens.InkBlack
        else -> primary.copy(alpha = 0.55f)
    }
    this
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            // Toward the reading side: down and left in Arabic, down and right otherwise
            val dx = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) -shift else shift
            onDrawBehind { translate(left = dx, top = shift) { drawOutline(outline, shadow) } }
        }
        .clip(shape)
        .background(if (accent) look.surfaceRaised else look.surface, shape)
        .border(if (accent) 2.dp else 1.5.dp, line, shape)
}

/**
 * Monarch Holo panel: deep glass with an iridescent rim (the accent and its two neighbours on the colour wheel).
 * On a highlighted panel the rim turns slowly all the way round.
 */
fun Modifier.holoSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    val clock = LocalLookTime.current
    val fill = remember(primary, accent, look) {
        Pair(
            Brush.verticalGradient(
                listOf(
                    lerp(look.surfaceRaised, primary, 0.12f).copy(alpha = 0.92f),
                    look.surface.copy(alpha = 0.86f),
                ),
            ),
            Brush.linearGradient(0f to Color.White.copy(alpha = 0.12f), 0.3f to Color.Transparent, 1f to Color.Transparent),
        )
    }
    val width = with(LocalDensity.current) { (if (accent) 1.5.dp else 1.dp).toPx() }
    this
        .clip(shape)
        .background(fill.first, shape)
        .background(fill.second, shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val colors = holoColors(primary, if (accent) 1f else 0.55f)
            val shader = SweepGradient(size.width / 2f, size.height / 2f, colors, null)
            val brush = ShaderBrush(shader)
            val matrix = Matrix()
            // The stroke is centred on the edge and half of it is clipped away: twice the width keeps it whole
            val stroke = Stroke(width = width * 2f)
            onDrawWithContent {
                drawContent()
                // Only a highlighted panel turns its rim (one shared clock, no animation of its own)
                if (accent) {
                    matrix.setRotate(phase(clock.value, HOLO_RIM_MS) * 360f, size.width / 2f, size.height / 2f)
                    shader.setLocalMatrix(matrix)
                }
                drawOutline(outline, brush, style = stroke)
            }
        }
}

private fun holoColors(primary: Color, alpha: Float): IntArray = intArrayOf(
    primary.copy(alpha = alpha).toArgb(),
    primary.hueShift(60f).copy(alpha = alpha * 0.8f).toArgb(),
    Color.White.copy(alpha = alpha * 0.5f).toArgb(),
    primary.hueShift(-60f).copy(alpha = alpha * 0.8f).toArgb(),
    primary.copy(alpha = alpha).toArgb(),
)

/**
 * The accent fill of the main buttons, active icons and selected chips, in the look's way:
 * MANHWA Glass and Obsidian Royal the deep accent gradient (with the neon glow on Glass), Aurora Veil a sweep from
 * the accent to its neighbour hue, Manhwa Ink a flat accent block inked and throwing a hard shadow, Monarch Holo
 * the accent gradient with a holographic rim.
 */
fun Modifier.accentFill(shape: Shape, glow: Float = 0.25f): Modifier = composed {
    val look = LocalManhwaLook.current
    val primary = MaterialTheme.colorScheme.primary
    when (look.style) {
        // Slate: one flat blue, nothing around it
        ManhwaStyle.SLATE -> this
            .clip(shape)
            .background(primary, shape)
        ManhwaStyle.AURORA -> this
            .clip(shape)
            .background(Brush.linearGradient(listOf(primary, primary.hueShift(45f))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
        ManhwaStyle.INK -> {
            val shift = with(LocalDensity.current) { 3.dp.toPx() }
            val line = if (look.light) LookTokens.InkBlack else LookTokens.InkNightLine
            val shadow = if (look.light) LookTokens.InkBlack else Color.Black
            this
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val dx = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) -shift else shift
                    onDrawBehind { translate(left = dx, top = shift) { drawOutline(outline, shadow) } }
                }
                .clip(shape)
                .background(primary, shape)
                .border(1.5.dp, line, shape)
        }
        ManhwaStyle.HOLO -> this
            .neonGlow(shape, primary, glow * 0.6f, 8.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(primary, lerp(primary.hueShift(-40f), Color.Black, 0.3f))), shape)
            .border(1.dp, Brush.sweepGradient(listOf(Color.White.copy(alpha = 0.6f), primary.hueShift(60f), Color.White.copy(alpha = 0.2f), primary.hueShift(-60f), Color.White.copy(alpha = 0.6f))), shape)
        else -> this
            .neonGlow(shape, primary, glow, 10.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(primary, lerp(primary, Color.Black, 0.45f))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
    }
}

// ------------------------------------------------------------------ backdrops

/** The backdrop of the three looks, behind every glass screen. */
@Composable
fun LookBackdrop(look: ManhwaLook, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxSize().background(look.background)) {
        when (look.style) {
            ManhwaStyle.AURORA -> AuroraSky(primary)
            ManhwaStyle.INK -> InkPage(primary, look.light)
            ManhwaStyle.HOLO -> HoloField(primary)
            else -> Unit
        }
        content()
    }
}

/** Three soft lights of the accent's family drifting round the sky, once every 48 seconds. */
@Composable
private fun BoxScope.AuroraSky(primary: Color) {
    val clock = LocalLookTime.current
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val lights = listOf(
                    Triple(primary, 0.30f, 0f),
                    Triple(primary.hueShift(45f), 0.22f, 0.33f),
                    Triple(primary.hueShift(-55f), 0.20f, 0.66f),
                ).map { (color, alpha, start) ->
                    // Built once, centred at the origin; each frame only moves it
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.3f), Color.Transparent),
                        center = Offset.Zero,
                        radius = w * 0.85f,
                    ) to start
                }
                val fade = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to LookTokens.AuroraBackground.copy(alpha = 0.35f),
                    1f to LookTokens.AuroraBackground.copy(alpha = 0.85f),
                )
                onDrawBehind {
                    val t = phase(clock.value, AURORA_MS)
                    lights.forEachIndexed { i, (brush, start) ->
                        val a = 2.0 * PI * (t + start)
                        val x = w * (0.5f + 0.32f * cos(a).toFloat())
                        val y = h * (0.22f + 0.12f * sin(a * (if (i % 2 == 0) 1 else -1)).toFloat() + i * 0.1f)
                        translate(x, y) { drawCircle(brush, radius = w * 0.85f, center = Offset.Zero) }
                    }
                    drawRect(fade)
                }
            },
    )
}

/** Halftone dots growing toward the top corner and speed lines from the bottom one: a comic page, drawn once. */
@Composable
private fun BoxScope.InkPage(primary: Color, light: Boolean) {
    val ink = if (light) LookTokens.InkBlack else LookTokens.InkNightLine
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val step = 9.dp.toPx()
                val maxDot = step * 0.42f
                val dots = buildList {
                    var y = 0f
                    var row = 0
                    while (y < h * 0.5f) {
                        var x = if (row % 2 == 0) 0f else step / 2f
                        while (x < w) {
                            // Biggest at the top end corner, gone by the middle of the screen
                            val d = kotlin.math.hypot((w - x) / w, y / (h * 0.5f))
                            val r = maxDot * (1f - d).coerceIn(0f, 1f)
                            if (r > 0.4f) add(Triple(x, y, r))
                            x += step
                        }
                        y += step
                        row++
                    }
                }
                val lines = (0 until SPEED_LINES).map { i ->
                    val angle = -PI / 2 * (i + 0.5) / SPEED_LINES
                    val inner = w * (0.25f + (i % 3) * 0.06f)
                    val outer = w * 1.1f
                    Path().apply {
                        moveTo((inner * cos(angle)).toFloat(), h + (inner * sin(angle)).toFloat())
                        lineTo((outer * cos(angle - 0.012)).toFloat(), h + (outer * sin(angle - 0.012)).toFloat())
                        lineTo((outer * cos(angle + 0.012)).toFloat(), h + (outer * sin(angle + 0.012)).toFloat())
                        close()
                    }
                }
                val dotColor = ink.copy(alpha = if (light) 0.10f else 0.07f)
                val lineColor = primary.copy(alpha = if (light) 0.10f else 0.12f)
                onDrawBehind {
                    dots.forEach { (x, y, r) -> drawCircle(dotColor, radius = r, center = Offset(x, y)) }
                    lines.forEach { drawPath(it, lineColor) }
                }
            },
    )
}

/** A holographic halo turning once a minute over the top, embers rising, and a faint grid at the bottom. */
@Composable
private fun BoxScope.HoloField(primary: Color) {
    val clock = LocalLookTime.current
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val haloCenter = Offset(w * 0.5f, h * 0.08f)
                val haloRadius = w * 1.1f
                val halo = Brush.sweepGradient(
                    listOf(
                        primary.copy(alpha = 0.26f),
                        primary.hueShift(60f).copy(alpha = 0.18f),
                        Color.Transparent,
                        primary.hueShift(-60f).copy(alpha = 0.18f),
                        primary.copy(alpha = 0.26f),
                    ),
                    center = haloCenter,
                )
                val haloMask = Brush.radialGradient(
                    listOf(Color.Transparent, LookTokens.HoloBackground.copy(alpha = 0.6f), LookTokens.HoloBackground),
                    center = haloCenter,
                    radius = haloRadius,
                )
                val gridTop = h * 0.72f
                val gridColor = primary.copy(alpha = 0.06f)
                val gridStroke = 1.dp.toPx()
                val ember = primary.hueShift(20f)
                val dot = 1.6.dp.toPx()
                onDrawBehind {
                    val now = clock.value
                    rotate(phase(now, HOLO_HALO_MS) * 360f, haloCenter) { drawCircle(halo, radius = haloRadius, center = haloCenter) }
                    drawRect(haloMask)
                    // The system grid: lines running to a vanishing point, and rows getting closer far away
                    val vanish = Offset(w / 2f, gridTop)
                    for (i in -6..6) drawLine(gridColor, vanish, Offset(w / 2f + i * w * 0.22f, h), gridStroke)
                    var k = 1f
                    while (k < 7f) {
                        val y = gridTop + (h - gridTop) * (k * k / 49f)
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), gridStroke)
                        k += 1f
                    }
                    val t = phase(now, HOLO_EMBER_MS)
                    Embers.forEach { e ->
                        val y = ((e.y - t * e.speed) % 1f + 1f) % 1f
                        val x = e.x + 0.015f * sin(2.0 * PI * (t * 3 + e.y)).toFloat()
                        // Fading in at the bottom and out near the top
                        val alpha = e.alpha * (y * (1f - y) * 4f).coerceIn(0f, 1f)
                        drawCircle(ember.copy(alpha = alpha), radius = dot * e.size, center = Offset(w * x, h * y))
                    }
                }
            },
    )
}

private class Ember(val x: Float, val y: Float, val speed: Float, val size: Float, val alpha: Float)

private val Embers = List(28) { i ->
    // A fixed scatter, the same on every phone
    val r = { n: Int -> ((i * 73 + n * 151) % 100) / 100f }
    Ember(x = r(1), y = r(2), speed = 0.6f + r(3) * 0.8f, size = 0.6f + r(4) * 1.0f, alpha = 0.25f + r(5) * 0.5f)
}

// ------------------------------------------------------------------ colour schemes

/** The dark scheme of a solid look: the MANHWA scheme laid on the look's own background and surfaces. */
fun lookDarkScheme(base: ColorScheme, background: Color, surface: Color, raised: Color, ink: Color, muted: Color): ColorScheme {
    val accent = base.primary
    val tinted = lerp(surface, accent, 0.05f)
    return base.copy(
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = tinted,
        onSurfaceVariant = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(background, tinted, 0.5f),
        surfaceContainer = tinted,
        surfaceContainerHigh = lerp(raised, accent, 0.06f),
        surfaceContainerHighest = lerp(raised, accent, 0.09f),
        outline = muted.copy(alpha = 0.5f),
        outlineVariant = ink.copy(alpha = 0.08f),
    )
}

/** Manhwa Ink on paper: manga paper, cream panels, black ink, the accent deep enough to read. */
fun inkLightScheme(accent: Color, dark: ColorScheme): ColorScheme {
    val primary = readableOnWhite(accent)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = lerp(primary, LookTokens.InkPaper, 0.8f),
        onPrimaryContainer = lerp(primary, Color.Black, 0.55f),
        secondary = lerp(primary, LookTokens.InkBlack, 0.25f),
        onSecondary = Color.White,
        secondaryContainer = lerp(primary, LookTokens.InkPaper, 0.88f),
        onSecondaryContainer = lerp(primary, Color.Black, 0.6f),
        tertiary = dark.tertiary,
        background = LookTokens.InkPaper,
        onBackground = LookTokens.InkBlack,
        surface = LookTokens.InkPaper,
        onSurface = LookTokens.InkBlack,
        surfaceVariant = Color(0xFFEAE3D3),
        onSurfaceVariant = LookTokens.InkPaperMuted,
        surfaceTint = primary,
        surfaceContainerLowest = LookTokens.InkPaperSurface,
        surfaceContainerLow = Color(0xFFF8F4EA),
        surfaceContainer = LookTokens.InkPaperSurface,
        surfaceContainerHigh = Color(0xFFEEE8DA),
        surfaceContainerHighest = Color(0xFFE6DFCE),
        outline = LookTokens.InkBlack.copy(alpha = 0.6f),
        outlineVariant = LookTokens.InkBlack.copy(alpha = 0.12f),
        error = dark.error,
        inversePrimary = accent,
    )
}

/** [accent] darkened until white text on it reads well (4.5:1). */
fun readableOnWhite(accent: Color): Color {
    var color = accent
    repeat(6) { if (color.luminance() > READABLE_LUMINANCE) color = lerp(color, Color.Black, 0.2f) }
    return color
}

private const val READABLE_LUMINANCE = 0.18f
private const val TICK_MS = 33L
private const val AURORA_MS = 48_000
private const val HOLO_HALO_MS = 60_000
private const val HOLO_EMBER_MS = 36_000
private const val HOLO_RIM_MS = 7_000
private const val SPEED_LINES = 14

/** Slate surface: a flat slate card with a hairline edge (blue when highlighted), no glow and no gradient. */
fun Modifier.slateSurface(shape: Shape, accent: Boolean, look: ManhwaLook): Modifier = composed {
    val primary = MaterialTheme.colorScheme.primary
    this
        .clip(shape)
        .background(if (accent) lerp(look.surface, primary, 0.12f) else look.surface, shape)
        .border(1.dp, if (accent) primary.copy(alpha = 0.7f) else look.rimTop, shape)
}
