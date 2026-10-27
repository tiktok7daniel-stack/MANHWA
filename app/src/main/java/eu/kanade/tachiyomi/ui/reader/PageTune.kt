package eu.kanade.tachiyomi.ui.reader

import android.graphics.ColorMatrix

/**
 * MANHWA: how the pages' colours are tuned, each from -100 to 100 with 0 leaving the page as it is: brightness,
 * contrast, saturation and warmth (red up and blue down, or the other way).
 */
data class PageTune(val brightness: Int, val contrast: Int, val saturation: Int, val warmth: Int) {
    val isNeutral: Boolean get() = brightness == 0 && contrast == 0 && saturation == 0 && warmth == 0

    fun matrix(): ColorMatrix = ColorMatrix(values())

    /** The 4x5 colour matrix, row by row (red, green, blue, alpha). */
    fun values(): FloatArray {
        val s = 1f + saturation.coerceIn(-100, 100) / 100f
        // Luminance weights, as ColorMatrix.setSaturation uses them
        val lr = 0.213f * (1 - s)
        val lg = 0.715f * (1 - s)
        val lb = 0.072f * (1 - s)
        val c = 1f + contrast.coerceIn(-100, 100) / 100f
        val offset = 128f * (1 - c) + brightness.coerceIn(-100, 100) * 1.28f
        val warm = warmth.coerceIn(-100, 100) / 400f
        val r = c * (1 + warm)
        val b = c * (1 - warm)
        return floatArrayOf(
            r * (lr + s), r * lg, r * lb, 0f, offset,
            c * lr, c * (lg + s), c * lb, 0f, offset,
            b * lr, b * lg, b * (lb + s), 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    companion object {
        /** A gentle lift for washed-out, compressed scans. */
        val AUTO = PageTune(brightness = 4, contrast = 14, saturation = 18, warmth = 0)

        /** AMOLED reading: whites a little lower, so a white page does not glare in the dark. */
        val AMOLED = PageTune(brightness = -14, contrast = 0, saturation = 0, warmth = 8)
    }
}
