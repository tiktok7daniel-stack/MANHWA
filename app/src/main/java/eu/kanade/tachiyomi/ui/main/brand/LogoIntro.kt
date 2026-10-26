package eu.kanade.tachiyomi.ui.main.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.main.ManhwaIntro
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val START_TIMEOUT_MS = 3_000L

/** The logo's width over its height. */
private const val MARK_RATIO = 275f / 133f

/**
 * MANHWA: the opening on a cold start, in the [style] picked (AUTO plays the [logo]'s own), at [speed] (1 is the
 * designed pace). A tap skips it; if the system splash never leaves, it lets the app through after a few seconds.
 */
@Composable
fun LogoIntro(style: IntroStyle, logo: AppLogo, speed: Float, started: Boolean, onFinished: () -> Unit) {
    val played = if (style == IntroStyle.AUTO) logo.intro else style
    if (played == IntroStyle.RIFT) {
        ManhwaIntro(started = started, onFinished = onFinished)
        return
    }
    val finish by rememberUpdatedState(onFinished)
    val currentStarted by rememberUpdatedState(started)
    var done by remember { mutableStateOf(false) }
    fun end() {
        if (!done) {
            done = true
            finish()
        }
    }
    LaunchedEffect(Unit) {
        delay(START_TIMEOUT_MS)
        if (!currentStarted) end()
    }
    val length = when (played) {
        IntroStyle.ORBIT -> 2200
        IntroStyle.GLITCH -> 1900
        else -> 2000
    }
    val t = remember { Animatable(0f) }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        t.animateTo(1f, tween((length / speed.coerceIn(0.5f, 2f)).toInt(), easing = LinearEasing))
        end()
    }
    val light = logo == AppLogo.PAPER
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - seg(t.value, 0.9f, 1f) }
            .background(Brush.verticalGradient(listOf(logo.top, if (light) logo.bottom else Color.Black)))
            .pointerInput(Unit) { detectTapGestures { end() } },
        contentAlignment = Alignment.Center,
    ) {
        when (played) {
            IntroStyle.BLOOM -> Bloom(logo, t)
            IntroStyle.GLITCH -> Glitch(logo, t)
            IntroStyle.ORBIT -> Orbit(logo, t)
            else -> Rise(logo, t)
        }
    }
}

/** How far [t] is through the stretch from [a] to [b], from 0 to 1. */
private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

/** Goes a little past 1 and settles back, as a spring does. */
private fun easeOutBack(x: Float): Float {
    val c = 1.7f
    val y = x - 1f
    return 1f + (c + 1f) * y * y * y + c * y * y
}

@Composable
private fun Mark(logo: AppLogo, modifier: Modifier = Modifier, tint: Color? = null) {
    Image(
        painter = painterResource(logo.mark),
        contentDescription = null,
        colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) },
        modifier = modifier.fillMaxWidth(0.72f).aspectRatio(MARK_RATIO),
    )
}

// ------------------------------------------------------------------ bloom (gold)

/** A glow opens, the logo swells into it past its size and settles, sparks twinkle round it, then it lifts away. */
@Composable
private fun Bloom(logo: AppLogo, t: Animatable<Float, *>) {
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        val open = easeOut(seg(p, 0f, 0.5f))
        drawCircle(
            Brush.radialGradient(
                listOf(logo.accent.copy(alpha = 0.45f * open), Color.Transparent),
                center = center,
                radius = size.minDimension * (0.15f + 0.6f * open),
            ),
            radius = size.minDimension * (0.15f + 0.6f * open),
        )
        val twinkle = seg(p, 0.35f, 0.85f)
        repeat(14) { i ->
            val a = i / 14f * 2f * PI.toFloat() + 0.4f
            val r = size.minDimension * (0.3f + 0.06f * ((i * 37) % 5))
            val phase = sin((twinkle * 3f + i * 0.37f) * PI.toFloat()).coerceAtLeast(0f) * twinkle
            spark(Offset(center.x + cos(a) * r, center.y + sin(a) * r * 0.7f), 6.dp.toPx() * phase, logo.accent.copy(alpha = phase))
        }
    }
    Mark(
        logo,
        Modifier.graphicsLayer {
            val p = t.value
            val s = 0.7f + 0.3f * easeOutBack(seg(p, 0.05f, 0.5f)) + 0.12f * seg(p, 0.85f, 1f)
            scaleX = s
            scaleY = s
            alpha = seg(p, 0.05f, 0.3f)
            translationY = -24.dp.toPx() * seg(p, 0.85f, 1f)
        },
    )
}

private fun DrawScope.spark(at: Offset, r: Float, color: Color) {
    if (r <= 0f) return
    drawLine(color, Offset(at.x - r, at.y), Offset(at.x + r, at.y), strokeWidth = r / 4f)
    drawLine(color, Offset(at.x, at.y - r), Offset(at.x, at.y + r), strokeWidth = r / 4f)
    drawCircle(color, radius = r / 4f, center = at)
}

// ------------------------------------------------------------------ glitch (neon)

/** The logo comes in torn into cyan and magenta over scanlines, snaps together with a flash, then shuts off. */
@Composable
private fun Glitch(logo: AppLogo, t: Animatable<Float, *>) {
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        val step = 4.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        // Torn bars while the signal is still settling
        val noise = 1f - seg(p, 0f, 0.5f)
        if (noise > 0f) {
            repeat(5) { i ->
                val by = ((sin(p * 47f + i * 1.7f) + 1f) / 2f) * size.height
                drawRect(
                    (if (i % 2 == 0) logo.accent else Color(0xFFD946EF)).copy(alpha = 0.18f * noise),
                    topLeft = Offset(0f, by),
                    size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx() + i * 2f),
                )
            }
        }
        val flash = seg(p, 0.5f, 0.53f) * (1f - seg(p, 0.53f, 0.62f))
        if (flash > 0f) drawRect(Color.White.copy(alpha = 0.5f * flash))
    }
    val shake: (Float, Float) -> Float = { p, k -> sin(p * 61f + k) * (1f - seg(p, 0f, 0.5f)) }
    Box(
        Modifier.graphicsLayer {
            val p = t.value
            // Shut off like an old screen: a thin bright line, then nothing
            val off = seg(p, 0.84f, 0.96f)
            scaleY = 1f - 0.98f * off
            scaleX = 1f + 0.2f * off
            alpha = seg(p, 0f, 0.12f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Mark(logo, Modifier.graphicsLayer { translationX = 14.dp.toPx() * shake(t.value, 0f); alpha = 0.75f }, tint = Color(0xFF22D3EE))
        Mark(logo, Modifier.graphicsLayer { translationX = -14.dp.toPx() * shake(t.value, 2f); alpha = 0.75f }, tint = Color(0xFFD946EF))
        Mark(logo, Modifier.graphicsLayer { alpha = seg(t.value, 0.3f, 0.5f) })
    }
}

// ------------------------------------------------------------------ orbit (nova)

/** Rings of light turn in and the logo swings round to face the camera, a pulse leaves it, then the camera goes through. */
@Composable
private fun Orbit(logo: AppLogo, t: Animatable<Float, *>) {
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        val inn = easeOut(seg(p, 0f, 0.45f))
        val spin = p * 260f
        val r = size.minDimension * 0.42f
        for (k in 0..1) {
            rotate(spin * (if (k == 0) 1f else -0.7f) + k * 60f, center) {
                drawOval(
                    logo.accent.copy(alpha = 0.55f * inn * (1f - seg(p, 0.8f, 1f))),
                    topLeft = Offset(center.x - r, center.y - r * 0.32f),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 0.64f),
                    style = Stroke(width = 1.6.dp.toPx()),
                )
                val a = (p * 4f + k) * 2f * PI.toFloat()
                drawCircle(Color.White.copy(alpha = inn), radius = 3.dp.toPx(), center = Offset(center.x + cos(a) * r, center.y + sin(a) * r * 0.32f))
            }
        }
        val pulse = seg(p, 0.5f, 0.85f)
        if (pulse > 0f && pulse < 1f) {
            drawCircle(logo.accent.copy(alpha = 0.6f * (1f - pulse)), radius = size.minDimension * (0.2f + 0.5f * pulse), style = Stroke(width = 3.dp.toPx() * (1f - pulse) + 1f))
        }
    }
    Mark(
        logo,
        Modifier.graphicsLayer {
            val p = t.value
            val face = easeOut(seg(p, 0.1f, 0.55f))
            cameraDistance = 14f * density
            rotationY = 80f * (1f - face)
            val s = 0.85f + 0.15f * face + 0.7f * easeOut(seg(p, 0.85f, 1f))
            scaleX = s
            scaleY = s
            alpha = seg(p, 0.1f, 0.3f)
        },
    )
}

// ------------------------------------------------------------------ rise (paper)

/** A line draws itself, a red sun grows behind it, and the logo rises out of the line; then it all lifts away. */
@Composable
private fun Rise(logo: AppLogo, t: Animatable<Float, *>) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val markWidth = maxWidth * 0.72f
        Canvas(Modifier.fillMaxSize()) {
            val p = t.value
            val sun = easeOut(seg(p, 0.1f, 0.45f))
            drawCircle(logo.accent.copy(alpha = 0.22f * sun), radius = size.minDimension * 0.28f * sun, center = Offset(center.x, center.y - size.minDimension * 0.06f))
            val line = easeOut(seg(p, 0f, 0.3f))
            val half = markWidth.toPx() * 0.55f * line
            val base = center.y + (markWidth.toPx() / MARK_RATIO) / 2f
            drawLine(Color(0xFF141414), Offset(center.x - half, base), Offset(center.x + half, base), strokeWidth = 2.dp.toPx())
        }
        Box(
            Modifier
                .width(markWidth)
                .aspectRatio(MARK_RATIO)
                .graphicsLayer { translationY = -20.dp.toPx() * seg(t.value, 0.85f, 1f) }
                // Only what has come up over the line is seen
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(logo.mark),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    translationY = size.height * (1f - easeOut(seg(t.value, 0.25f, 0.7f)))
                },
            )
        }
    }
}
