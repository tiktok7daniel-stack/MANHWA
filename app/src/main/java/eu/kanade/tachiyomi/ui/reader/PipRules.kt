package eu.kanade.tachiyomi.ui.reader

/** MANHWA: the pure rule of the reader's small window (tested without a phone). */
object PipRules {
    /**
     * The small window's shape for a reader of [width] x [height]: the same shape, kept within what Android accepts
     * (no thinner than 1:2.39, no wider than 2.39:1), as a ratio of whole numbers.
     */
    fun ratio(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 9 to 16
        val r = width.toDouble() / height
        val clamped = r.coerceIn(MIN, MAX)
        return if (clamped == r) width to height else (clamped * 1000).toInt() to 1000
    }

    private const val MIN = 1 / 2.39 + 0.001
    private const val MAX = 2.39 - 0.001
}
