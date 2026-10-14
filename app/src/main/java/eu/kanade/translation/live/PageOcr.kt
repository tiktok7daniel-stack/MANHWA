package eu.kanade.translation.live

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.min

/** Turns ML Kit text blocks into the bubbles of a page, merging lines that belong to the same bubble. */
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
        val result = PageTranslation(imgWidth = w.toFloat(), imgHeight = h.toFloat())
        var top = 0
        while (top < h) {
            val sh = min(stripH, h - top)
            val strip = Bitmap.createBitmap(bitmap, 0, top, w, sh)
            val part = try {
                toPageTranslation(read(recognizer, strip), w, sh)
            } finally {
                if (strip !== bitmap) strip.recycle()
            }
            for (block in part.blocks) {
                block.y += top
                val twin = result.blocks.firstOrNull { it.overlapsMostly(block) }
                when {
                    twin == null -> result.blocks += block
                    block.width * block.height > twin.width * twin.height -> result.blocks[result.blocks.indexOf(twin)] = block
                }
            }
            if (top + sh >= h) break
            top += sh - overlap
        }
        return result
    }

    private fun read(recognizer: TextRecognizer, bitmap: Bitmap): List<Text.TextBlock> =
        recognizer.recognize(InputImage.fromBitmap(bitmap, 0)).textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

    private fun TranslationBlock.overlapsMostly(other: TranslationBlock): Boolean {
        val l = kotlin.math.max(x, other.x)
        val t = kotlin.math.max(y, other.y)
        val r = kotlin.math.min(x + width, other.x + other.width)
        val b = kotlin.math.min(y + height, other.y + other.height)
        if (r <= l || b <= t) return false
        return (r - l) * (b - t) > 0.3f * kotlin.math.min(width * height, other.width * other.height)
    }

    private const val STRIP_RATIO = 2.2f
    private const val OVERLAP = 0.15f

    fun toPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox ?: continue
            val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()?.symbols?.firstOrNull()?.boundingBox
                ?: block.lines.firstOrNull()?.boundingBox
                ?: bounds
            translation.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = symBounds.width().toFloat(),
                    symHeight = symBounds.height().toFloat(),
                    angle = block.lines.firstOrNull()?.angle ?: 0f,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }
        // Smart merge overlapping text blocks
        translation.blocks = smartMergeBlocks(translation.blocks, 50, 30, 30)

        return translation
    }

    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val merged = mutableListOf<TranslationBlock>()
        var current = blocks[0]
        for (i in 1 until blocks.size) {
            val next = blocks[i]
            if (shouldMergeTextBlock(current, next, widthThreshold, xThreshold, yThreshold)) {
                current = mergeTextBlock(current, next)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun shouldMergeTextBlock(
        a: TranslationBlock,
        b: TranslationBlock,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): Boolean {
        val isWidthSimilar = (b.width < a.width) || (abs(a.width - b.width) < widthThreshold)
        val isXClose = abs(a.x - b.x) < xThreshold
        val isYClose = (b.y - (a.y + a.height)) in -yThreshold.toFloat()..yThreshold.toFloat()
        return isWidthSimilar && isXClose && isYClose
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
        val newX = kotlin.math.min(a.x, b.x)
        val newY = a.y
        val newWidth = kotlin.math.max(a.x + a.width, b.x + b.width) - newX
        val newHeight = kotlin.math.max(a.y + a.height, b.y + b.height) - newY
        return TranslationBlock(
            a.text + " " + b.text,
            a.translation + " " + b.translation,
            newWidth,
            newHeight,
            newX, newY, a.symHeight,
            a.symWidth, a.angle,
        )
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
        }
        imgWidth *= factor
        imgHeight *= factor
        return this
    }
}
