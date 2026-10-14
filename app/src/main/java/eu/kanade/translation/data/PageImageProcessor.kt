package eu.kanade.translation.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
 * Image work done on each page while translating: erasing colours for every bubble and, for vision
 * engines, readable slices of the page. Pages are decoded once, downscaled, and never kept in memory.
 */
object PageImageProcessor {

    private const val TARGET_WIDTH = 1440
    private const val MAX_PIXELS = 16_000_000L
    private const val MAX_TILE_RATIO = 1.6f
    private const val JPEG_QUALITY = 85

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

    /** Fills [TranslationBlock.bgColor] and [TranslationBlock.textColor] for blocks that don't have them yet. */
    fun sampleColors(page: PageTranslation, decoded: Decoded, offsetY: Float = 0f) {
        val bitmap = decoded.bitmap
        for (block in page.blocks) {
            if (block.bgColor != 0) continue
            val left = (block.x - block.symWidth) * decoded.scale
            val top = (block.y - offsetY - block.symHeight / 2) * decoded.scale
            val right = (block.x + block.width + block.symWidth) * decoded.scale
            val bottom = (block.y - offsetY + block.height + block.symHeight / 2) * decoded.scale
            if (bottom < 0 || top > bitmap.height) continue
            val colors = perimeter(left, top, right, bottom, bitmap)
            if (colors.isEmpty()) continue
            val r = colors.map { Color.red(it) }.sorted()[colors.size / 2]
            val g = colors.map { Color.green(it) }.sorted()[colors.size / 2]
            val b = colors.map { Color.blue(it) }.sorted()[colors.size / 2]
            block.bgColor = Color.argb(0xF2, r, g, b)
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            block.textColor = if (luminance > 140) Color.BLACK else Color.WHITE
        }
    }

    private fun perimeter(left: Float, top: Float, right: Float, bottom: Float, bitmap: Bitmap): List<Int> {
        val x0 = left.roundToInt().coerceIn(0, bitmap.width - 1)
        val x1 = right.roundToInt().coerceIn(0, bitmap.width - 1)
        val y0 = top.roundToInt().coerceIn(0, bitmap.height - 1)
        val y1 = bottom.roundToInt().coerceIn(0, bitmap.height - 1)
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val steps = 24
        return buildList {
            for (i in 0..steps) {
                val x = x0 + (x1 - x0) * i / steps
                val y = y0 + (y1 - y0) * i / steps
                add(bitmap.getPixel(x, y0))
                add(bitmap.getPixel(x, y1))
                add(bitmap.getPixel(x0, y))
                add(bitmap.getPixel(x1, y))
            }
        }
    }

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

    /** Samples colours for blocks added by the vision model, using the slices that were sent to it. */
    fun sampleColorsFromTiles(page: PageTranslation, image: PageImage) {
        if (page.blocks.none { it.bgColor == 0 }) return
        for (tile in image.tiles) {
            val bitmap = BitmapFactory.decodeByteArray(tile.jpeg, 0, tile.jpeg.size) ?: continue
            sampleColors(page, Decoded(bitmap, bitmap.height / tile.height), offsetY = tile.top)
            bitmap.recycle()
        }
    }

    /**
     * Finds the real speech bubble around every block from the page pixels, so the translation is laid out
     * and erased over the whole bubble instead of the tight OCR box. Blocks that sit in the same bubble are
     * merged (the OCR often splits a bubble), and blocks placed by the vision model are snapped onto the
     * nearest free bubble. Boxes are in page pixels; [decoded] may be downscaled.
     */
    fun layoutBubbles(page: PageTranslation, decoded: Decoded) {
        val bitmap = decoded.bitmap
        val s = decoded.scale
        for (block in page.blocks) {
            if (block.needsSnap || block.hasBubble) continue
            if (block.bgColor == 0) continue
            fill(bitmap, block.x * s, block.y * s, (block.x + block.width) * s, (block.y + block.height) * s, block.bgColor)
                ?.let { block.setBubble(it, s) }
        }
        mergeSameBubble(page)
        val snapped = page.blocks.filter { it.needsSnap }
        for (block in snapped) {
            if (!snap(block, page, bitmap, s)) page.blocks.remove(block)
            block.needsSnap = false
        }
    }

    private class Found(val left: Float, val top: Float, val right: Float, val bottom: Float, val round: Boolean)

    private fun TranslationBlock.setBubble(found: Found, s: Float) {
        bubbleX = found.left / s
        bubbleY = found.top / s
        bubbleW = (found.right - found.left) / s
        bubbleH = (found.bottom - found.top) / s
        bubbleRound = found.round
    }

    /**
     * Flood fill from the text box over pixels close to [bg] (letters inside the box count as inside).
     * Returns the bubble bounds in bitmap pixels, or null when the fill leaks out (text over art).
     */
    private fun fill(bitmap: Bitmap, l: Float, t: Float, r: Float, b: Float, bg: Int): Found? {
        val bw = r - l
        val bh = b - t
        if (bw < 2 || bh < 2) return null
        val pad = max(bw, bh) * 1.6f + 8f
        val rl = max(0, (l - pad).toInt())
        val rt = max(0, (t - pad).toInt())
        val rr = min(bitmap.width, (r + pad).toInt())
        val rb = min(bitmap.height, (b + pad).toInt())
        val rw = rr - rl
        val rh = rb - rt
        if (rw < 4 || rh < 4) return null
        val step = max(1, kotlin.math.ceil(kotlin.math.sqrt(rw.toDouble() * rh / GRID_CELLS)).toInt())
        val gw = rw / step
        val gh = rh / step
        if (gw < 3 || gh < 3) return null
        val passable = BooleanArray(gw * gh)
        val row = IntArray(rw)
        val br = Color.red(bg)
        val bgG = Color.green(bg)
        val bb = Color.blue(bg)
        for (gy in 0 until gh) {
            val y = rt + gy * step
            bitmap.getPixels(row, 0, rw, rl, y, rw, 1)
            for (gx in 0 until gw) {
                val x = rl + gx * step
                val inBox = x >= l && x <= r && y >= t && y <= b
                val p = row[gx * step]
                val d = abs(Color.red(p) - br) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bb)
                passable[gy * gw + gx] = inBox || d < COLOR_TOLERANCE
            }
        }
        val seen = BooleanArray(gw * gh)
        val queue = IntArray(gw * gh)
        var head = 0
        var tail = 0
        val sx0 = ((l - rl) / step).toInt().coerceIn(0, gw - 1)
        val sx1 = ((r - rl) / step).toInt().coerceIn(0, gw - 1)
        val sy0 = ((t - rt) / step).toInt().coerceIn(0, gh - 1)
        val sy1 = ((b - rt) / step).toInt().coerceIn(0, gh - 1)
        for (gy in sy0..sy1) for (gx in sx0..sx1) {
            val i = gy * gw + gx
            if (passable[i] && !seen[i]) {
                seen[i] = true
                queue[tail++] = i
            }
        }
        val rowCount = IntArray(gh)
        val colCount = IntArray(gw)
        var filled = 0
        while (head < tail) {
            val i = queue[head++]
            val gx = i % gw
            val gy = i / gw
            // Reaching the edge of the search area (not of the page) means there is no closed bubble
            if ((gx == 0 && rl > 0) || (gx == gw - 1 && rr < bitmap.width) ||
                (gy == 0 && rt > 0) || (gy == gh - 1 && rb < bitmap.height)
            ) {
                return null
            }
            filled++
            rowCount[gy]++
            colCount[gx]++
            if (gx > 0) visit(i - 1, passable, seen, queue, tail).also { if (it) tail++ }
            if (gx < gw - 1) visit(i + 1, passable, seen, queue, tail).also { if (it) tail++ }
            if (gy > 0) visit(i - gw, passable, seen, queue, tail).also { if (it) tail++ }
            if (gy < gh - 1) visit(i + gw, passable, seen, queue, tail).also { if (it) tail++ }
        }
        if (filled == 0) return null
        // Trim the tail of the bubble and thin leaks: keep rows and columns that are well filled
        val maxRow = rowCount.max()
        val maxCol = colCount.max()
        val top = rowCount.indexOfFirst { it >= maxRow * TRIM }
        val bottom = rowCount.indexOfLast { it >= maxRow * TRIM }
        val left = colCount.indexOfFirst { it >= maxCol * TRIM }
        val right = colCount.indexOfLast { it >= maxCol * TRIM }
        if (top < 0 || left < 0 || bottom <= top || right <= left) return null
        val areaCells = (right - left + 1) * (bottom - top + 1)
        // A huge plain area around a small text is the panel background, not a bubble
        if (areaCells * step * step > bw * bh * MAX_BUBBLE_TO_TEXT) return null
        var inside = 0
        for (gy in top..bottom) for (gx in left..right) if (seen[gy * gw + gx]) inside++
        val ratio = inside.toFloat() / areaCells
        return Found(
            left = (rl + left * step).toFloat().coerceAtMost(l),
            top = (rt + top * step).toFloat().coerceAtMost(t),
            right = (rl + (right + 1) * step).toFloat().coerceAtLeast(r),
            bottom = (rt + (bottom + 1) * step).toFloat().coerceAtLeast(b),
            round = ratio < 0.9f,
        )
    }

    private fun visit(i: Int, passable: BooleanArray, seen: BooleanArray, queue: IntArray, tail: Int): Boolean {
        if (seen[i] || !passable[i]) return false
        seen[i] = true
        queue[tail] = i
        return true
    }

    /** Blocks whose centre falls in the same bubble are one bubble the OCR cut in pieces. */
    private fun mergeSameBubble(page: PageTranslation) {
        val blocks = page.blocks.sortedWith(compareBy({ it.y }, { it.x })).toMutableList()
        val out = mutableListOf<TranslationBlock>()
        for (block in blocks) {
            val host = out.firstOrNull { it.hasBubble && block.hasBubble && it.sameBubble(block) }
            if (host == null) {
                out += block
                continue
            }
            val left = min(host.x, block.x)
            val top = min(host.y, block.y)
            host.width = max(host.x + host.width, block.x + block.width) - left
            host.height = max(host.y + host.height, block.y + block.height) - top
            host.x = left
            host.y = top
            host.text = host.text + "\n" + block.text
            if (block.translation.isNotBlank()) host.translation = (host.translation + " " + block.translation).trim()
            host.bubbleX = min(host.bubbleX, block.bubbleX)
            host.bubbleY = min(host.bubbleY, block.bubbleY)
            host.bubbleW = max(host.bubbleX + host.bubbleW, block.bubbleX + block.bubbleW) - host.bubbleX
            host.bubbleH = max(host.bubbleY + host.bubbleH, block.bubbleY + block.bubbleH) - host.bubbleY
        }
        page.blocks = out
    }

    private fun TranslationBlock.sameBubble(other: TranslationBlock): Boolean {
        val cx = other.x + other.width / 2
        val cy = other.y + other.height / 2
        val inside = cx in bubbleX..(bubbleX + bubbleW) && cy in bubbleY..(bubbleY + bubbleH)
        return inside && iou(other) > 0.5f
    }

    private fun TranslationBlock.iou(other: TranslationBlock): Float {
        val l = max(bubbleX, other.bubbleX)
        val t = max(bubbleY, other.bubbleY)
        val r = min(bubbleX + bubbleW, other.bubbleX + other.bubbleW)
        val b = min(bubbleY + bubbleH, other.bubbleY + other.bubbleH)
        if (r <= l || b <= t) return 0f
        val inter = (r - l) * (b - t)
        return inter / (bubbleW * bubbleH + other.bubbleW * other.bubbleH - inter)
    }

    /**
     * Moves a block placed from the vision model's rough box onto the nearest bubble that has no other
     * translation, looking around the box. Returns false when it duplicates a bubble already translated.
     */
    private fun snap(block: TranslationBlock, page: PageTranslation, bitmap: Bitmap, s: Float): Boolean {
        val w = block.width * s
        val h = block.height * s
        val x0 = block.x * s
        val y0 = block.y * s
        val others = page.blocks.filter { it !== block && it.hasBubble }
        val shifts = listOf(0f to 0f, 0f to -1f, 0f to 1f, 0f to -2f, 0f to 2f, -1f to 0f, 1f to 0f)
        for ((dx, dy) in shifts) {
            val l = x0 + dx * w
            val t = y0 + dy * h
            if (l + w < 0 || t + h < 0 || l > bitmap.width || t > bitmap.height) continue
            val colors = perimeter(l, t, l + w, t + h, bitmap)
            if (colors.isEmpty()) continue
            val bg = median(colors)
            val found = fill(bitmap, l, t, l + w, t + h, bg) ?: continue
            val probe = block.copy().apply { setBubble(found, s) }
            val taken = others.firstOrNull { it.iou(probe) > 0.4f }
            if (taken != null) {
                // Right on a bubble that already has its translation: a duplicate of it
                if (dx == 0f && dy == 0f && taken.translation.isNotBlank()) return false
                continue
            }
            block.setBubble(found, s)
            block.bgColor = bg or (0xFF shl 24)
            block.textColor = if (luminance(bg) > 140) Color.BLACK else Color.WHITE
            // The text area becomes the middle of the bubble
            block.x = block.bubbleX + block.bubbleW * 0.15f
            block.y = block.bubbleY + block.bubbleH * 0.2f
            block.width = block.bubbleW * 0.7f
            block.height = block.bubbleH * 0.6f
            return true
        }
        // No bubble around: text over art, keep the model's box
        return true
    }

    private fun median(colors: List<Int>): Int {
        val r = colors.map { Color.red(it) }.sorted()[colors.size / 2]
        val g = colors.map { Color.green(it) }.sorted()[colors.size / 2]
        val b = colors.map { Color.blue(it) }.sorted()[colors.size / 2]
        return Color.rgb(r, g, b)
    }

    private fun luminance(color: Int) = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)

    private const val GRID_CELLS = 160_000.0
    private const val COLOR_TOLERANCE = 80
    private const val TRIM = 0.3f
    private const val MAX_BUBBLE_TO_TEXT = 40f
}
