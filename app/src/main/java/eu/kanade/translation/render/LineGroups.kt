package eu.kanade.translation.render

import android.graphics.RectF

/**
 * MANHWA cleaning: a bubble's lines put together when their cleaning margins overlap, so a stack of close lines
 * is cleaned as one area (its background read around all of them) instead of each line reading its neighbours'
 * letters as background.
 */
object LineGroups {

    /** Groups of indices of [rects] (each line's area with its margin), each in order. */
    fun of(rects: List<RectF>): List<List<Int>> =
        groups(rects.map { floatArrayOf(it.left, it.top, it.right, it.bottom) })

    /** [of] on plain boxes (left, top, right, bottom): pure, tested without a phone. */
    fun groups(boxes: List<FloatArray>): List<List<Int>> {
        val parent = IntArray(boxes.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val touch = a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]
            if (touch) parent[root(j)] = root(i)
        }
        return boxes.indices.groupBy { root(it) }.values.map { it.sorted() }.sortedBy { it.first() }
    }
}
