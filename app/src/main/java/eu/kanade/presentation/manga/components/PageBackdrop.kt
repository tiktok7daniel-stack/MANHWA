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

/** MANHWA: the families of cover colours a scene can go with. */
enum class SceneFamily { BLUE, PURPLE, TEAL, GREEN, MONO }

/**
 * MANHWA: the scenes a work's page and its music player stand on (the reader's own pictures), each in a family of
 * cover colours. Works of the same colour get different scenes of that family, so pages don't all look the same.
 */
enum class BackdropScene(@DrawableRes val res: Int, val family: SceneFamily) {
    COSMIC(R.drawable.music_bg_cosmic, SceneFamily.BLUE),
    METEORS(R.drawable.music_bg_meteors, SceneFamily.BLUE),
    BLUE_GATE(R.drawable.music_bg_bluegate, SceneFamily.BLUE),
    BLUE_MOON(R.drawable.music_bg_bluemoon, SceneFamily.BLUE),
    STORM(R.drawable.music_bg_storm, SceneFamily.PURPLE),
    SAVAGE(R.drawable.music_bg_savage, SceneFamily.PURPLE),
    VIOLET_MOON(R.drawable.music_bg_violetmoon, SceneFamily.PURPLE),
    TEAL_MOON(R.drawable.music_bg_tealmoon, SceneFamily.TEAL),
    TEMPLE(R.drawable.music_bg_temple, SceneFamily.TEAL),
    ABYSS(R.drawable.music_bg_abyss, SceneFamily.TEAL),
    PORTAL(R.drawable.music_bg_portal, SceneFamily.TEAL),
    MIRRORS(R.drawable.music_bg_mirrors, SceneFamily.GREEN),
    LAB(R.drawable.music_bg_lab, SceneFamily.GREEN),
    GATE(R.drawable.music_bg_gate, SceneFamily.MONO),
    ECLIPSE(R.drawable.music_bg_eclipse, SceneFamily.MONO),
    RING(R.drawable.music_bg_ring, SceneFamily.MONO),
    ;

    companion object {
        fun familyOf(color: Color?): SceneFamily {
            color ?: return SceneFamily.MONO
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(android.graphics.Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()), hsv)
            if (hsv[1] < 0.2f) return SceneFamily.MONO
            return when (hsv[0]) {
                in 200f..250f -> SceneFamily.BLUE
                in 250f..335f -> SceneFamily.PURPLE
                in 160f..200f -> SceneFamily.TEAL
                in 70f..160f -> SceneFamily.GREEN
                // Reds, oranges and yellows stand on the dark scenes, washed with their own colour
                else -> SceneFamily.MONO
            }
        }

        /** The scene of a cover colour; [seed] (the work's id) picks among the scenes of that family. */
        fun forColor(color: Color?, seed: Long = 0L): BackdropScene {
            val family = entries.filter { it.family == familyOf(color) }
            return family[((seed % family.size + family.size) % family.size).toInt()]
        }

        fun of(name: String?): BackdropScene? = entries.firstOrNull { it.name == name }
    }
}

/**
 * MANHWA: a work's page stands on a scene in its colour: the picture, dark enough for the text, washed with the cover's
 * deep shade, with dust drifting up through it. It stays still while the page scrolls over it.
 */
@Composable
fun PageBackdrop(
    scene: BackdropScene,
    modifier: Modifier = Modifier,
    dim: Float = 0.62f,
    /** A picture of the reader's own, shown instead of [scene]. */
    custom: java.io.File? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalCoverPalette.current
    val deep = palette?.deep ?: Color(0xFF07070A)
    val accent = palette?.primary ?: Color(0xFFFF3B4E)
    val motion = rememberInfiniteTransition(label = "backdrop")
    val breathe by motion.animateFloat(1.04f, 1.12f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "breathe")
    val rise by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "rise")
    val dust = remember { List(40) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Box(modifier.fillMaxSize().background(Color(0xFF040406))) {
        Crossfade(custom ?: scene, animationSpec = tween(900), label = "scene") { s ->
            val picture = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = breathe
                    scaleY = breathe
                    alpha = 1f - dim
                }
            if (s is java.io.File) {
                coil3.compose.AsyncImage(model = s, contentDescription = null, contentScale = ContentScale.Crop, modifier = picture)
                return@Crossfade
            }
            Image(
                painter = painterResource((s as BackdropScene).res),
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
