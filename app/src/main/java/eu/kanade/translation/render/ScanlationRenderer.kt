package eu.kanade.translation.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.style.BubbleKind
import eu.kanade.translation.style.TranslationTypeface
import eu.kanade.translation.translator.TextTranslatorLanguage
import logcat.LogPriority
import mihon.app.di.appGraph
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANHWA scanlation studio: cleans and typesets the translation straight into the page's pixels, before the
 * reader shows the page. The reader then displays an ordinary image, so whatever it does with it (crop
 * borders, fit, zoom, split, webtoon strips) the text can never drift from its bubble.
 *
 * Cleaning works like a human cleaner: around every original line (+12 px on every side) the colour right
 * behind the letters is found; every group of non-background pixels that lies completely inside that area is
 * a letter and is painted over with the background (blended along each row, so gradients stay smooth). Groups
 * that reach the edge of the area are the bubble's outline or the drawing and are never touched.
 *
 * Typesetting works like a human typesetter: the cleaned lines are grown into the free background of the
 * bubble until an outline, the drawing or another bubble is met; the translation is laid out there with
 * StaticLayout, centred, right to left for Arabic, at the largest size whose lines fit without cutting a word,
 * with balanced lines and sizes evened out across the page.
 */
object ScanlationRenderer {

    data class Style(
        val typeface: TranslationTypeface,
        val sizeScale: Float,
        val minSize: Int,
        val outline: Boolean,
        val lineSpacing: Float,
        val skipSfx: Boolean,
        val latinOnly: Boolean,
    )

    private val cache = object : LruCache<String, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val fonts = ConcurrentHashMap<Int, Typeface>()

    fun style(context: Context): Style {
        val prefs = context.appGraph.translationPreferences
        val target = prefs.translateToLanguage().get()
        val lang = TextTranslatorLanguage.entries.firstOrNull { it.name.equals(target, true) }
        return Style(
            typeface = TranslationTypeface.entries.getOrNull(prefs.translationTypeface().get()) ?: TranslationTypeface.SMART,
            sizeScale = prefs.translationSizeScale().get(),
            minSize = prefs.translationMinSize().get(),
            outline = prefs.translationOutline().get(),
            lineSpacing = prefs.translationLineSpacing().get(),
            skipSfx = prefs.translationSkipSfx().get(),
            latinOnly = lang != null && lang.code !in RTL_SCRIPTS && lang.code !in WIDE_SCRIPTS,
        )
    }

    /** The page with its translation cleaned and drawn in, or null when there is nothing to draw. */
    fun bake(context: Context, source: BufferedSource, translation: PageTranslation): BufferedSource? {
        if (translation.blocks.none { it.translation.isNotBlank() }) return null
        val style = style(context)
        val bytes = source.peek().readByteArray()
        val key = "${bytes.size}|${bytes.contentHashCodeSample()}|${translation.imgWidth}|${translation.blocks.hashCode()}|$style"
        val out = cache.get(key) ?: runCatching { render(context, bytes, translation, style) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Could not draw the translation into the page" } }
            .getOrNull()
            ?.also { cache.put(key, it) }
            ?: return null
        return Buffer().write(out)
    }

    private fun ByteArray.contentHashCodeSample(): Int {
        var h = 1
        val step = max(1, size / 4096)
        var i = 0
        while (i < size) {
            h = 31 * h + this[i]
            i += step
        }
        return h
    }

    private fun render(context: Context, bytes: ByteArray, page: PageTranslation, style: Style): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
        val pixels = (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample)
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = sample
            // Full colour for normal pages, half the memory for very long webtoon strips
            inPreferredConfig = if (pixels <= ARGB_PIXELS) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            val scale = bitmap.width / (if (page.imgWidth > 0f) page.imgWidth else bounds.outWidth.toFloat())
            Studio(context, bitmap, scale, style).draw(page)
            return ByteArrayOutputStream(bytes.size + bytes.size / 4).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class Studio(private val context: Context, private val bitmap: Bitmap, private val s: Float, private val style: Style) {

        private val canvas = Canvas(bitmap)
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        /** A bubble after cleaning: where its lines were, the colour behind them, and whether it is plain. */
        private class Cleaned(val block: TranslationBlock, val kind: BubbleKind, val union: RectF, val bg: Int, val plain: Boolean)

        private class Placement(val cleaned: Cleaned, val area: RectF, val paint: TextPaint, val fit: Float)

        fun draw(page: PageTranslation) {
            // 1. Cleaning, every bubble first, so free space is measured on the cleaned page
            val cleaned = page.blocks.mapNotNull { block ->
                if (block.translation.isBlank()) return@mapNotNull null
                val kind = BubbleKind.parse(block.kind) ?: BubbleKind.guess(block.text, block.translation)
                if (kind == BubbleKind.SFX && style.skipSfx) return@mapNotNull null
                clean(block, kind)
            }
            // 2. Room for every bubble's text, stopping at outlines, the drawing and the other bubbles
            val unions = cleaned.map { it.union }
            val sets = cleaned.mapIndexed { i, c ->
                val others = unions.filterIndexed { j, _ -> j != i }
                val room = if (c.plain) grow(c.union, c.bg, others) else RectF(c.union)
                val area = edited(c.block, shrink(room, 0.04f))
                val paint = paintFor(c)
                Placement(c, area, paint, fit(c.block.translation, paint, area))
            }
            // 3. Sizes evened out across the page, then drawn
            val fits = sets.map { it.fit }.filter { it > 0f }.sorted()
            val median = fits.getOrNull(fits.size / 2) ?: 0f
            sets.forEach { set ->
                var size = if (median > 0f) min(set.fit, median * 1.35f) else set.fit
                // Never larger than what fits; only the reader's own A+ may go past it
                size = min(size * style.sizeScale, set.fit)
                size = (size * set.cleaned.block.userFont).coerceAtLeast(MIN_TEXT_PX * s)
                typeset(set, size)
            }
        }

        // ---------------------------------------------------------------- cleaning

        private fun clean(block: TranslationBlock, kind: BubbleKind): Cleaned {
            val rects = block.lineBoxes.map { line ->
                val padX = max(PAD, line.h * 0.3f)
                val padY = max(PAD, line.h * 0.25f)
                RectF((line.x - padX) * s, (line.y - padY) * s, (line.right + padX) * s, (line.bottom + padY) * s)
            }
            val bounds = RectF(rects.first())
            rects.forEach { bounds.union(it) }
            clamp(bounds)
            // Sound effects are part of the drawing: they are only outlined, never erased
            if (kind == BubbleKind.SFX) return Cleaned(block, kind, bounds, dominantOf(bounds)?.first ?: Color.WHITE, false)
            val results = rects.map { cleanArea(clamp(RectF(it))) }
            // One more pass over the whole bubble (a little taller): letters joined across two lines, and a
            // line the OCR did not list, are inside it and are cleaned too
            val lineH = block.lineBoxes.map { it.h }.average().toFloat() * s
            cleanArea(clamp(RectF(bounds.left, bounds.top - lineH * 0.8f, bounds.right, bounds.bottom + lineH * 0.8f)), boxWhenBusy = false)
            val plain = results.filterNotNull().filter { it.second }
            val bg = plain.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
                ?: results.filterNotNull().firstOrNull()?.first
                ?: Color.WHITE
            return Cleaned(block, kind, bounds, bg, plain.isNotEmpty())
        }

        /**
         * Cleans one area: the background is its most common colour; every group of other pixels that does
         * not reach the edge of the area is a letter and is painted over. Over a busy drawing (no plain
         * background) a soft patch of the main colour is used instead. Returns (background, plain).
         */
        private fun cleanArea(area: RectF, boxWhenBusy: Boolean = true): Pair<Int, Boolean>? {
            val l = area.left.roundToInt().coerceIn(0, bitmap.width - 1)
            val t = area.top.roundToInt().coerceIn(0, bitmap.height - 1)
            val r = area.right.roundToInt().coerceIn(0, bitmap.width)
            val b = area.bottom.roundToInt().coerceIn(0, bitmap.height)
            val w = r - l
            val h = b - t
            if (w < 3 || h < 3) return null
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, l, t, w, h)
            val (bg, share) = dominant(px) ?: return null
            if (share < PLAIN_SHARE) {
                if (boxWhenBusy) {
                    boxPaint.color = Color.argb(236, Color.red(bg), Color.green(bg), Color.blue(bg))
                    val radius = min(w, h) * 0.3f
                    canvas.drawRoundRect(RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()), radius, radius, boxPaint)
                }
                return bg to false
            }
            val n = w * h
            val ink = BooleanArray(n) { distance(px[it], bg) > INK }
            val seen = BooleanArray(n)
            val erase = BooleanArray(n)
            val keep = BooleanArray(n)
            val queue = IntArray(n)
            for (start in 0 until n) {
                if (!ink[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var touches = false
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % w
                    val y = i / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                    if (x > 0 && ink[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                    if (x < w - 1 && ink[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                    if (y > 0 && ink[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                    if (y < h - 1 && ink[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
                }
                // A group that reaches the edge is the outline or the drawing: left exactly as it is
                for (k in 0 until tail) if (touches) keep[queue[k]] = true else erase[queue[k]] = true
            }
            // The soft edge of each letter goes too (2 px around it), never into what is kept
            val grown = erase.copyOf()
            for (i in 0 until n) {
                if (!erase[i]) continue
                val x = i % w
                val y = i / w
                for (dy in -GROW..GROW) for (dx in -GROW..GROW) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (!keep[j] && distance(px[j], bg) > HALO) grown[j] = true
                }
            }
            // Paint over: along each row, blended between the background on both sides (keeps gradients)
            var any = false
            for (y in 0 until h) {
                var x = 0
                while (x < w) {
                    if (!grown[y * w + x]) {
                        x++
                        continue
                    }
                    val runStart = x
                    while (x < w && grown[y * w + x]) x++
                    val left = if (runStart > 0) px[y * w + runStart - 1].takeIf { distance(it, bg) <= INK } ?: bg else bg
                    val right = if (x < w) px[y * w + x].takeIf { distance(it, bg) <= INK } ?: bg else bg
                    val len = x - runStart
                    for (k in 0 until len) {
                        val f = (k + 1f) / (len + 1f)
                        px[y * w + runStart + k] = mix(left, right, f)
                    }
                    any = true
                }
            }
            if (any) bitmap.setPixels(px, 0, w, l, t, w, h)
            return bg to true
        }

        private fun dominantOf(area: RectF): Pair<Int, Float>? {
            val l = area.left.roundToInt().coerceIn(0, bitmap.width - 1)
            val t = area.top.roundToInt().coerceIn(0, bitmap.height - 1)
            val r = area.right.roundToInt().coerceIn(0, bitmap.width)
            val b = area.bottom.roundToInt().coerceIn(0, bitmap.height)
            if (r - l < 2 || b - t < 2) return null
            val px = IntArray((r - l) * (b - t))
            bitmap.getPixels(px, 0, r - l, l, t, r - l, b - t)
            return dominant(px)
        }

        // ---------------------------------------------------------------- typesetting

        /** Grows [start] into free background: each side stops at an outline, the drawing or another bubble. */
        private fun grow(start: RectF, bg: Int, others: List<RectF>): RectF {
            val rect = RectF(start)
            val maxX = start.width() * 0.4f
            val maxY = start.height() * 0.45f
            val step = max(2f, min(start.width(), start.height()) * 0.04f)
            var left = true
            var right = true
            var top = true
            var bottom = true
            while (left || right || top || bottom) {
                if (left) left = tryGrow(rect, bg, others, dl = -step, limit = start.left - maxX)
                if (right) right = tryGrow(rect, bg, others, dr = step, limit = start.right + maxX)
                if (top) top = tryGrow(rect, bg, others, dt = -step, limit = start.top - maxY)
                if (bottom) bottom = tryGrow(rect, bg, others, db = step, limit = start.bottom + maxY)
            }
            return rect
        }

        private fun tryGrow(rect: RectF, bg: Int, others: List<RectF>, dl: Float = 0f, dt: Float = 0f, dr: Float = 0f, db: Float = 0f, limit: Float): Boolean {
            val next = RectF(rect.left + dl, rect.top + dt, rect.right + dr, rect.bottom + db)
            if (next.left < 0f || next.top < 0f || next.right > bitmap.width || next.bottom > bitmap.height) return false
            if (dl != 0f && next.left < limit || dr != 0f && next.right > limit) return false
            if (dt != 0f && next.top < limit || db != 0f && next.bottom > limit) return false
            if (others.any { RectF.intersects(it, next) }) return false
            // The new strip must be clean background
            val strip = when {
                dl != 0f -> RectF(next.left, next.top, rect.left, next.bottom)
                dr != 0f -> RectF(rect.right, next.top, next.right, next.bottom)
                dt != 0f -> RectF(next.left, next.top, next.right, rect.top)
                else -> RectF(next.left, rect.bottom, next.right, next.bottom)
            }
            if (!isClean(strip, bg)) return false
            rect.set(next)
            return true
        }

        private fun isClean(strip: RectF, bg: Int): Boolean {
            val l = strip.left.roundToInt().coerceIn(0, bitmap.width - 1)
            val t = strip.top.roundToInt().coerceIn(0, bitmap.height - 1)
            val r = strip.right.roundToInt().coerceIn(l + 1, bitmap.width)
            val b = strip.bottom.roundToInt().coerceIn(t + 1, bitmap.height)
            val px = IntArray((r - l) * (b - t))
            bitmap.getPixels(px, 0, r - l, l, t, r - l, b - t)
            val dirty = px.count { distance(it, bg) > INK }
            return dirty <= px.size * 0.01f
        }

        private fun paintFor(c: Cleaned): TextPaint {
            val (res, weight) = style.typeface.androidFont(c.kind, style.latinOnly)
            val font = fonts.getOrPut(res) { runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.DEFAULT_BOLD }
            return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = font
                if (weight > 0) setFontVariationSettings("'wght' $weight")
                color = textColorOn(c.bg)
            }
        }

        /** Largest text size (bitmap px) whose lines fit [area] without cutting a word. */
        private fun fit(text: String, paint: TextPaint, area: RectF): Float {
            val maxW = area.width().toInt()
            if (maxW < 4 || area.height() < 4f) return 0f
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var low = 4f * s
            var high = area.height()
            var best = 0f
            repeat(14) {
                val mid = (low + high) / 2f
                paint.textSize = mid
                val widest = words.maxOfOrNull { paint.measureText(it) } ?: 0f
                val fits = widest <= maxW && layout(text, paint, maxW).height <= area.height()
                if (fits) {
                    best = mid
                    low = mid
                } else {
                    high = mid
                }
            }
            return best
        }

        private fun typeset(set: Placement, size: Float) {
            val text = set.cleaned.block.translation
            val paint = set.paint
            val area = set.area
            paint.textSize = size
            val maxW = area.width().toInt().coerceAtLeast(8)
            var layout = layout(text, paint, maxW)
            // Balanced lines: the narrowest width that keeps the same number of lines
            if (layout.lineCount > 1) {
                var low = (maxW * 0.45f).toInt()
                var high = maxW
                repeat(8) {
                    val mid = (low + high) / 2
                    val candidate = layout(text, paint, mid.coerceAtLeast(8))
                    if (candidate.lineCount <= layout.lineCount && candidate.height <= max(area.height(), layout.height.toFloat())) {
                        layout = candidate
                        high = mid
                    } else {
                        low = mid + 1
                    }
                }
            }
            val x = area.centerX() - layout.width / 2f
            val y = area.centerY() - layout.height / 2f
            canvas.save()
            canvas.translate(x, y)
            val plain = set.cleaned.plain
            val stroke = when {
                set.cleaned.kind == BubbleKind.SFX || !plain -> size * 0.16f
                style.outline -> size * 0.07f
                else -> 0f
            }
            if (stroke > 0f) {
                val fill = paint.color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (plain) set.cleaned.bg else outlineFor(fill)
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = fill
            }
            layout.draw(canvas)
            canvas.restore()
        }

        private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setLineSpacing(0f, style.lineSpacing.coerceIn(0.8f, 1.8f))
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

        /** The reader's move and resize of this bubble (page pixels → bitmap pixels). */
        private fun edited(block: TranslationBlock, area: RectF): RectF {
            if (!block.edited) return area
            val cx = area.centerX() + block.userDx * s
            val cy = area.centerY() + block.userDy * s
            val w = area.width() * block.userScale / 2f
            val h = area.height() * block.userScale / 2f
            return clamp(RectF(cx - w, cy - h, cx + w, cy + h))
        }

        private fun shrink(rect: RectF, f: Float) = RectF(rect).apply { inset(rect.width() * f, rect.height() * f) }

        private fun clamp(rect: RectF): RectF = rect.apply {
            left = left.coerceIn(0f, bitmap.width.toFloat())
            right = right.coerceIn(0f, bitmap.width.toFloat())
            top = top.coerceIn(0f, bitmap.height.toFloat())
            bottom = bottom.coerceIn(0f, bitmap.height.toFloat())
        }
    }

    /** Most common colour (4 bits per channel buckets, averaged) and the share of pixels it has. */
    private fun dominant(px: IntArray): Pair<Int, Float>? {
        if (px.isEmpty()) return null
        val count = IntArray(4096)
        val sumR = LongArray(4096)
        val sumG = LongArray(4096)
        val sumB = LongArray(4096)
        val step = max(1, px.size / 6000)
        var total = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val bin = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            count[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
            total++
            i += step
        }
        var best = 0
        for (k in 1 until 4096) if (count[k] > count[best]) best = k
        // Neighbouring buckets belong to the same flat colour (noise, JPEG)
        val n = count[best].coerceAtLeast(1)
        val color = Color.rgb((sumR[best] / n).toInt(), (sumG[best] / n).toInt(), (sumB[best] / n).toInt())
        var near = 0
        i = 0
        while (i < px.size) {
            if (distance(px[i], color) <= INK) near++
            i += step
        }
        return color to near.toFloat() / total
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun mix(a: Int, b: Int, f: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * f).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * f).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).roundToInt(),
    )

    private fun luminance(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    private fun textColorOn(bg: Int): Int = if (luminance(bg) > 140) Color.rgb(17, 17, 17) else Color.WHITE

    private fun outlineFor(text: Int): Int = if (luminance(text) > 140) Color.argb(230, 0, 0, 0) else Color.argb(240, 255, 255, 255)

    private const val CACHE_BYTES = 48 * 1024 * 1024
    private const val MAX_PIXELS = 30_000_000L
    private const val ARGB_PIXELS = 14_000_000L
    private const val JPEG_QUALITY = 92
    private const val PAD = 12f
    private const val PLAIN_SHARE = 0.4f
    private const val INK = 70
    private const val HALO = 18
    private const val GROW = 2
    private const val MIN_TEXT_PX = 6f
    private val RTL_SCRIPTS = setOf("ar", "fa", "ur", "he", "iw")
    private val WIDE_SCRIPTS = setOf("ja", "zh", "ko", "th", "hi", "ru", "uk", "el", "bg")
}
