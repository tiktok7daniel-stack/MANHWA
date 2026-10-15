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

    private const val SAMPLE = 48
    private const val CACHE_ENTRIES = 300
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
