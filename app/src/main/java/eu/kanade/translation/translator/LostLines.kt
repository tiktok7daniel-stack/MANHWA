package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min

/**
 * MANHWA: lines of one bubble that came back from the AI empty, or word for word in English, while their neighbour
 * was translated. The AI said it put their words in the neighbour's translation, but it often did not
 * ("PHYSICAL-TYPE, RISK LEVEL: INCOMPREHENSIBLE." drawn as "المستوى:.", "COOPERATION MUST" left in English): their
 * meaning was lost and their letters erased. Such a bubble is put back together as one block, in reading order, and
 * translated again whole, so nothing it says is dropped.
 */
object LostLines {

    /**
     * Replaces in [page] every bubble with a lost line by one block holding all of its lines; returns those
     * blocks, to be translated again. Their translation is, meanwhile, what their lines had in Arabic (or the
     * target language), so a failed second try still shows something.
     */
    fun mergeLost(page: PageTranslation, targetLatin: Boolean): List<TranslationBlock> {
        val blocks = page.blocks.filterNot { it.kind.equals("sfx", ignoreCase = true) || it.edited }
        val groups = clusters(blocks.map { box(it) }, blocks.map { lineHeight(it) })
            .map { group -> group.map { blocks[it] } }
            .filter { it.size >= 2 && lost(it, targetLatin) }
        if (groups.isEmpty()) return emptyList()
        val merged = groups.map { merge(it, targetLatin) }
        groups.zip(merged).forEach { (group, one) ->
            val at = page.blocks.indexOfFirst { b -> group.any { it === b } }
            page.blocks.removeAll { b -> group.any { it === b } }
            page.blocks.add(at.coerceIn(0, page.blocks.size), one)
        }
        return merged
    }

    /** Whether a line of [group] has no translation of its own, or still shows its source. */
    fun lost(group: List<TranslationBlock>, targetLatin: Boolean): Boolean = group.any { b ->
        val letters = b.text.count { it.isLetter() }
        val tr = b.translation.trim()
        letters >= 3 && !tr.contains(AiTranslator.WATERMARK) &&
            (tr.isEmpty() || (!targetLatin && tr.equals(b.text.trim(), ignoreCase = true)))
    } && group.any { it.translation.isNotBlank() }

    private fun merge(group: List<TranslationBlock>, targetLatin: Boolean): TranslationBlock {
        val ordered = group.sortedWith(compareBy({ it.y }, { it.x }))
        val first = ordered.first()
        val left = ordered.minOf { it.x }
        val top = ordered.minOf { it.y }
        val right = ordered.maxOf { it.x + it.width }
        val bottom = ordered.maxOf { it.y + it.height }
        // What was translated stays meanwhile; a line left in its source is not shown again
        val kept = ordered.filter { b -> b.translation.isNotBlank() && (targetLatin || !b.translation.trim().equals(b.text.trim(), ignoreCase = true)) }
        return first.copy(
            text = ordered.joinToString("\n") { it.text },
            translation = kept.joinToString(" ") { it.translation.trim() },
            x = left,
            y = top,
            width = right - left,
            height = bottom - top,
            lines = ordered.flatMap { it.lineBoxes }.sortedBy { it.y },
            ocrConfidence = ordered.minOf { it.ocrConfidence },
            kind = ordered.firstOrNull { it.kind.isNotBlank() }?.kind.orEmpty(),
        )
    }

    private fun box(b: TranslationBlock): FloatArray {
        val l = b.lineBoxes
        return floatArrayOf(l.minOf { it.x }, l.minOf { it.y }, l.maxOf { it.right }, l.maxOf { it.bottom })
    }

    private fun lineHeight(b: TranslationBlock): Float = b.lineBoxes.map { it.h }.average().toFloat()

    /**
     * Groups of indices of [boxes] (left, top, right, bottom) that lie one under the other within a line height
     * ([lineHeights], each box's own) and overlap sideways: the lines of one bubble. Pure, tested without a phone.
     */
    fun clusters(boxes: List<FloatArray>, lineHeights: List<Float>): List<List<Int>> {
        val parent = IntArray(boxes.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            return r
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val lineH = min(lineHeights[i], lineHeights[j]).coerceAtLeast(1f)
            val gapY = max(b[1] - a[3], a[1] - b[3])
            val overlapX = min(a[2], b[2]) - max(a[0], b[0])
            val narrower = min(a[2] - a[0], b[2] - b[0]).coerceAtLeast(1f)
            if (gapY <= lineH && overlapX > narrower * 0.3f) parent[root(j)] = root(i)
        }
        return boxes.indices.groupBy { root(it) }.values.map { it.sorted() }.sortedBy { it.first() }
    }
}
