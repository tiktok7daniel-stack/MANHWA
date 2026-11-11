package eu.kanade.translation.render

/**
 * MANHWA cleaning over a drawing: exactly which pixels are the letters, so only they are painted over and the art
 * between and around them stays. The letters' colours are found as the colours common inside the text box and rare
 * in the ring of page around it (a white fill and a black outline over a grey street, red letters over a face):
 * whatever the colours, no fixed black or white is assumed. The mask is grown by the letters' soft edge.
 *
 * Pure Kotlin on ARGB ints: tested without a phone.
 */
object LetterMask {

    /** Least share of the text box a letter colour covers. */
    private const val MIN_INK_SHARE = 0.02f

    /** A letter colour is at least this many times more common inside the box than around it. */
    private const val INSIDE_OVER_RING = 4f

    /** A mask covering less (nothing found) or more (the art itself) than this of the box is not trusted. */
    private const val MIN_COVER = 0.03f
    private const val MAX_COVER = 0.8f

    private fun bin(c: Int): Int = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)

    /**
     * The letters inside the box ([x0], [y0]) to ([x1], [y1]) of [px] (width [w], height [h]; the rest of the area
     * is the ring), grown by [grow] pixels; null when no colour stands out as the letters' or the mask would cover
     * too little or too much of the box to be the letters.
     */
    fun build(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, grow: Int): BooleanArray? {
        if (x1 - x0 < 2 || y1 - y0 < 2 || px.size != w * h) return null
        val inside = IntArray(4096)
        val ring = IntArray(4096)
        var insideTotal = 0
        var ringTotal = 0
        for (y in 0 until h) for (x in 0 until w) {
            val b = bin(px[y * w + x])
            if (x in x0 until x1 && y in y0 until y1) {
                inside[b]++
                insideTotal++
            } else {
                ring[b]++
                ringTotal++
            }
        }
        if (insideTotal == 0 || ringTotal < 16) return null
        val ink = BooleanArray(4096) { b ->
            val inShare = inside[b].toFloat() / insideTotal
            val ringShare = ring[b].toFloat() / ringTotal
            inShare >= MIN_INK_SHARE && inShare > ringShare * INSIDE_OVER_RING
        }
        if (ink.none { it }) return null
        val core = BooleanArray(px.size)
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (ink[bin(px[i])]) {
                core[i] = true
                count++
            }
        }
        val area = (x1 - x0) * (y1 - y0)
        if (count < area * MIN_COVER || count > area * MAX_COVER) return null
        if (grow <= 0) return core
        // The letters' soft edge (anti-aliasing, a thin glow) goes with them
        val mask = core.copyOf()
        for (y in y0 until y1) for (x in x0 until x1) {
            if (!core[y * w + x]) continue
            for (dy in -grow..grow) for (dx in -grow..grow) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) mask[ny * w + nx] = true
            }
        }
        return mask
    }
}
