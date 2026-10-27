package eu.kanade.presentation.manga.components

import eu.kanade.presentation.components.swing
import eu.kanade.presentation.components.loop
import androidx.compose.runtime.derivedStateOf
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
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
enum class SceneFamily { BLUE, PURPLE, TEAL, GREEN, WARM, MONO }

/**
 * MANHWA: a scene a work's page and its music player stand on, in a family of cover colours: the reader's own
 * pictures built in here, and the free ones fetched by tools/fetch_scenes.py ([GeneratedScenes]). Works of the same
 * colour get different scenes of their family, so pages don't all look the same.
 */
data class BackdropScene(val name: String, @DrawableRes val res: Int, val family: SceneFamily) {

    companion object {
        private val builtIn = listOf(
            BackdropScene("COSMIC", R.drawable.music_bg_cosmic, SceneFamily.BLUE),
            BackdropScene("METEORS", R.drawable.music_bg_meteors, SceneFamily.BLUE),
            BackdropScene("BLUE_GATE", R.drawable.music_bg_bluegate, SceneFamily.BLUE),
            BackdropScene("BLUE_MOON", R.drawable.music_bg_bluemoon, SceneFamily.BLUE),
            BackdropScene("STORM", R.drawable.music_bg_storm, SceneFamily.PURPLE),
            BackdropScene("SAVAGE", R.drawable.music_bg_savage, SceneFamily.PURPLE),
            BackdropScene("VIOLET_MOON", R.drawable.music_bg_violetmoon, SceneFamily.PURPLE),
            BackdropScene("TEAL_MOON", R.drawable.music_bg_tealmoon, SceneFamily.TEAL),
            BackdropScene("TEMPLE", R.drawable.music_bg_temple, SceneFamily.TEAL),
            BackdropScene("ABYSS", R.drawable.music_bg_abyss, SceneFamily.TEAL),
            BackdropScene("PORTAL", R.drawable.music_bg_portal, SceneFamily.TEAL),
            BackdropScene("MIRRORS", R.drawable.music_bg_mirrors, SceneFamily.GREEN),
            BackdropScene("LAB", R.drawable.music_bg_lab, SceneFamily.GREEN),
            BackdropScene("GATE", R.drawable.music_bg_gate, SceneFamily.MONO),
            BackdropScene("ECLIPSE", R.drawable.music_bg_eclipse, SceneFamily.MONO),
            BackdropScene("RING", R.drawable.music_bg_ring, SceneFamily.MONO),
        )

        val entries: List<BackdropScene> by lazy { builtIn + GeneratedScenes.all }

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
                else -> SceneFamily.WARM
            }
        }

        /** The scene of a cover colour; [seed] (the work's id) picks among the scenes of that family. */
        fun forColor(color: Color?, seed: Long = 0L): BackdropScene {
            // Reds, oranges and yellows without warm scenes of their own stand on the dark ones, washed with their colour
            val family = entries.filter { it.family == familyOf(color) }.ifEmpty { entries.filter { it.family == SceneFamily.MONO } }
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
    /** A living effect over the scene, faint, in [effectColor] (the cover's when null), pulsing with [beat]. */
    effect: List<WorkEffect> = emptyList(),
    effectColor: Color? = null,
    beat: () -> Float = { 0f },
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalCoverPalette.current
    val deep = palette?.deep ?: Color(0xFF07070A)
    val accent = palette?.primary ?: Color(0xFFFF3B4E)
    // MANHWA battery: on the app's one decoration clock, read while drawing
    val decor = eu.kanade.presentation.components.LocalDecorTime.current
    val breathe by remember(decor) { derivedStateOf { 1.04f + 0.08f * decor.swing(36f) } }
    val rise by remember(decor) { derivedStateOf { decor.loop(16f) } }
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
        EffectLayers(effect, effectColor ?: accent, Modifier.fillMaxSize(), strength = 0.5f, beat = beat)
        content()
    }
}
