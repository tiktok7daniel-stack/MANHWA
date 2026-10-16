package eu.kanade.translation.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MANHWA real scanlation cleaning: exemplar-based inpainting (after Criminisi, Pérez and Toyama), all on the
 * phone. Where letters sit on a busy drawing (speed lines, rain, textures, gradients with grain), the hole left
 * by the letters is filled patch by patch with real pieces of the drawing around it, starting where lines
 * run into the hole, so lines carry on through instead of turning into a smudge.
 *
 * Pure (pixels in, pixels out): works on ARGB ints and is tested without a phone. Bounded work: the search for
 * a patch stays in a window around it, and holes too big are left to the caller's simpler fill.
 */
object ExemplarInpainter {

    /** Holes larger than this (pixels) are not inpainted here (too slow for the reader). */
    const val MAX_HOLE = 16_000

    private const val HALF = 3
    private const val STEP = 2

    /**
     * Fills the pixels of [px] (width [w], height [h]) where [hole] is true, in place. Returns false when it
     * could not (no source around, hole too big); then [px] is left as it was.
     */
    fun fill(px: IntArray, w: Int, h: Int, hole: BooleanArray): Boolean {
        val n = w * h
        if (px.size != n || hole.size != n) return false
        val holeCount = hole.count { it }
        if (holeCount == 0) return true
        if (holeCount > MAX_HOLE || holeCount > n * 0.85) return false
        val out = px.copyOf()
        val known = BooleanArray(n) { !hole[it] }
        val confidence = FloatArray(n) { if (known[it]) 1f else 0f }
        // Source patches: fully known in the original, searched on a coarse grid
        val sourceOk = BooleanArray(n)
        var anySource = false
        for (y in HALF until h - HALF step STEP) for (x in HALF until w - HALF step STEP) {
            if (patchKnown(known, w, x, y)) {
                sourceOk[y * w + x] = true
                anySource = true
            }
        }
        if (!anySource) return false
        val window = max(24, (HALF * 2 + 1) * 6)
        var left = holeCount
        var guard = holeCount + 16
        // Only the pixels still to fill are looked at on every step
        val remaining = IntArray(holeCount)
        var remainingCount = 0
        for (i in 0 until n) if (hole[i]) remaining[remainingCount++] = i
        while (left > 0 && guard-- > 0) {
            // The fill front: unknown pixels with a known neighbour; the most confident with the strongest
            // structure around it goes first
            var best = -1
            var bestPriority = -1f
            var kept = 0
            for (k in 0 until remainingCount) {
                val i = remaining[k]
                if (known[i]) continue
                remaining[kept++] = i
                val x = i % w
                val y = i / w
                if (!touchesKnown(known, w, h, x, y)) continue
                val c = patchConfidence(confidence, w, h, x, y)
                val d = 1f + isophote(out, known, w, h, x, y)
                val p = c * d
                if (p > bestPriority) {
                    bestPriority = p
                    best = i
                }
            }
            remainingCount = kept
            if (best < 0) break
            val bx = best % w
            val by = best / w
            val q = bestMatch(out, known, sourceOk, w, h, bx, by, window) ?: nearestSource(sourceOk, w, h, bx, by) ?: break
            val qx = q % w
            val qy = q / w
            val c = patchConfidence(confidence, w, h, bx, by)
            for (dy in -HALF..HALF) for (dx in -HALF..HALF) {
                val x = bx + dx
                val y = by + dy
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val i = y * w + x
                if (known[i]) continue
                val sx = (qx + dx).coerceIn(0, w - 1)
                val sy = (qy + dy).coerceIn(0, h - 1)
                out[i] = out[sy * w + sx]
                known[i] = true
                confidence[i] = c
                left--
            }
        }
        if (left > 0) return false
        System.arraycopy(out, 0, px, 0, n)
        return true
    }

    private fun patchKnown(known: BooleanArray, w: Int, x: Int, y: Int): Boolean {
        for (dy in -HALF..HALF) for (dx in -HALF..HALF) if (!known[(y + dy) * w + x + dx]) return false
        return true
    }

    private fun touchesKnown(known: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean =
        (x > 0 && known[y * w + x - 1]) || (x < w - 1 && known[y * w + x + 1]) ||
            (y > 0 && known[(y - 1) * w + x]) || (y < h - 1 && known[(y + 1) * w + x])

    private fun patchConfidence(confidence: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        var sum = 0f
        var count = 0
        for (dy in -HALF..HALF) for (dx in -HALF..HALF) {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            sum += confidence[ny * w + nx]
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    /** How strong the drawing's lines are right next to the hole here (0–1): lines are carried in first. */
    private fun isophote(px: IntArray, known: BooleanArray, w: Int, h: Int, x: Int, y: Int): Float {
        var strongest = 0
        for (dy in -1..1) for (dx in -1..1) {
            val ax = x + dx
            val ay = y + dy
            if (ax <= 0 || ay <= 0 || ax >= w - 1 || ay >= h - 1) continue
            val i = ay * w + ax
            if (!known[i] || !known[i - 1] || !known[i + 1] || !known[i - w] || !known[i + w]) continue
            val g = abs(luma(px[i + 1]) - luma(px[i - 1])) + abs(luma(px[i + w]) - luma(px[i - w]))
            strongest = max(strongest, g)
        }
        return min(1f, strongest / 255f)
    }

    /** The source patch most like the known part of the patch at ([x], [y]), in a window around it. */
    private fun bestMatch(px: IntArray, known: BooleanArray, sourceOk: BooleanArray, w: Int, h: Int, x: Int, y: Int, window: Int): Int? {
        var best = -1
        var bestCost = Long.MAX_VALUE
        val x0 = max(HALF, x - window)
        val x1 = min(w - HALF - 1, x + window)
        val y0 = max(HALF, y - window)
        val y1 = min(h - HALF - 1, y + window)
        var sy = y0
        while (sy <= y1) {
            var sx = x0
            while (sx <= x1) {
                val q = sy * w + sx
                if (sourceOk[q]) {
                    var cost = 0L
                    var used = 0
                    loop@ for (dy in -HALF..HALF) for (dx in -HALF..HALF) {
                        val tx = x + dx
                        val ty = y + dy
                        if (tx < 0 || ty < 0 || tx >= w || ty >= h) continue
                        val t = ty * w + tx
                        if (!known[t]) continue
                        cost += distance2(px[t], px[(sy + dy) * w + sx + dx])
                        used++
                        if (cost >= bestCost) break@loop
                    }
                    if (used > 0 && cost < bestCost) {
                        bestCost = cost
                        best = q
                    }
                }
                sx += STEP
            }
            sy += STEP
        }
        return best.takeIf { it >= 0 }
    }

    private fun nearestSource(sourceOk: BooleanArray, w: Int, h: Int, x: Int, y: Int): Int? {
        var best = -1
        var bestD = Int.MAX_VALUE
        for (i in sourceOk.indices) {
            if (!sourceOk[i]) continue
            val d = abs(i % w - x) + abs(i / w - y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun luma(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun distance2(a: Int, b: Int): Long {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return (dr * dr + dg * dg + db * db).toLong()
    }
}
