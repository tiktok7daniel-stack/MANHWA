package eu.kanade.translation.live

/** MANHWA: the pure rules of what kind a line of text is (tested without a phone). */
object TextKindRules {

    /** Kind of a line, read as a shout: erased and replaced in its bubble. */
    const val SHOUT = "shout"

    /** Kind of the text drawn into the art: the drawing stays, the translation goes in a caption under it. */
    const val ART = "art"

    /**
     * Big letters (far bigger than the page's text) are text drawn into the art, unless they sit inside a speech
     * bubble: then they are someone shouting.
     */
    fun bigLetters(inBubble: Boolean): String = if (inBubble) SHOUT else ART

    /**
     * MANHWA: the blocks whose translation came back empty (the AI joined their line into a neighbour's) that
     * lie within [reach] of a translated block, as (empty block index, translated block index). Boxes are
     * (left, top, right, bottom).
     */
    fun joinedLines(
        boxes: List<FloatArray>,
        translated: List<Boolean>,
        reach: (Int) -> Float,
    ): List<Pair<Int, Int>> = boxes.indices.filter { !translated[it] }.mapNotNull { empty ->
        val r = reach(empty)
        val b = boxes[empty]
        val owner = boxes.indices.firstOrNull { other ->
            translated[other] && boxes[other].let { o ->
                b[0] - r < o[2] && b[2] + r > o[0] && b[1] - r < o[3] && b[3] + r > o[1]
            }
        } ?: return@mapNotNull null
        empty to owner
    }
}
