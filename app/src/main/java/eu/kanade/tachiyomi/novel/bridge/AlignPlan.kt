package eu.kanade.tachiyomi.novel.bridge

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * MANHWA novel bridge: which novel chapters to read to find where a manhwa chapter is told, so a 1 000-chapter
 * novel is not downloaded whole. Places found before tell where to look (the pace of the adaptation); with none,
 * a spread of chapters over where an adaptation usually is (a manhwa chapter tells 0.6 to 4 novel chapters'
 * worth), then a close look around the best of them.
 *
 * Pure Kotlin: tested without a phone.
 */
object AlignPlan {

    const val SAMPLES = 16
    const val WINDOW = 8
    const val MAX_WINDOW = 12

    /**
     * The novel chapter index [manhwaNumber] should be at, from places already found ([places]: manhwa chapter
     * number to novel index); null with none. Two or more: the line through the nearest one below and above (or
     * the two nearest on one side); one: the same pace from the start.
     */
    fun predict(manhwaNumber: Double, places: List<Pair<Double, Int>>): Int? {
        val known = places.filter { it.first > 0 }.distinctBy { it.first }.sortedBy { it.first }
        if (known.isEmpty() || manhwaNumber <= 0) return null
        if (known.size == 1) {
            val (n, i) = known[0]
            return ((i + 1) * manhwaNumber / n).roundToInt() - 1
        }
        val below = known.lastOrNull { it.first <= manhwaNumber }
        val above = known.firstOrNull { it.first >= manhwaNumber }
        val (a, b) = when {
            below != null && above != null && below.first != above.first -> below to above
            below != null && above != null -> return below.second
            below != null -> known[known.size - 2] to known.last()
            else -> known[0] to known[1]
        }
        val slope = (b.second - a.second) / (b.first - a.first)
        return (a.second + slope * (manhwaNumber - a.first)).roundToInt()
    }

    /** Chapters spread over where manhwa chapter [manhwaNumber] may be told in a novel of [count] chapters. */
    fun coarse(manhwaNumber: Double, count: Int, samples: Int = SAMPLES): List<Int> {
        if (count <= 0) return emptyList()
        val (lo, hi) = if (manhwaNumber > 0) {
            (floor(manhwaNumber * 0.6).toInt() - 1).coerceIn(0, count - 1) to
                (ceil(manhwaNumber * 4).toInt() + 10).coerceIn(0, count - 1)
        } else {
            0 to count - 1
        }
        val span = hi - lo
        if (span + 1 <= samples) return (lo..hi).toList()
        return (0 until samples).map { lo + (span.toDouble() * it / (samples - 1)).roundToInt() }.distinct()
    }

    /** Chapters within [radius] of [center] (capped at [MAX_WINDOW]), inside a novel of [count] chapters. */
    fun window(center: Int, count: Int, radius: Int = WINDOW): List<Int> {
        if (count <= 0) return emptyList()
        val r = radius.coerceIn(1, MAX_WINDOW)
        val c = center.coerceIn(0, count - 1)
        return ((c - r).coerceAtLeast(0)..(c + r).coerceAtMost(count - 1)).toList()
    }

    /** The gap between the chapters of a [coarse] spread (1 when they are next to each other). */
    fun gap(spread: List<Int>): Int = spread.zipWithNext { a, b -> b - a }.maxOrNull() ?: 1
}
