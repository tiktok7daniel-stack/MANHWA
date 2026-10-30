package eu.kanade.tachiyomi.ui.reader

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANHWA automatic colours: the best tuning for a page, worked out from the page itself. A washed-out scan
 * (its darkest and lightest parts far from black and white) gets its contrast stretched; a dark page is lifted a
 * little; a dull coloured page gets more colour, while a black and white page is left grey. A page that is fine
 * as it is gets almost nothing. Pure Kotlin: tested without a phone.
 */
object AutoColors {

    /** The sharpness given with the automatic colours (Android 13 and later). */
    const val SHARPNESS = 15

    /** The tuning for a page whose pixels (ARGB, a small copy of it is plenty) are [argb]. */
    fun tuneFor(argb: IntArray): PageTune {
        if (argb.isEmpty()) return NEUTRAL
        val lum = IntArray(argb.size)
        var satSum = 0.0
        var lumSum = 0L
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val l = (r * 299 + g * 587 + b * 114) / 1000
            lum[i] = l
            lumSum += l
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            satSum += if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
        }
        lum.sort()
        val low = lum[(lum.size * 0.02).toInt().coerceIn(0, lum.size - 1)]
        val high = lum[(lum.size * 0.98).toInt().coerceIn(0, lum.size - 1)]
        val mean = lumSum.toDouble() / lum.size
        val sat = satSum / argb.size
        // Contrast: stretch what the page uses towards the full range, never more than a third
        val range = (high - low).coerceAtLeast(1)
        val contrast = if (range < FULL_RANGE) ((FULL_RANGE.toFloat() / range - 1f) * 100f).roundToInt().coerceIn(0, MAX_CONTRAST) else 0
        // Brightness: a dark page is lifted, a glaring one barely lowered
        val brightness = ((MID - mean) * 0.15).roundToInt().coerceIn(-6, MAX_LIFT)
        // Colour: a dull coloured page gets more; a grey page stays grey
        val saturation = if (sat in GREY..DULL) ((DULL + 0.1 - sat) * 100).roundToInt().coerceIn(0, MAX_SATURATION) else 0
        return PageTune(brightness, contrast, saturation, 0)
    }

    /** Halfway from [from] to [to], so the colours do not jump from one page to the next. */
    fun ease(from: PageTune?, to: PageTune): PageTune {
        if (from == null) return to
        return PageTune(
            (from.brightness + to.brightness) / 2,
            (from.contrast + to.contrast) / 2,
            (from.saturation + to.saturation) / 2,
            (from.warmth + to.warmth) / 2,
        )
    }

    private val NEUTRAL = PageTune(0, 0, 0, 0)
    private const val FULL_RANGE = 225
    private const val MAX_CONTRAST = 30
    private const val MID = 128.0
    private const val MAX_LIFT = 14
    private const val GREY = 0.06
    private const val DULL = 0.28
    private const val MAX_SATURATION = 28
}
