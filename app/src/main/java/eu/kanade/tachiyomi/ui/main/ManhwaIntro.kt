package eu.kanade.tachiyomi.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

private val Wine = Color(0xFF1A0508)
private val DeepWine = Color(0xFF3A0812)
private val Crimson = Color(0xFFFF2D46)

private class Spark(val x: Float, val y: Float, val radius: Float, val speed: Float, val phase: Float)

/**
 * MANHWA LEGENDS opening: the logo comes forward out of the dark with a 3D tilt, a light sweeps over its
 * metal edges, a crimson energy pulse beats behind it while sparks rise, then it opens onto the app.
 * It starts on the same wine colour as the system splash, so there is no black frame in between.
 */
@Composable
fun ManhwaIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val enter = remember { Animatable(0f) }
    val sweep = remember { Animatable(-0.6f) }
    val exit = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { enter.animateTo(1f, tween(1_150, easing = FastOutSlowInEasing)) }
        delay(550)
        sweep.animateTo(1.6f, tween(900, easing = LinearEasing))
        delay(150)
        exit.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        onFinished()
    }
    val pulse by rememberInfiniteTransition(label = "intro").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val time by rememberInfiniteTransition(label = "sparks").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "time",
    )
    val sparks = remember {
        val random = Random(7)
        List(46) { Spark(random.nextFloat(), random.nextFloat(), 1f + random.nextFloat() * 2.6f, 0.15f + random.nextFloat() * 0.5f, random.nextFloat()) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Brush.radialGradient(listOf(DeepWine, Wine, Color(0xFF050507)), radius = 1600f)),
        contentAlignment = Alignment.Center,
    ) {
        // Rising sparks
        Canvas(Modifier.fillMaxSize()) {
            sparks.forEach { spark ->
                val progress = (time * spark.speed + spark.phase) % 1f
                val y = size.height * (spark.y - progress * 0.6f).let { if (it < 0f) it + 1f else it }
                val x = size.width * spark.x + sin((progress + spark.phase) * 6.28f) * 14f
                val twinkle = 0.25f + 0.75f * (0.5f + 0.5f * sin((progress * 4f + spark.phase) * 6.28f))
                drawCircle(Crimson.copy(alpha = 0.55f * twinkle * enter.value), spark.radius * density, Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.35f * twinkle * enter.value), spark.radius * 0.45f * density, Offset(x, y))
            }
        }
        // Energy pulse behind the logo
        Box(
            Modifier
                .size(360.dp)
                .graphicsLayer {
                    val s = 0.8f + 0.25f * pulse * enter.value
                    scaleX = s
                    scaleY = s
                    alpha = enter.value
                }
                .background(
                    Brush.radialGradient(listOf(Crimson.copy(alpha = 0.5f), Crimson.copy(alpha = 0.12f), Color.Transparent)),
                ),
        )
        // The logo, coming forward with a 3D tilt and a light sweeping over it
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer {
                    val p = enter.value
                    val s = (0.62f + 0.38f * p) * (1f + 0.12f * exit.value)
                    scaleX = s
                    scaleY = s
                    rotationY = (1f - p) * -38f
                    rotationX = (1f - p) * 16f
                    cameraDistance = 14f * density
                    alpha = p
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val x = size.width * sweep.value
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent),
                            start = Offset(x - size.width * 0.25f, 0f),
                            end = Offset(x + size.width * 0.25f, size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
    }
}
