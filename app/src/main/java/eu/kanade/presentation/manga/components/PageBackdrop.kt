package eu.kanade.presentation.manga.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import kotlin.random.Random

/** MANHWA: the scenes a work's page and its music player stand on, each for a family of cover colours. */
enum class BackdropScene(@DrawableRes val res: Int) {
    COSMIC(R.drawable.music_bg_cosmic),
    STORM(R.drawable.music_bg_storm),
    SAVAGE(R.drawable.music_bg_savage),
    MIRRORS(R.drawable.music_bg_mirrors),
    GATE(R.drawable.music_bg_gate),
    ;

    companion object {
        /** The scene for a cover colour: blue the cosmic temple, purple the storm, pink the savage, green the mirrors, the rest the gate. */
        fun forColor(color: Color?): BackdropScene {
            color ?: return GATE
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(android.graphics.Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()), hsv)
            if (hsv[1] < 0.2f) return GATE
            return when (hsv[0]) {
                in 170f..250f -> COSMIC
                in 250f..295f -> STORM
                in 295f..335f -> SAVAGE
                in 70f..170f -> MIRRORS
                else -> GATE
            }
        }

        fun of(name: String?): BackdropScene? = entries.firstOrNull { it.name == name }
    }
}

/**
 * MANHWA: a work's page stands on a scene in its colour: the picture, dark enough for the text, washed with the cover's
 * deep shade, with dust drifting up through it. It stays still while the page scrolls over it.
 */
@Composable
fun PageBackdrop(scene: BackdropScene, modifier: Modifier = Modifier, dim: Float = 0.62f, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalCoverPalette.current
    val deep = palette?.deep ?: Color(0xFF07070A)
    val accent = palette?.primary ?: Color(0xFFFF3B4E)
    val motion = rememberInfiniteTransition(label = "backdrop")
    val breathe by motion.animateFloat(1.04f, 1.12f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "breathe")
    val rise by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "rise")
    val dust = remember { List(40) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Box(modifier.fillMaxSize().background(Color(0xFF040406))) {
        Crossfade(scene, animationSpec = tween(900), label = "scene") { s ->
            Image(
                painter = painterResource(s.res),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = breathe
                        scaleY = breathe
                        alpha = 1f - dim
                    },
            )
        }
        // The cover's deep shade over it, and darker to the bottom where the chapters are
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to deep.copy(alpha = 0.35f),
                        0.5f to deep.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )
        Canvas(Modifier.fillMaxSize()) {
            dust.forEach { (x, y, s) ->
                val yy = ((y - rise * (0.4f + s)) % 1f + 1f) % 1f
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.55f * s), Color.Transparent), Offset(x * size.width, yy * size.height), 6f + 10f * s),
                    radius = 6f + 10f * s,
                    center = Offset(x * size.width, yy * size.height),
                )
            }
        }
        content()
    }
}
