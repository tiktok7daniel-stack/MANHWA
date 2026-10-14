package eu.kanade.translation.live

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.model.LineBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns ML Kit text into the bubbles of a page. Every block keeps the exact box of each of its lines, so the
 * page is cleaned line by line and never with one big shape. Lines of the same bubble are joined, text that
 * is far apart never is, and boxes that overlap are reduced to one before and after translating.
 */
object PageOcr {

    /**
     * Reads [bitmap] in overlapping strips, so text on tall webtoon pages is not shrunk away by the OCR.
     * Boxes are in [bitmap] pixels; a bubble read twice in the overlap keeps its larger reading.
     */
    fun recognize(recognizer: TextRecognizer, bitmap: Bitmap): PageTranslation {
        val w = bitmap.width
        val h = bitmap.height
        val stripH = (w * STRIP_RATIO).toInt().coerceAtLeast(64)
        if (h <= stripH * 1.15f) return toPageTranslation(read(recognizer, bitmap), w, h)
        val overlap = (stripH * OVERLAP).toInt()
        val found = mutableListOf<TranslationBlock>()
        var top = 0
        while (top < h) {
            val sh = min(stripH, h - top)
            val strip = Bitmap.createBitmap(bitmap, 0, top, w, sh)
            val part = try {
                toBlocks(read(recognizer, strip))
            } finally {
                if (strip !== bitmap) strip.recycle()
            }
            for (block in part) {
                val moved = block.shifted(top.toFloat())
                val twin = found.indexOfFirst { overlapRatio(it, moved) > 0.3f }
                when {
                    twin < 0 -> found += moved
                    moved.width * moved.height > found[twin].width * found[twin].height -> found[twin] = moved
                }
            }
            if (top + sh >= h) break
            top += sh - overlap
        }
        return PageTranslation(mergeLines(found, w.toFloat()), w.toFloat(), h.toFloat())
    }

    fun toPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation =
        PageTranslation(mergeLines(toBlocks(blocks), width.toFloat()), width.toFloat(), height.toFloat())

    private fun read(recognizer: TextRecognizer, bitmap: Bitmap): List<Text.TextBlock> =
        recognizer.recognize(InputImage.fromBitmap(bitmap, 0)).textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

    private fun toBlocks(blocks: List<Text.TextBlock>): List<TranslationBlock> = blocks.mapNotNull { block ->
        val bounds = block.boundingBox ?: return@mapNotNull null
        val lines = block.lines.mapNotNull { line ->
            line.boundingBox?.let { LineBox(it.left.toFloat(), it.top.toFloat(), it.width().toFloat(), it.height().toFloat()) }
        }.ifEmpty { listOf(LineBox(bounds.left.toFloat(), bounds.top.toFloat(), bounds.width().toFloat(), bounds.height().toFloat())) }
        val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()?.symbols?.firstOrNull()?.boundingBox
            ?: block.lines.firstOrNull()?.boundingBox
            ?: bounds
        TranslationBlock(
            text = block.text,
            width = bounds.width().toFloat(),
            height = bounds.height().toFloat(),
            symWidth = symBounds.width().toFloat(),
            symHeight = symBounds.height().toFloat(),
            angle = 0f,
            x = bounds.left.toFloat(),
            y = bounds.top.toFloat(),
            lines = lines,
        )
    }

    private fun TranslationBlock.shifted(dy: Float): TranslationBlock =
        copy(y = y + dy, lines = lines.map { it.copy(y = it.y + dy) })

    /**
     * Joins blocks that are lines of the same bubble: close one under the other, with the same text size and
     * overlapping columns, or boxes that overlap. Never past a size limit, so far-apart text stays apart.
     */
    private fun mergeLines(blocks: List<TranslationBlock>, pageWidth: Float): MutableList<TranslationBlock> {
        val list = blocks.sortedWith(compareBy({ it.y }, { it.x })).toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    if (shouldJoin(list[i], list[j], pageWidth)) {
                        list[i] = join(list[i], list[j])
                        list.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return list
    }

    private fun shouldJoin(a: TranslationBlock, b: TranslationBlock, pageWidth: Float): Boolean {
        val left = min(a.x, b.x)
        val right = max(a.x + a.width, b.x + b.width)
        val top = min(a.y, b.y)
        val bottom = max(a.y + a.height, b.y + b.height)
        // Size cap: one bubble never covers most of the page
        if (right - left > pageWidth * MAX_WIDTH || bottom - top > pageWidth * MAX_HEIGHT) return false
        if (a.lineBoxes.size + b.lineBoxes.size > MAX_LINES) return false
        if (overlapRatio(a, b) > 0.15f) return true
        val ha = a.lineHeight()
        val hb = b.lineHeight()
        // Same text size: a title or a sound effect is not part of the dialogue next to it
        if (abs(ha - hb) > max(ha, hb) * 0.45f) return false
        val (upper, lower) = if (a.y <= b.y) a to b else b to a
        val gap = lower.y - (upper.y + upper.height)
        if (gap > min(ha, hb) * 0.9f || gap < -min(ha, hb) * 0.6f) return false
        val overlapX = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val centred = abs((a.x + a.width / 2) - (b.x + b.width / 2)) < max(a.width, b.width) * 0.25f
        return overlapX > min(a.width, b.width) * 0.4f || centred
    }

    private fun join(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
        val (first, second) = if (a.y <= b.y) a to b else b to a
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        return first.copy(
            text = first.text + "\n" + second.text,
            translation = listOf(first.translation, second.translation).filter { it.isNotBlank() }.joinToString(" "),
            x = left,
            y = top,
            width = max(a.x + a.width, b.x + b.width) - left,
            height = max(a.y + a.height, b.y + b.height) - top,
            lines = (first.lineBoxes + second.lineBoxes).sortedBy { it.y },
        )
    }

    private fun TranslationBlock.lineHeight(): Float {
        val boxes = lineBoxes
        return (boxes.sumOf { it.h.toDouble() } / boxes.size).toFloat().coerceAtLeast(1f)
    }

    /** Intersection over the smaller box: 1 when one box is inside the other. */
    private fun overlapRatio(a: TranslationBlock, b: TranslationBlock): Float {
        val inter = intersection(a, b)
        if (inter <= 0f) return 0f
        return inter / min(a.width * a.height, b.width * b.height).coerceAtLeast(1f)
    }

    private fun iou(a: TranslationBlock, b: TranslationBlock): Float {
        val inter = intersection(a, b)
        if (inter <= 0f) return 0f
        return inter / (a.width * a.height + b.width * b.height - inter).coerceAtLeast(1f)
    }

    private fun intersection(a: TranslationBlock, b: TranslationBlock): Float {
        val w = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val h = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    /**
     * After translating: one translation per place. A piece the model left empty (it joined it into the bubble
     * next to it) gives its lines to that bubble so they are erased too; boxes that overlap another one
     * (IoU over 0.15, or mostly inside it) are dropped and only the larger, merged one is drawn.
     */
    fun finish(page: PageTranslation) {
        // Bubbles from the vision model whose letters could not be found on the page are never drawn
        val blocks = page.blocks.filterNot { it.needsSnap }.toMutableList()
        // 1. Empty pieces: erased with the nearest translated bubble, or dropped
        val empty = blocks.filter { it.translation.isBlank() }
        blocks.removeAll(empty)
        for (piece in empty) {
            val host = blocks
                .filter { near(it, piece) }
                .minByOrNull { distance(it, piece) }
                ?: continue
            blocks[blocks.indexOf(host)] = absorb(host, piece)
        }
        // 2. Non-maximum suppression: the largest box wins, the others give it their lines
        val sorted = blocks.sortedByDescending { it.width * it.height }.toMutableList()
        val kept = mutableListOf<TranslationBlock>()
        for (block in sorted) {
            val i = kept.indexOfFirst { iou(it, block) > NMS_IOU || overlapRatio(it, block) > 0.5f }
            if (i < 0) kept += block else kept[i] = absorb(kept[i], block)
        }
        page.blocks = kept.sortedWith(compareBy({ it.y }, { it.x })).toMutableList()
    }

    /** [host] keeps its translation and also erases the lines of [other]. */
    private fun absorb(host: TranslationBlock, other: TranslationBlock): TranslationBlock {
        val left = min(host.x, other.x)
        val top = min(host.y, other.y)
        return host.copy(
            x = left,
            y = top,
            width = max(host.x + host.width, other.x + other.width) - left,
            height = max(host.y + host.height, other.y + other.height) - top,
            lines = (host.lineBoxes + other.lineBoxes).distinct().sortedBy { it.y },
        ).also {
            it.needsSnap = false
            it.userDx = host.userDx
            it.userDy = host.userDy
            it.userScale = host.userScale
            it.userFont = host.userFont
        }
    }

    private fun near(a: TranslationBlock, b: TranslationBlock): Boolean {
        if (overlapRatio(a, b) > 0f) return true
        val ha = a.lineHeight()
        val hb = b.lineHeight()
        // Only a piece of the same text (same size, same column), never art or a sound effect next to it
        if (abs(ha - hb) > max(ha, hb) * 0.45f) return false
        val overlapX = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        if (overlapX <= 0f && abs((a.x + a.width / 2) - (b.x + b.width / 2)) > max(a.width, b.width) * 0.5f) return false
        val h = min(ha, hb)
        val gapY = max(a.y, b.y) - min(a.y + a.height, b.y + b.height)
        val gapX = max(a.x, b.x) - min(a.x + a.width, b.x + b.width)
        return gapY < h * 1.5f && gapX < h * 1.5f
    }

    private fun distance(a: TranslationBlock, b: TranslationBlock): Float {
        val dx = (a.x + a.width / 2) - (b.x + b.width / 2)
        val dy = (a.y + a.height / 2) - (b.y + b.height / 2)
        return dx * dx + dy * dy
    }

    /** Scales every box of [this] page by [factor], e.g. from a downscaled bitmap back to the real page. */
    fun PageTranslation.rescaled(factor: Float): PageTranslation {
        if (factor == 1f) return this
        blocks.forEach {
            it.x *= factor
            it.y *= factor
            it.width *= factor
            it.height *= factor
            it.symWidth *= factor
            it.symHeight *= factor
            it.lines = it.lines.map { l -> LineBox(l.x * factor, l.y * factor, l.w * factor, l.h * factor, l.bg) }
        }
        imgWidth *= factor
        imgHeight *= factor
        return this
    }

    private const val STRIP_RATIO = 2.2f
    private const val OVERLAP = 0.15f
    private const val MAX_WIDTH = 0.95f
    private const val MAX_HEIGHT = 0.6f
    private const val MAX_LINES = 12
    private const val NMS_IOU = 0.15f
}
