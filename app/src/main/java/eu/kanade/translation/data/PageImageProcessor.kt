package eu.kanade.translation.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    private const val TARGET_WIDTH = 1024
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
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
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
}
