package eu.kanade.translation.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * MANHWA: the colour translated letters are set in on a cleaned bubble of any colour. Not just black or white by
 * brightness: a coloured bubble gets a deep or a pale shade of its own hue (maroon on pink, navy on light blue,
 * cream on red), as a letterer would pick, as long as it reads at least as clearly as plain black or white would;
 * otherwise plain black or white. Pure ARGB ints, so it is tested without a phone.
 */
object BubbleInk {

    /** WCAG contrast that body text should reach. */
    private const val READABLE = 4.5

    fun on(bg: Int): Int {
        val dark = 0xFF111111.toInt()
        val light = 0xFFFFFFFF.toInt()
        val plain = if (contrast(dark, bg) >= contrast(light, bg)) dark else light
        val hsv = hsv(bg)
        // Greys and near-greys keep plain ink
        if (hsv[1] < 0.22f || hsv[2] < 0.12f) return plain
        val tinted = if (plain == dark) {
            fromHsv(hsv[0], min(1f, hsv[1] + 0.25f), 0.2f)
        } else {
            fromHsv(hsv[0], hsv[1] * 0.18f, 1f)
        }
        val c = contrast(tinted, bg)
        return if (c >= READABLE || c >= contrast(plain, bg) * 0.9) tinted else plain
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun hsv(c: Int): FloatArray {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    private fun fromHsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun to(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (to(r) shl 16) or (to(g) shl 8) or to(b)
    }
}
