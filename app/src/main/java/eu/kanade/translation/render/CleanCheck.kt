package eu.kanade.translation.render

import android.content.Context
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MANHWA cleaning that checks its own work: after a bubble is cleaned, the place its letters were is compared with
 * the background just around it, the way a letterer looks at a page. Letters left behind (light strokes on a dark
 * page, dark ones on a light page, the white of outlined lettering on grain) are pixels the background around
 * never has. When too many are left, the bubble is cleaned harder; [CleanMemory] remembers, per kind of
 * background, how hard it had to go, and starts there the next time.
 *
 * The measures are pure Kotlin on luminance values (0 to 255): tested without a phone.
 */
object CleanCheck {

    enum class Ground { PLAIN, GRADIENT, GRAIN, BUSY }

    /** Share of the letters' place that may differ from the background around it before it counts as left. */
    const val LEFT = 0.025f

    /** How far beyond the background's own range a pixel must be to count as a letter left. */
    private const val MARGIN = 14

    /**
     * The share of [inside] (luminance of where the letters were) outside the range the [ring] around it covers
     * (its 3rd to 97th percentile, widened a little). Grain and gradients stay inside their range; a stroke of a
     * letter does not.
     */
    fun residue(inside: IntArray, ring: IntArray): Float {
        if (inside.isEmpty() || ring.size < 16) return 0f
        val sorted = ring.sortedArray()
        val lo = sorted[(sorted.size * 0.03f).toInt()] - MARGIN
        val hi = sorted[((sorted.size - 1) * 0.97f).toInt()] + MARGIN
        return inside.count { it < lo || it > hi }.toFloat() / inside.size
    }

    /**
     * What kind of background [ring] is, from its spread and its roughness ([roughness]: the mean difference
     * between neighbouring pixels): flat, a smooth fade, fine grain, or a drawing.
     */
    fun groundOf(ring: IntArray, roughness: Float): Ground {
        if (ring.isEmpty()) return Ground.BUSY
        val mean = ring.average()
        val spread = sqrt(ring.sumOf { (it - mean) * (it - mean) } / ring.size).toFloat()
        return when {
            spread < 6f && roughness < 4f -> Ground.PLAIN
            roughness < 5f -> Ground.GRADIENT
            spread < 34f -> Ground.GRAIN
            else -> Ground.BUSY
        }
    }

    /** Mean difference between each pixel and the one to its right, over rows of [w] luminance values. */
    fun roughness(lum: IntArray, w: Int): Float {
        if (w < 2 || lum.size < 2) return 0f
        var sum = 0L
        var n = 0
        for (i in 0 until lum.size - 1) {
            if ((i + 1) % w == 0) continue
            sum += abs(lum[i] - lum[i + 1])
            n++
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    fun luma(c: Int): Int = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
}

/**
 * MANHWA: what the cleaner learnt on this phone: for each kind of background, how hard the last bubbles had to be
 * cleaned (0 = the usual cleaning was enough, 1 = cleaned again wider, 2 = a soft plate of the background colour).
 * When most of the recent bubbles on a kind of background needed more, the next ones start there.
 */
object CleanMemory {

    private const val PREFS = "manhwa_clean_memory"
    private const val KEEP = 20

    /** At least this many bubbles seen before the memory changes anything. */
    private const val MIN_SEEN = 5

    @Volatile private var history: MutableMap<CleanCheck.Ground, ArrayDeque<Int>>? = null

    private fun load(context: Context): MutableMap<CleanCheck.Ground, ArrayDeque<Int>> {
        history?.let { return it }
        synchronized(this) {
            history?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val map = CleanCheck.Ground.entries.associateWith { g ->
                ArrayDeque(prefs.getString(g.name, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.takeLast(KEEP))
            }.toMutableMap()
            history = map
            return map
        }
    }

    /** The level to start cleaning at on [ground]. */
    fun start(context: Context, ground: CleanCheck.Ground): Int =
        synchronized(this) { startFrom(load(context)[ground].orEmpty().toList()) }

    fun record(context: Context, ground: CleanCheck.Ground, level: Int) {
        synchronized(this) {
            val list = load(context).getOrPut(ground) { ArrayDeque() }
            list.addLast(level.coerceIn(0, 2))
            while (list.size > KEEP) list.removeFirst()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ground.name, list.joinToString(","))
                .apply()
        }
    }

    /** From the levels the last bubbles needed (oldest first): the one most of the last ten reached. */
    fun startFrom(levels: List<Int>): Int {
        if (levels.size < MIN_SEEN) return 0
        val recent = levels.takeLast(10)
        val need = recent.size * 0.6f
        return when {
            recent.count { it >= 2 } >= need -> 2
            recent.count { it >= 1 } >= need -> 1
            else -> 0
        }
    }
}
