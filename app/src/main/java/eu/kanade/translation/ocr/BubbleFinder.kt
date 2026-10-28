package eu.kanade.translation.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MANHWA bubble finder: the light, closed shape a line of text sits in (a speech bubble), found by flooding the
 * light pixels around the text. Two lines in two different bubbles are never joined into one, even when the
 * bubbles touch. Text on dark panels, on the art, or in a bubble too big for the search area gives "unknown",
 * and nothing is decided from it. Plain Kotlin on ARGB pixels (tested on a drawn page without a phone).
 */
object BubbleFinder {

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Long get() = (right - left).toLong() * (bottom - top)
    }

    /**
     * The bubble around the text box ([bx], [by], [bw], [bh]) inside the [rw]×[rh] region [argb] (box in region
     * pixels), or null when the text is not inside a closed light shape within the region.
     */
    fun bubbleOf(argb: IntArray, rw: Int, rh: Int, bx: Int, by: Int, bw: Int, bh: Int, lineHeight: Int): Rect? {
        if (rw < 4 || rh < 4 || argb.size < rw * rh) return null
        val step = max(1, lineHeight / STEP_DIVISOR)
        val gw = (rw + step - 1) / step
        val gh = (rh + step - 1) / step
        // A cell is light only when every pixel in it is: a thin bubble outline always closes the shape
        val darkest = IntArray(gw * gh) { 255 }
        for (y in 0 until rh) {
            val row = (y / step) * gw
            for (x in 0 until rw) {
                val l = luma(argb[y * rw + x])
                val c = row + x / step
                if (l < darkest[c]) darkest[c] = l
            }
        }
        val light = BooleanArray(gw * gh) { darkest[it] >= LIGHT }
        // Seeds: light cells in the text box (between the letters) and just around it
        val x0 = ((bx - step) / step).coerceIn(0, gw - 1)
        val y0 = ((by - step) / step).coerceIn(0, gh - 1)
        val x1 = ((bx + bw + step) / step).coerceIn(0, gw - 1)
        val y1 = ((by + bh + step) / step).coerceIn(0, gh - 1)
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        var top = 0
        for (gy in y0..y1) for (gx in x0..x1) {
            val i = gy * gw + gx
            if (light[i] && !seen[i]) {
                seen[i] = true
                stack[top++] = i
            }
        }
        if (top == 0) return null
        var minX = gw
        var minY = gh
        var maxX = -1
        var maxY = -1
        var filled = 0
        while (top > 0) {
            val i = stack[--top]
            val gx = i % gw
            val gy = i / gw
            // Reaching the edge of the region: the light area is open (or bigger than we look at)
            if (gx == 0 || gy == 0 || gx == gw - 1 || gy == gh - 1) return null
            filled++
            if (gx < minX) minX = gx
            if (gx > maxX) maxX = gx
            if (gy < minY) minY = gy
            if (gy > maxY) maxY = gy
            for (n in intArrayOf(i - 1, i + 1, i - gw, i + gw)) {
                if (!seen[n] && light[n]) {
                    seen[n] = true
                    stack[top++] = n
                }
            }
        }
        val rect = Rect(
            min(minX * step, bx),
            min(minY * step, by),
            max((maxX + 1) * step, bx + bw),
            max((maxY + 1) * step, by + bh),
        )
        // A bubble is larger than its text; a few light cells between letters are not one
        return rect.takeIf { it.area > bw.toLong() * bh * MIN_GROWTH }
    }

    /**
     * MANHWA: the plain box around the text when it is not a light bubble: a dark caption box, a coloured one or
     * a smooth gradient (brown to black). Cells whose pixels barely differ are joined to neighbours of a close
     * colour, from around the text out to the box's edge; null when that area is open (it runs out of the
     * region: the text sits on the drawing), when it is mostly not flat, or when it is hardly bigger than the text.
     */
    fun boxOf(argb: IntArray, rw: Int, rh: Int, bx: Int, by: Int, bw: Int, bh: Int, lineHeight: Int): Rect? {
        if (rw < 4 || rh < 4 || argb.size < rw * rh) return null
        val step = max(1, lineHeight / STEP_DIVISOR)
        val gw = (rw + step - 1) / step
        val gh = (rh + step - 1) / step
        val n = gw * gh
        val lo = IntArray(n * 3) { 255 }
        val hi = IntArray(n * 3)
        val sum = LongArray(n * 3)
        val count = IntArray(n)
        for (y in 0 until rh) {
            val row = (y / step) * gw
            for (x in 0 until rw) {
                val c = argb[y * rw + x]
                val cell = row + x / step
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val o = cell * 3
                if (r < lo[o]) lo[o] = r
                if (r > hi[o]) hi[o] = r
                if (g < lo[o + 1]) lo[o + 1] = g
                if (g > hi[o + 1]) hi[o + 1] = g
                if (b < lo[o + 2]) lo[o + 2] = b
                if (b > hi[o + 2]) hi[o + 2] = b
                sum[o] += r.toLong()
                sum[o + 1] += g.toLong()
                sum[o + 2] += b.toLong()
                count[cell]++
            }
        }
        val flat = BooleanArray(n) { i ->
            count[i] > 0 && (0 until 3).all { k -> hi[i * 3 + k] - lo[i * 3 + k] <= FLAT_RANGE }
        }
        fun mean(i: Int, k: Int): Int = (sum[i * 3 + k] / count[i].coerceAtLeast(1)).toInt()
        fun close(a: Int, b: Int): Boolean =
            abs(mean(a, 0) - mean(b, 0)) + abs(mean(a, 1) - mean(b, 1)) + abs(mean(a, 2) - mean(b, 2)) <= STEP_DIFF
        // Seeds: flat cells in a ring just outside the text, where the box's own colour shows
        val x0 = ((bx - step * 2) / step).coerceIn(0, gw - 1)
        val y0 = ((by - step * 2) / step).coerceIn(0, gh - 1)
        val x1 = ((bx + bw + step * 2) / step).coerceIn(0, gw - 1)
        val y1 = ((by + bh + step * 2) / step).coerceIn(0, gh - 1)
        val tx0 = (bx / step).coerceIn(0, gw - 1)
        val ty0 = (by / step).coerceIn(0, gh - 1)
        val tx1 = ((bx + bw) / step).coerceIn(0, gw - 1)
        val ty1 = ((by + bh) / step).coerceIn(0, gh - 1)
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        var top = 0
        for (gy in y0..y1) for (gx in x0..x1) {
            if (gx in tx0..tx1 && gy in ty0..ty1) continue
            val i = gy * gw + gx
            if (flat[i] && !seen[i]) {
                seen[i] = true
                stack[top++] = i
            }
        }
        if (top == 0) return null
        var minX = gw
        var minY = gh
        var maxX = -1
        var maxY = -1
        var filled = 0
        while (top > 0) {
            val i = stack[--top]
            val gx = i % gw
            val gy = i / gw
            if (gx == 0 || gy == 0 || gx == gw - 1 || gy == gh - 1) return null
            filled++
            if (gx < minX) minX = gx
            if (gx > maxX) maxX = gx
            if (gy < minY) minY = gy
            if (gy > maxY) maxY = gy
            for (nb in intArrayOf(i - 1, i + 1, i - gw, i + gw)) {
                if (!seen[nb] && flat[nb] && close(i, nb)) {
                    seen[nb] = true
                    stack[top++] = nb
                }
            }
        }
        val cells = (maxX - minX + 1).toLong() * (maxY - minY + 1)
        // A box is mostly plain; a few flat patches in a drawing are not one
        if (cells <= 0 || filled < cells * BOX_FLAT_SHARE) return null
        val rect = Rect(
            min(minX * step, bx),
            min(minY * step, by),
            max((maxX + 1) * step, bx + bw),
            max((maxY + 1) * step, by + bh),
        )
        return rect.takeIf { it.area > bw.toLong() * bh * MIN_GROWTH }
    }

    /** True or false when both bubbles are known: the same bubble when they mostly cover each other. */
    fun same(a: Rect?, b: Rect?): Boolean? {
        if (a == null || b == null) return null
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return false
        val inter = w.toLong() * h
        return inter >= min(a.area, b.area) * SAME_SHARE
    }

    private fun luma(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private const val LIGHT = 200
    private const val STEP_DIVISOR = 6
    private const val MIN_GROWTH = 1.3

    // MANHWA plain boxes: a cell is plain when no channel varies more than this; neighbours join when their
    // colours are this close (sum over the channels), which follows a gradient but stops at a border or drawing
    private const val FLAT_RANGE = 26
    private const val STEP_DIFF = 30
    private const val BOX_FLAT_SHARE = 0.45
    private const val SAME_SHARE = 0.6
}
