package eu.kanade.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover

/**
 * MANHWA ambient light: the strongest colour of a manhwa's cover, so the screens around it glow in its colour.
 * Read once from a small copy of the cover (off the main thread) and kept per manhwa.
 */
object CoverGlow {

    private val cache = LruCache<Long, Int>(CACHE_ENTRIES)

    suspend fun colorOf(context: Context, manga: Manga): Color? {
        cache.get(manga.id)?.let { return Color(it) }
        val argb = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(manga.asMangaCover())
                    .size(Size(SAMPLE, SAMPLE))
                    .allowHardware(false)
                    .build()
                val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources) as? BitmapDrawable
                drawable?.bitmap?.let(::vivid)
            }.getOrNull()
        } ?: return null
        cache.put(manga.id, argb)
        return Color(argb)
    }

    /** The most common colourful pixel colour (grey, near-black and near-white pixels don't count). */
    private fun vivid(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val counts = HashMap<Int, Int>()
        val hsv = FloatArray(3)
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.28f || hsv[2] < 0.22f || hsv[2] > 0.97f) continue
            // 4 bits per channel: close colours fall in the same bucket
            val bucket = (c shr 4 and 0x0F0F0F)
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return null
        val r = (best shr 16 and 0x0F) * 17
        val g = (best shr 8 and 0x0F) * 17
        val b = (best and 0x0F) * 17
        // Brighter and stronger, so the glow reads on the dark glass
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb(r, g, b), hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceAtLeast(0.75f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    private val palettes = LruCache<Long, CoverPalette>(CACHE_ENTRIES)

    /** The cover's own colours: its strongest, a second hue apart from it, and a deep shade for backgrounds. */
    suspend fun paletteOf(context: Context, manga: Manga): CoverPalette? = paletteOf(context, manga.id, manga.asMangaCover())

    /** The palette of any cover Coil can load ([data]), kept under [key] (a novel's uses a negative one). */
    suspend fun paletteOf(context: Context, key: Long, data: Any): CoverPalette? {
        palettes.get(key)?.let { return it }
        val palette = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(data)
                    .size(Size(SAMPLE, SAMPLE))
                    .allowHardware(false)
                    .build()
                val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources) as? BitmapDrawable
                drawable?.bitmap?.let(::palette)
            }.getOrNull()
        } ?: return null
        palettes.put(key, palette)
        return palette
    }

    private fun palette(bitmap: Bitmap): CoverPalette? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        // Hue in 24 slices of 15 degrees, weighted by how colourful each pixel is
        val weight = FloatArray(24)
        val sat = FloatArray(24)
        var dark = 0f
        var darkHue = 0f
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[2] < 0.35f) {
                dark += 1f
                darkHue += hsv[0]
            }
            if (hsv[1] < 0.25f || hsv[2] < 0.2f || hsv[2] > 0.98f) continue
            val slice = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            val w8 = hsv[1] * hsv[2]
            weight[slice] += w8
            sat[slice] += hsv[1] * w8
        }
        val first = weight.indices.maxByOrNull { weight[it] }?.takeIf { weight[it] > 0f } ?: return null
        // The second hue must stand apart (at least 45 degrees), or it is a shade of the first
        val second = weight.indices
            .filter { val d = kotlin.math.abs(it - first).let { x -> minOf(x, 24 - x) }; d >= 3 }
            .maxByOrNull { weight[it] }
            ?.takeIf { weight[it] > weight[first] * 0.12f }
        fun tone(slice: Int, s: Float, v: Float) = Color(
            android.graphics.Color.HSVToColor(floatArrayOf(slice * 15f + 7.5f, s.coerceIn(0f, 1f), v)),
        )
        val firstSat = (sat[first] / weight[first]).coerceAtLeast(0.55f)
        val primary = tone(first, firstSat * 1.1f, 0.92f)
        val secondary = second?.let { tone(it, (sat[it] / weight[it]).coerceAtLeast(0.5f), 0.88f) }
            ?: tone((first + 2) % 24, firstSat * 0.8f, 0.95f)
        val deep = tone(first, 0.55f, 0.13f)
        return CoverPalette(primary, secondary, deep)
    }

    private const val SAMPLE = 48
    private const val CACHE_ENTRIES = 300
}

/** MANHWA: a cover's colours (see [CoverGlow.paletteOf]). */
data class CoverPalette(val primary: Color, val secondary: Color, val deep: Color)

/** The palette of [manga]'s cover, or null until it is read (or when the cover has no colour). */
@Composable
fun rememberCoverPalette(manga: Manga?): CoverPalette? {
    val context = LocalContext.current
    var palette by remember(manga?.id) { mutableStateOf<CoverPalette?>(null) }
    LaunchedEffect(manga?.id, manga?.thumbnailUrl) {
        palette = manga?.let { CoverGlow.paletteOf(context, it) }
    }
    return palette
}

/** The palette of the cover at [url], kept under [key]; null until it is read. */
@Composable
fun rememberCoverPalette(key: Long, url: String?): CoverPalette? {
    val context = LocalContext.current
    var palette by remember(key) { mutableStateOf<CoverPalette?>(null) }
    LaunchedEffect(key, url) {
        palette = url?.takeIf { it.isNotBlank() }?.let { CoverGlow.paletteOf(context, key, it) }
    }
    return palette
}

/** The cover colour of [manga], or null until it is read (or when the cover has none). */
@Composable
fun rememberCoverColor(manga: Manga?): Color? {
    val context = LocalContext.current
    var color by remember(manga?.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(manga?.id, manga?.thumbnailUrl) {
        color = manga?.let { CoverGlow.colorOf(context, it) }
    }
    return color
}

/** [content] with a soft light of [color] behind it, from the top. Nothing extra when [color] is null. */
@Composable
fun AmbientGlow(color: Color?, modifier: Modifier = Modifier, strength: Float = 0.32f, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.drawBehind {
            if (color == null) return@drawBehind
            drawRect(
                Brush.radialGradient(
                    listOf(color.copy(alpha = strength), color.copy(alpha = strength * 0.35f), Color.Transparent),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = size.width * 0.95f,
                ),
            )
        },
        content = content,
    )
}
