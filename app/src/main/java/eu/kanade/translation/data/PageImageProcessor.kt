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
    private const val MAX_SAMPLE = 16

    fun decode(open: () -> InputStream): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
        // Very tall webtoon strips: also keep the whole bitmap within a safe memory budget
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        // MANHWA: when the app is short of memory, a smaller copy is read instead of running out
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        while (sample < MAX_SAMPLE && bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) * 2 > free / 3) sample *= 2
        // Out of memory anyway: once more at half the size before giving up on the page
        repeat(2) { attempt ->
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                // Half the memory of ARGB: tall webtoon strips keep more detail for the OCR
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            try {
                val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
                return Decoded(bitmap, bitmap.width.toFloat() / bounds.outWidth)
            } catch (e: OutOfMemoryError) {
                if (attempt == 1) return null
                System.gc()
                sample *= 2
            }
        }
        return null
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
                // MANHWA: the letters' own colour and outline, so a red shout or a blue system line stays red or blue
                inkColors(block, bitmap, s, main)
            }
        }
    }

    /**
     * MANHWA: the colours of the original letters. Pixels clearly unlike the background are the ink; its two main
     * colours are the fill and, when the second is common enough and sits on the edge of the letters (touching the
     * background), the outline. A colour that would not read on the cleaned bubble is never used.
     */
    private fun inkColors(block: TranslationBlock, bitmap: Bitmap, s: Float, bg: Int) {
        val count = IntArray(4096)
        val edge = IntArray(4096)
        val sumR = LongArray(4096)
        val sumG = LongArray(4096)
        val sumB = LongArray(4096)
        var ink = 0
        for (line in block.lineBoxes) {
            val l = (line.x * s).roundToInt().coerceIn(0, bitmap.width - 1)
            val r = (line.right * s).roundToInt().coerceIn(0, bitmap.width)
            val t = (line.y * s).roundToInt().coerceIn(0, bitmap.height - 1)
            val b = (line.bottom * s).roundToInt().coerceIn(0, bitmap.height)
            val w = r - l
            val h = b - t
            if (w < 3 || h < 3 || w.toLong() * h > INK_MAX_PIXELS) continue
            val lineBg = if (line.bg != 0) line.bg else bg
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, l, t, w, h)
            val isBg = BooleanArray(px.size) { colorDistance(px[it], lineBg) <= LETTER_DISTANCE }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (isBg[i]) continue
                    val p = px[i]
                    val bin = (Color.red(p) shr 4 shl 8) or (Color.green(p) shr 4 shl 4) or (Color.blue(p) shr 4)
                    count[bin]++
                    sumR[bin] += Color.red(p).toLong()
                    sumG[bin] += Color.green(p).toLong()
                    sumB[bin] += Color.blue(p).toLong()
                    ink++
                    // On the edge of the letters: a background pixel right next to it
                    val touches = (x > 0 && isBg[i - 1]) || (x < w - 1 && isBg[i + 1]) || (y > 0 && isBg[i - w]) || (y < h - 1 && isBg[i + w])
                    if (touches) edge[bin]++
                }
            }
        }
        if (ink < INK_MIN_PIXELS) return
        fun colorOf(bin: Int): Int {
            val n = count[bin].coerceAtLeast(1)
            return Color.rgb((sumR[bin] / n).toInt(), (sumG[bin] / n).toInt(), (sumB[bin] / n).toInt())
        }
        val first = count.indices.maxBy { count[it] }
        val firstColor = colorOf(first)
        // The second colour: common, and clearly another colour than the first
        val second = count.indices
            .filter { it != first && count[it] > 0 && colorDistance(colorOf(it), firstColor) > OUTLINE_DISTANCE }
            .maxByOrNull { count[it] }
            ?.takeIf { count[it] >= ink * OUTLINE_SHARE }
        var fill = firstColor
        var outline = 0
        if (second != null) {
            val secondColor = colorOf(second)
            val firstEdge = edge[first].toFloat() / count[first]
            val secondEdge = edge[second].toFloat() / count[second]
            // The outline is the one lying on the letters' edge
            if (secondEdge > firstEdge) {
                outline = secondColor
            } else {
                fill = secondColor
                outline = firstColor
            }
        }
        // Read on the bubble once it is cleaned: otherwise the plain black or white stays
        if (contrast(fill, bg) >= MIN_TEXT_CONTRAST) block.textColor = fill
        if (outline != 0 && contrast(outline, block.textColor) >= MIN_OUTLINE_CONTRAST) block.strokeColor = outline
    }

    /** MANHWA: [block]'s area of the decoded page as a JPEG, a little larger than the text, to be read again. */
    fun crop(decoded: Decoded, block: TranslationBlock): ByteArray? {
        val bitmap = decoded.bitmap
        val s = decoded.scale
        val padX = block.width * CROP_PAD
        val padY = block.height * CROP_PAD
        val l = ((block.x - padX) * s).roundToInt().coerceIn(0, bitmap.width - 1)
        val t = ((block.y - padY) * s).roundToInt().coerceIn(0, bitmap.height - 1)
        val r = ((block.x + block.width + padX) * s).roundToInt().coerceIn(l + 1, bitmap.width)
        val b = ((block.y + block.height + padY) * s).roundToInt().coerceIn(t + 1, bitmap.height)
        if (r - l < 8 || b - t < 8) return null
        return runCatching {
            val part = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
            // Small text is enlarged so the model reads every stroke
            val scaled = if (part.height < CROP_MIN_HEIGHT) {
                val f = CROP_MIN_HEIGHT.toFloat() / part.height
                Bitmap.createScaledBitmap(part, (part.width * f).roundToInt(), CROP_MIN_HEIGHT, true).also { if (it !== part) part.recycle() }
            } else {
                part
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, CROP_QUALITY, out)
                if (scaled !== bitmap) scaled.recycle()
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    /** WCAG contrast ratio of two colours (1 to 21). */
    fun contrast(a: Int, b: Int): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return ((max(la, lb) + 0.05) / (min(la, lb) + 0.05)).toFloat()
    }

    private fun relativeLuminance(c: Int): Double {
        fun channel(v: Int): Double {
            val x = v / 255.0
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
    }

    private const val LETTER_DISTANCE = 90
    private const val OUTLINE_DISTANCE = 120
    private const val OUTLINE_SHARE = 0.15f
    private const val INK_MIN_PIXELS = 40
    private const val INK_MAX_PIXELS = 400_000L
    const val MIN_TEXT_CONTRAST = 3f
    private const val MIN_OUTLINE_CONTRAST = 2f
    private const val CROP_PAD = 0.12f
    private const val CROP_MIN_HEIGHT = 64
    private const val CROP_QUALITY = 90

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
