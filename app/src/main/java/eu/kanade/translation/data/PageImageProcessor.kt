package eu.kanade.translation.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import eu.kanade.translation.model.LineBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A page prepared for a vision model: JPEG slices from top to bottom, with their place on the original page. */
class PageImage(val tiles: List<Tile>) {
    class Tile(val jpeg: ByteArray, val top: Float, val height: Float)
}

/**
 * Image work done on each page while translating: the colour right behind the letters of every line (what
 * each line is erased with), exact boxes for bubbles added by the vision model and, for vision engines,
 * readable slices of the page. Pages are decoded once, downscaled, and never kept in memory.
 */
object PageImageProcessor {

    private const val TARGET_WIDTH = 1440
    private const val MAX_PIXELS = 16_000_000L
    private const val MAX_TILE_RATIO = 1.6f
    private const val JPEG_QUALITY = 85

    /** Erase margin around each line, in page pixels (at least): left/right and top/bottom. */
    const val PAD_X = 10f
    const val PAD_Y = 6f

    class Decoded(val bitmap: Bitmap, val scale: Float)

    /** Decodes a page downscaled to about [TARGET_WIDTH] pixels wide. [open] must return a fresh stream. */
    fun decode(open: () -> InputStream): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        // Very tall webtoon strips: also keep the whole bitmap within a safe memory budget
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Half the memory of ARGB: tall webtoon strips keep more detail for the OCR
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return Decoded(bitmap, bitmap.width.toFloat() / bounds.outWidth)
    }

    /** Horizontal and vertical erase margins for a line [h] page pixels tall. */
    fun padX(h: Float) = max(PAD_X, h * 0.3f)
    fun padY(h: Float) = max(PAD_Y, h * 0.2f)

    /**
     * Gets every block ready to draw: bubbles placed by the vision model get the exact box of their letters
     * (or are dropped when there are no letters there), and every line gets the colour behind its letters.
     */
    fun prepare(page: PageTranslation, decoded: Decoded) {
        val bitmap = decoded.bitmap
        val s = decoded.scale
        val dropped = mutableListOf<TranslationBlock>()
        for (block in page.blocks) {
            if (block.needsSnap) {
                if (!snapToLetters(block, bitmap, s)) dropped += block
                block.needsSnap = false
            }
        }
        page.blocks.removeAll(dropped)
        for (block in page.blocks) {
            if (block.lines.isNotEmpty() && block.lines.all { it.bg != 0 }) continue
            block.lines = block.lineBoxes.map { line -> if (line.bg != 0) line else line.copy(bg = background(line, bitmap, s) ?: 0) }
            val colors = block.lines.map { it.bg }.filter { it != 0 }
            if (colors.isNotEmpty()) {
                // The block colour is the one of most of its lines; the text colour reads well on it
                val main = colors.groupingBy { it }.eachCount().maxBy { it.value }.key
                block.bgColor = main
                block.textColor = if (luminance(main) > 140) Color.BLACK else Color.WHITE
            }
        }
    }

    /**
     * The colour right behind the letters of [line]: the most common colour inside the line box and its
     * margin (letters are thin, so the background is what most pixels show). Never taken from the bubble
     * border, so a white bubble with a black frame is cleaned in white.
     */
    private fun background(line: LineBox, bitmap: Bitmap, s: Float): Int? {
        val l = ((line.x - padX(line.h) / 2) * s).roundToInt().coerceIn(0, bitmap.width - 1)
        val r = ((line.right + padX(line.h) / 2) * s).roundToInt().coerceIn(0, bitmap.width - 1)
        val t = ((line.y - padY(line.h) / 2) * s).roundToInt().coerceIn(0, bitmap.height - 1)
        val b = ((line.bottom + padY(line.h) / 2) * s).roundToInt().coerceIn(0, bitmap.height - 1)
        if (r <= l || b <= t) return null
        return dominant(bitmap, l, t, r, b)?.first
    }

    /** Most common colour in a rect (4 bits per channel buckets, averaged), with the share of pixels it has. */
    private fun dominant(bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int): Pair<Int, Float>? {
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        val step = max(1, kotlin.math.sqrt(w.toDouble() * h / SAMPLES).toInt())
        val count = IntArray(4096)
        val sumR = LongArray(4096)
        val sumG = LongArray(4096)
        val sumB = LongArray(4096)
        val row = IntArray(w)
        var total = 0
        var y = t
        while (y < b) {
            bitmap.getPixels(row, 0, w, l, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val red = Color.red(p)
                val green = Color.green(p)
                val blue = Color.blue(p)
                val bin = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
                count[bin]++
                sumR[bin] += red.toLong()
                sumG[bin] += green.toLong()
                sumB[bin] += blue.toLong()
                total++
                x += step
            }
            y += step
        }
        if (total == 0) return null
        var best = 0
        for (i in 1 until 4096) if (count[i] > count[best]) best = i
        val n = count[best].coerceAtLeast(1)
        val color = Color.rgb((sumR[best] / n).toInt(), (sumG[best] / n).toInt(), (sumB[best] / n).toInt())
        return color to count[best].toFloat() / total
    }

    /**
     * A bubble added by the vision model comes with a rough box. The real letters are found inside it (the
     * pixels that differ from its background) and the block takes their exact box. No letters, or a busy
     * drawing instead of a plain background: the box is wrong and the block is dropped.
     */
    private fun snapToLetters(block: TranslationBlock, bitmap: Bitmap, s: Float): Boolean {
        val grow = 0.06f
        val l = ((block.x - block.width * grow) * s).roundToInt().coerceIn(0, bitmap.width - 1)
        val r = ((block.x + block.width * (1 + grow)) * s).roundToInt().coerceIn(0, bitmap.width - 1)
        val t = ((block.y - block.height * grow) * s).roundToInt().coerceIn(0, bitmap.height - 1)
        val b = ((block.y + block.height * (1 + grow)) * s).roundToInt().coerceIn(0, bitmap.height - 1)
        if (r - l < 4 || b - t < 4) return false
        val (bg, share) = dominant(bitmap, l, t, r, b) ?: return false
        // Text sits on a plain background; a box over busy art is a wrong guess
        if (share < 0.35f) return false
        val w = r - l
        val h = b - t
        val row = IntArray(w)
        val rows = IntArray(h)
        val cols = IntArray(w)
        var ink = 0
        for (y in t until b) {
            bitmap.getPixels(row, 0, w, l, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val d = abs(Color.red(p) - Color.red(bg)) + abs(Color.green(p) - Color.green(bg)) + abs(Color.blue(p) - Color.blue(bg))
                if (d > INK_DISTANCE) {
                    ink++
                    rows[y - t]++
                    cols[x]++
                }
            }
        }
        val total = w * h
        if (ink < total * 0.004f || ink > total * 0.45f) return false
        // Long straight runs are a bubble or panel border, not letters: left out of the box
        for (i in rows.indices) if (rows[i] > w * 0.6f) rows[i] = 0
        for (i in cols.indices) if (cols[i] > h * 0.6f) cols[i] = 0
        val minY = t + rows.indexOfFirst { it > 0 }.takeIf { it >= 0 }.let { it ?: return false }
        val maxY = t + rows.indexOfLast { it > 0 }
        val minX = l + cols.indexOfFirst { it > 0 }.takeIf { it >= 0 }.let { it ?: return false }
        val maxX = l + cols.indexOfLast { it > 0 }
        if (maxX <= minX || maxY <= minY) return false
        block.x = minX / s
        block.y = minY / s
        block.width = (maxX - minX + 1) / s
        block.height = (maxY - minY + 1) / s
        // Lines: runs of rows with ink, split at empty rows
        val lines = mutableListOf<LineBox>()
        var start = -1
        for (i in rows.indices) {
            val inked = rows[i] > 0
            if (inked && start < 0) start = i
            if ((!inked || i == rows.lastIndex) && start >= 0) {
                val end = if (inked) i else i - 1
                if (end - start >= 2) lines += LineBox(block.x, (t + start) / s, block.width, (end - start + 1) / s, bg or (0xFF shl 24))
                start = -1
            }
        }
        block.lines = lines.ifEmpty { listOf(LineBox(block.x, block.y, block.width, block.height, bg or (0xFF shl 24))) }
        val glyph = block.lines.map { it.h }.average().toFloat()
        block.symHeight = glyph
        block.symWidth = glyph * 0.6f
        block.bgColor = bg or (0xFF shl 24)
        block.textColor = if (luminance(bg) > 140) Color.BLACK else Color.WHITE
        return true
    }

    private fun luminance(color: Int) = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)

    /** Fills colours of blocks that still have none (kept for callers of the old API). */
    fun sampleColors(page: PageTranslation, decoded: Decoded) = prepare(page, decoded)

    /** Cuts the page into slices no taller than [MAX_TILE_RATIO] × width, so long webtoon strips stay legible. */
    fun tiles(decoded: Decoded): PageImage {
        val bitmap = decoded.bitmap
        val tileHeight = max(1, (bitmap.width * MAX_TILE_RATIO).roundToInt())
        val tiles = mutableListOf<PageImage.Tile>()
        var top = 0
        while (top < bitmap.height) {
            val height = min(tileHeight, bitmap.height - top)
            val slice = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            val jpeg = ByteArrayOutputStream().use {
                slice.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                it.toByteArray()
            }
            if (slice !== bitmap) slice.recycle()
            tiles += PageImage.Tile(jpeg, top / decoded.scale, height / decoded.scale)
            top += height
        }
        return PageImage(tiles)
    }

    private const val SAMPLES = 1600.0
    private const val INK_DISTANCE = 110
}
