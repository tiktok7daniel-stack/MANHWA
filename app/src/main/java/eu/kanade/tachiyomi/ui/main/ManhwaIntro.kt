package eu.kanade.tachiyomi.ui.main

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val Obsidian = Color(0xFF050505)

/** The window and system splash colour (@color/splash): the first frame matches it exactly. */
private val SplashBlack = Color(0xFF060204)
private val Wine = Color(0xFF1A0508)
private val DeepWine = Color(0xFF2E0610)
private val Crimson = Color(0xFFFF2D46)
private val HotCrimson = Color(0xFFFF5A6E)
private val Silver = Color(0xFFE9E4EA)

/** Where the star above the M sits inside the logo, as a fraction of its box. */
private const val STAR_X = 0.5f
private const val STAR_Y = 0.135f

/** Layers of the extruded depth behind the logo's face. */
private const val DEPTH_LAYERS = 12

private const val START_TIMEOUT_MS = 3_000L

/** Room kept around the logo inside its layer, for the depth and the bloom. */
private const val INSET = 0.04f

private class Mote(val angle: Float, val z: Float, val speed: Float, val size: Float, val warm: Boolean)

/**
 * MANHWA LEGENDS 3D opening, about two seconds:
 * 1. Dimension rift: deep obsidian void, a crimson rift line opens and light motes fly past the camera.
 * 2. The logo rushes out of the depth (0.4 to 1.0 with a spring), turning on a 3D axis; it is drawn as a real
 *    extruded solid (stacked depth layers darkening to wine, a silver bevel rim, then the face).
 * 3. The star above the M ignites with a four point flare and an energy wave that lights the letters, a shock
 *    ring leaves the logo as it lands.
 * 4. A crimson specular sweep crosses the letters, then the camera zooms through the logo into the app.
 * Everything is drawn with Canvas on cached layers (the vector is rasterised once and redrawn tinted), so it
 * stays at the screen's full frame rate without allocating per frame.
 */
@Composable
fun ManhwaIntro(started: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val currentStarted by rememberUpdatedState(started)
    val finish by rememberUpdatedState(onFinished)
    // Safety: if the system splash never reports leaving, the opening does not hold the app
    LaunchedEffect(Unit) {
        delay(START_TIMEOUT_MS)
        if (!currentStarted) finish()
    }
    val rift = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    val turn = remember { Animatable(0f) }
    val ignite = remember { Animatable(0f) }
    val shock = remember { Animatable(0f) }
    val sweep = remember { Animatable(-0.5f) }
    val exit = remember { Animatable(0f) }
    // A tap skips the opening (the app is already ready underneath)
    var skipped by remember { mutableStateOf(false) }

    LaunchedEffect(started) {
        // The clock starts once the system splash is gone, so the whole opening is seen
        if (!started) return@LaunchedEffect
        launch { rift.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        delay(160)
        launch { enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
        launch { turn.animateTo(1f, tween(900, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
        delay(520)
        launch { ignite.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        launch { shock.animateTo(1f, tween(720, easing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f))) }
        delay(260)
        sweep.animateTo(1.5f, tween(620, easing = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)))
        delay(80)
        exit.animateTo(1f, tween(420, easing = CubicBezierEasing(0.55f, 0f, 0.8f, 0.4f)))
        if (!skipped) {
            skipped = true
            onFinished()
        }
    }

    // One clock for the motes and the pulse, read only in the draw phase
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (isActive) withFrameNanos { nanos = it - start }
    }

    val motes = remember {
        val random = Random(11)
        List(70) {
            Mote(
                angle = random.nextFloat() * 2f * PI.toFloat(),
                z = random.nextFloat(),
                speed = 0.18f + random.nextFloat() * 0.32f,
                size = 0.6f + random.nextFloat() * 1.6f,
                warm = random.nextFloat() < 0.7f,
            )
        }
    }

    val logo = rememberVectorPainter(ImageVector.vectorResource(R.drawable.manhwa_logo))
    val depthFilters = remember {
        List(DEPTH_LAYERS) { i ->
            val f = i / (DEPTH_LAYERS - 1f)
            ColorFilter.tint(lerp(Color(0xFF7A1020), Color(0xFF14030A), f))
        }
    }
    val rimFilter = remember { ColorFilter.tint(Silver) }
    val glowFilter = remember { ColorFilter.tint(Crimson) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!skipped) {
                        skipped = true
                        finish()
                    }
                }
            }
            .graphicsLayer { alpha = 1f - exit.value * exit.value },
        contentAlignment = Alignment.Center,
    ) {
        val logoWidth = min(maxWidth * 0.88f, 460.dp)
        val logoHeight = logoWidth * (133f / 275f)

        // The void: obsidian, a wine depth behind the logo, the rift and the motes flying past
        Canvas(Modifier.fillMaxSize()) {
            val t = nanos / 1_000_000_000f
            val center = Offset(size.width / 2f, size.height / 2f)
            val opening = 1f - rift.value
            drawRect(lerp(Obsidian, SplashBlack, opening))
            drawRect(
                Brush.radialGradient(
                    listOf(DeepWine.copy(alpha = 0.95f * rift.value), Wine.copy(alpha = 0.5f * rift.value), Color.Transparent),
                    center = center,
                    radius = size.maxDimension * 0.62f,
                ),
            )
            drawRift(center, rift.value, enter.value)
            drawMotes(motes, center, t, rift.value * (1f - exit.value * 0.5f), exit.value)
        }

        // The extruded logo
        Canvas(
            Modifier
                .size(logoWidth, logoHeight)
                .graphicsLayer {
                    val e = enter.value
                    val s = (0.4f + 0.6f * e) * (1f + 1.6f * exit.value * exit.value)
                    scaleX = s
                    scaleY = s
                    rotationY = (1f - turn.value) * -42f
                    rotationX = (1f - turn.value) * 20f
                    translationY = -(18.dp.toPx()) * exit.value
                    cameraDistance = 16f * density
                    alpha = (e * 1.6f).coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            val t = nanos / 1_000_000_000f
            val depth = 0.55.dp.toPx()
            // Depth goes down and away from the light, and follows the turn so the solid reads in 3D
            val dir = Offset(0.35f + (1f - turn.value) * 0.9f, 1f)
            // The logo is drawn a little inside the layer so the depth never gets cut at the edges
            val inner = Size(size.width * (1f - 2f * INSET), size.height * (1f - 2f * INSET))
            translate(size.width * INSET, size.height * INSET) {
                with(logo) {
                    // Crimson bloom under the solid once the star has lit it: the shape, spread around
                    if (ignite.value > 0f) {
                        val spread = 2.5.dp.toPx()
                        val bloom = 0.16f * ignite.value * (1f + 0.25f * sin(t * 6f))
                        for (k in 0 until 8) {
                            val a = k * PI.toFloat() / 4f
                            translate(cos(a) * spread, sin(a) * spread) { draw(inner, alpha = bloom, colorFilter = glowFilter) }
                        }
                    }
                    for (i in DEPTH_LAYERS - 1 downTo 0) {
                        translate(dir.x * depth * (i + 1), dir.y * depth * (i + 1)) {
                            draw(inner, colorFilter = depthFilters[i])
                        }
                    }
                    // Silver bevel rim catching the light on the top left edges
                    translate(-0.9.dp.toPx(), -0.9.dp.toPx()) { draw(inner, alpha = 0.85f, colorFilter = rimFilter) }
                    // The face, with its own silver to crimson gradient
                    draw(inner)
                }
            }
            drawEnergy(ignite.value, t)
            drawSweep(sweep.value)
        }

        // The star ignition and the shock ring, over the logo, following its scale
        Canvas(
            Modifier
                .size(logoWidth, logoHeight)
                .graphicsLayer {
                    val s = (0.4f + 0.6f * enter.value) * (1f + 1.6f * exit.value * exit.value)
                    scaleX = s
                    scaleY = s
                    translationY = -(18.dp.toPx()) * exit.value
                    alpha = enter.value.coerceIn(0f, 1f)
                },
        ) {
            val t = nanos / 1_000_000_000f
            drawShock(shock.value)
            drawStar(ignite.value, t)
        }
    }
}

private fun DrawScope.starAt() = Offset(
    size.width * (INSET + (1f - 2f * INSET) * STAR_X),
    size.height * (INSET + (1f - 2f * INSET) * STAR_Y),
)

private fun DrawScope.drawRift(center: Offset, open: Float, logoIn: Float) {
    if (open >= 1f && logoIn >= 1f) return
    val fade = (1f - logoIn).coerceIn(0f, 1f)
    val width = size.width * (0.15f + 0.85f * open)
    val glow = (sin(open * PI.toFloat()) * 0.8f + 0.2f) * fade
    if (glow <= 0.01f) return
    drawRect(
        Brush.horizontalGradient(
            listOf(Color.Transparent, Crimson.copy(alpha = glow), Color.White.copy(alpha = glow), Crimson.copy(alpha = glow), Color.Transparent),
            startX = center.x - width / 2f,
            endX = center.x + width / 2f,
        ),
        topLeft = Offset(center.x - width / 2f, center.y - 1.5f),
        size = Size(width, 3f),
    )
    drawOval(
        Brush.radialGradient(listOf(Crimson.copy(alpha = 0.45f * glow), Color.Transparent), center = center, radius = width / 2f),
        topLeft = Offset(center.x - width / 2f, center.y - width * 0.08f),
        size = Size(width, width * 0.16f),
    )
}

/** Light motes coming from the depth of the rift towards the camera. */
private fun DrawScope.drawMotes(motes: List<Mote>, center: Offset, t: Float, visible: Float, rush: Float) {
    if (visible <= 0f) return
    val reach = size.maxDimension * 0.75f
    motes.forEach { m ->
        // z goes from far (1) to the camera (0) and loops; the exit makes everything rush past
        val z = 1f - ((m.z + t * m.speed * (1f + rush * 6f)) % 1f)
        val perspective = 1f / (0.18f + z * 1.4f)
        val r = reach * (1f - z) * 0.9f
        val p = Offset(center.x + cos(m.angle) * r, center.y + sin(m.angle) * r * 0.75f)
        val near = (1f - z)
        val alpha = (near * 1.4f).coerceIn(0f, 1f) * visible * 0.8f
        val radius = m.size * density * perspective * 0.35f
        val color = if (m.warm) HotCrimson else Silver
        drawCircle(color.copy(alpha = alpha * 0.35f), radius * 2.6f, p)
        drawCircle(color.copy(alpha = alpha), radius, p)
    }
}

/** The energy wave from the star lighting the letters, kept inside them. */
private fun DrawScope.drawEnergy(ignite: Float, t: Float) {
    if (ignite <= 0f) return
    val star = starAt()
    val radius = size.width * (0.08f + 0.9f * ignite)
    val pulse = 0.85f + 0.15f * sin(t * 7f)
    drawRect(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.55f * pulse), HotCrimson.copy(alpha = 0.35f * pulse), Crimson.copy(alpha = 0.12f), Color.Transparent),
            center = star,
            radius = radius,
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/** Crimson specular band crossing the letters, only where the logo is. */
private fun DrawScope.drawSweep(x: Float) {
    if (x <= -0.5f || x >= 1.5f) return
    val cx = size.width * x
    val band = size.width * 0.18f
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Crimson.copy(alpha = 0.55f), Color.White.copy(alpha = 0.95f), Crimson.copy(alpha = 0.55f), Color.Transparent),
            start = Offset(cx - band, 0f),
            end = Offset(cx + band, size.height),
        ),
        blendMode = BlendMode.SrcAtop,
    )
}

/** The ring of light that leaves the logo as it lands. */
private fun DrawScope.drawShock(p: Float) {
    if (p <= 0f || p >= 1f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.width * (0.2f + 0.9f * p)
    val alpha = (1f - p) * 0.7f
    drawOval(
        color = Crimson.copy(alpha = alpha),
        topLeft = Offset(center.x - radius, center.y - radius * 0.42f),
        size = Size(radius * 2f, radius * 0.84f),
        style = Stroke(width = (1f + 5f * (1f - p)) * density),
    )
    drawOval(
        color = Color.White.copy(alpha = alpha * 0.6f),
        topLeft = Offset(center.x - radius * 0.97f, center.y - radius * 0.40f),
        size = Size(radius * 1.94f, radius * 0.80f),
        style = Stroke(width = 1f * density),
    )
}

/** The star above the M: a hot core, a halo and a slowly turning four point flare. */
private fun DrawScope.drawStar(ignite: Float, t: Float) {
    if (ignite <= 0f) return
    val c = starAt()
    val beat = 0.8f + 0.2f * sin(t * 9f)
    val power = ignite * beat
    val halo = size.width * 0.16f * (0.6f + 0.4f * power)
    drawCircle(Brush.radialGradient(listOf(Crimson.copy(alpha = 0.55f * power), Color.Transparent), c, halo), halo, c)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * power), HotCrimson.copy(alpha = 0.5f * power), Color.Transparent), c, halo * 0.35f), halo * 0.35f, c)
    val long = size.width * 0.32f * power
    val short = size.width * 0.12f * power
    rotate(degrees = t * 18f, pivot = c) {
        flare(c, long, 2.2f * density, power)
        flare(c, short, 1.6f * density, power * 0.7f, vertical = true)
    }
    rotate(degrees = 45f + t * 18f, pivot = c) {
        flare(c, short * 0.8f, 1.2f * density, power * 0.5f)
        flare(c, short * 0.8f, 1.2f * density, power * 0.5f, vertical = true)
    }
}

private fun DrawScope.flare(c: Offset, length: Float, thickness: Float, alpha: Float, vertical: Boolean = false) {
    val colors = listOf(Color.Transparent, HotCrimson.copy(alpha = 0.6f * alpha), Color.White.copy(alpha = alpha), HotCrimson.copy(alpha = 0.6f * alpha), Color.Transparent)
    if (vertical) {
        drawRect(
            Brush.verticalGradient(colors, startY = c.y - length, endY = c.y + length),
            topLeft = Offset(c.x - thickness / 2f, c.y - length),
            size = Size(thickness, length * 2f),
        )
    } else {
        drawRect(
            Brush.horizontalGradient(colors, startX = c.x - length, endX = c.x + length),
            topLeft = Offset(c.x - length, c.y - thickness / 2f),
            size = Size(length * 2f, thickness),
        )
    }
}
