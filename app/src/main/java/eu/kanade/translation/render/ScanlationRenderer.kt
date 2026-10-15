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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * One page at a time: the webtoon viewer prepares several tall pages at once, and drawing them all
     * together runs out of memory (which used to leave a page untranslated).
     */
    private val gate = Mutex()

    suspend fun bake(context: Context, source: BufferedSource, translation: PageTranslation): BufferedSource? = gate.withLock {
        bakeNow(context, source, translation)
    }

    private fun bakeNow(context: Context, source: BufferedSource, translation: PageTranslation): BufferedSource? {
        if (translation.blocks.none { it.translation.isNotBlank() }) return null
        val style = style(context)
        val bytes = source.peek().readByteArray()
        val key = "${bytes.size}|${bytes.contentHashCodeSample()}|${translation.imgWidth}|${translation.blocks.hashCode()}|$style"
        val out = cache.get(key) ?: renderSafely(context, bytes, translation, style)
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

    /** Tries full quality first; on low memory, again at half and then quarter resolution. */
    private fun renderSafely(context: Context, bytes: ByteArray, page: PageTranslation, style: Style): Result<ByteArray?> {
        var last: Throwable? = null
        for (extra in intArrayOf(1, 2, 4)) {
            try {
                return Result.success(render(context, bytes, page, style, extra))
            } catch (e: OutOfMemoryError) {
                last = e
                System.gc()
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return Result.failure(last ?: IllegalStateException("render failed"))
    }

    private fun render(context: Context, bytes: ByteArray, page: PageTranslation, style: Style, extraSample: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = extraSample
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2
        val pixels = (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample)
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inSampleSize = sample
            // Full colour for normal pages, half the memory for very long webtoon strips
            inPreferredConfig = if (pixels <= ARGB_PIXELS && extraSample == 1) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
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
            val areas = cleaned.mapIndexed { i, c ->
                val others = unions.filterIndexed { j, _ -> j != i }
                val room = if (c.plain) grow(c.union, c.bg, others) else RectF(c.union)
                edited(c.block, shrink(room, 0.04f))
            }
            // Two texts never share space: overlapping areas are split at the middle of their overlap
            separate(areas)
            val sets = cleaned.mapIndexed { i, c ->
                val paint = paintFor(c)
                Placement(c, areas[i], paint, fit(c.block.translation, paint, areas[i]))
            }
            // 3. Sizes evened out across the page, then drawn
            val fits = sets.map { it.fit }.filter { it > 0f }.sorted()
            val median = fits.getOrNull(fits.size / 2) ?: 0f
            sets.forEach { set ->
                var size = if (median > 0f) min(set.fit, median * 1.35f) else set.fit
                // Never larger than what fits; only the reader's own A+ may go past it
                size = min(size * style.sizeScale, set.fit)
                // Big titles stay big, but never giant
                size = min(size, bitmap.width * MAX_TEXT_OF_WIDTH)
                size = (size * set.cleaned.block.userFont).coerceAtLeast(MIN_TEXT_PX * s)
                typeset(set, size)
            }
        }

        private fun separate(areas: List<RectF>) {
            for (i in areas.indices) for (j in i + 1 until areas.size) {
                val a = areas[i]
                val b = areas[j]
                if (!RectF.intersects(a, b)) continue
                val overlapW = min(a.right, b.right) - max(a.left, b.left)
                val overlapH = min(a.bottom, b.bottom) - max(a.top, b.top)
                if (overlapH <= overlapW) {
                    val (upper, lower) = if (a.centerY() <= b.centerY()) a to b else b to a
                    val mid = (max(upper.top, lower.top) + min(upper.bottom, lower.bottom)) / 2f
                    upper.bottom = min(upper.bottom, mid).coerceAtLeast(upper.top + 1f)
                    lower.top = max(lower.top, mid).coerceAtMost(lower.bottom - 1f)
                } else {
                    val (first, second) = if (a.centerX() <= b.centerX()) a to b else b to a
                    val mid = (max(first.left, second.left) + min(first.right, second.right)) / 2f
                    first.right = min(first.right, mid).coerceAtLeast(first.left + 1f)
                    second.left = max(second.left, mid).coerceAtMost(second.right - 1f)
                }
            }
        }

        // ---------------------------------------------------------------- cleaning

        private fun clean(block: TranslationBlock, kind: BubbleKind): Cleaned {
            val rects = block.lineBoxes.map { line ->
                // Margin grows with the letters: big titles carry thick outlines and glows around them
                val padX = max(PAD, line.h * 0.45f)
                val padY = max(PAD, line.h * 0.45f)
                RectF((line.x - padX) * s, (line.y - padY) * s, (line.right + padX) * s, (line.bottom + padY) * s)
            }
            val bounds = RectF(rects.first())
            rects.forEach { bounds.union(it) }
            clamp(bounds)
            val inners = block.lineBoxes.map { line -> RectF(line.x * s, line.y * s, line.right * s, line.bottom * s) }
            val results = rects.mapIndexed { i, rect -> cleanArea(clamp(RectF(rect)), inners[i]) }
            // One more pass over the whole bubble (a little taller): letters joined across two lines, glows
            // and a line the OCR did not list are inside it and are cleaned too
            val lineH = block.lineBoxes.map { it.h }.average().toFloat() * s
            val textBox = RectF(inners.first()).apply { inners.forEach { union(it) } }
            val extra = min(lineH * 0.8f, WHOLE_EXTRA * s)
            val whole = clamp(RectF(bounds.left, bounds.top - extra, bounds.right, bounds.bottom + extra))
            cleanArea(whole, RectF(textBox.left, textBox.top - extra, textBox.right, textBox.bottom + extra), boxWhenBusy = false, strictKeep = true)
            val plain = results.filterNotNull().filter { it.second }
            val bg = plain.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
                ?: results.filterNotNull().firstOrNull()?.first
                ?: Color.WHITE
            return Cleaned(block, kind, bounds, bg, plain.isNotEmpty())
        }

        /**
         * Cleans one area like a cleaner's brush. The background is read from the ring between [area] and
         * [inner] (where the letters are not): its main colour, and a smooth model of it (a plane per colour
         * channel, so gradients stay gradients). Groups of pixels that reach the edge of the area are the
         * bubble's outline or the drawing: they and their soft edge are protected. Every other pixel (letters,
         * their glow, faint ghosts) is painted with the background model, feathered at the area's edge.
         * Over a busy drawing (no plain background) a soft patch of the main colour is used instead.
         * Returns (background, plain).
         */
        private fun cleanArea(area: RectF, inner: RectF, boxWhenBusy: Boolean = true, strictKeep: Boolean = false): Pair<Int, Boolean>? {
            val l = area.left.roundToInt().coerceIn(0, bitmap.width - 1)
            val t = area.top.roundToInt().coerceIn(0, bitmap.height - 1)
            val r = area.right.roundToInt().coerceIn(0, bitmap.width)
            val b = area.bottom.roundToInt().coerceIn(0, bitmap.height)
            val w = r - l
            val h = b - t
            if (w < 3 || h < 3) return null
            val n = w * h
            val px = IntArray(n)
            bitmap.getPixels(px, 0, w, l, t, w, h)
            val innerL = (inner.left.roundToInt() - l).coerceIn(0, w)
            val innerR = (inner.right.roundToInt() - l).coerceIn(0, w)
            val innerT = (inner.top.roundToInt() - t).coerceIn(0, h)
            val innerB = (inner.bottom.roundToInt() - t).coerceIn(0, h)
            val inRing = BooleanArray(n) { i ->
                val x = i % w
                val y = i / w
                x < innerL || x >= innerR || y < innerT || y >= innerB
            }
            val ring = IntArray(inRing.count { it })
            var k = 0
            for (i in 0 until n) if (inRing[i]) ring[k++] = px[i]
            val (bg, share) = dominant(if (ring.size >= 16) ring else px) ?: return null
            // Background model: least-squares plane per channel over the ring pixels close to its main colour
            var solved = fitPlane(px, w, n, inRing, null, bg, MODEL_DISTANCE)
            var residual = residual(px, w, n, inRing, solved)
            if (share < PLAIN_SHARE && residual > SMOOTH_RESIDUAL) {
                // No main colour, maybe a smooth gradient: model the whole ring
                solved = fitPlane(px, w, n, inRing, null, bg, Int.MAX_VALUE)
                residual = residual(px, w, n, inRing, solved)
            }
            if (share < PLAIN_SHARE && residual > SMOOTH_RESIDUAL) {
                // A busy drawing behind the text (speed lines, art): rebuilt from the area's edges
                if (boxWhenBusy) rebuildFromEdges(px, w, h)?.let { bitmap.setPixels(it, 0, w, l, t, w, h) }
                return averageOf(if (ring.isEmpty()) px else ring) to false
            }
            val modelAt = IntArray(n) { solved.at((it % w).toDouble(), (it / w).toDouble()) }
            // Groups of ink: one that reaches the edge is the outline or the drawing and is kept, unless most of
            // it lies on the letters themselves (big titles: their glow and outline reach the edge too)
            val ink = BooleanArray(n) { distance(px[it], modelAt[it]) > INK }
            val seen = BooleanArray(n)
            val keep = BooleanArray(n)
            val queue = IntArray(n)
            for (start in 0 until n) {
                if (!ink[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var touches = false
                var onLetters = 0
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % w
                    val y = i / w
                    if (!inRing[i]) onLetters++
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches = true
                    if (x > 0 && ink[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                    if (x < w - 1 && ink[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                    if (y > 0 && ink[i - w] && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                    if (y < h - 1 && ink[i + w] && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
                }
                val letters = !strictKeep && onLetters > tail * 0.5f
                if (touches && !letters) for (q in 0 until tail) keep[queue[q]] = true
            }
            // What is kept, with its soft edge (2 px), is never painted
            val protect = keep.copyOf()
            for (i in 0 until n) {
                if (!keep[i]) continue
                val x = i % w
                val y = i / w
                for (dy in -GROW..GROW) for (dx in -GROW..GROW) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (distance(px[j], modelAt[j]) > HALO) protect[j] = true
                }
            }
            var any = false
            for (i in 0 until n) {
                if (protect[i]) continue
                val x = i % w
                val y = i / w
                val target = modelAt[i]
                // Feathered at the area's edge so no seam shows on gradients
                val edge = min(min(x, y), min(w - 1 - x, h - 1 - y)).toFloat()
                val f = (edge / FEATHER).coerceIn(0f, 1f)
                val value = if (f >= 1f) target else mix(px[i], target, f)
                if (value != px[i]) {
                    px[i] = value
                    any = true
                }
            }
            if (any) bitmap.setPixels(px, 0, w, l, t, w, h)
            return bg to true
        }

        private fun fitPlane(px: IntArray, w: Int, n: Int, inRing: BooleanArray, skip: BooleanArray?, bg: Int, maxDistance: Int): PlaneModel.Solved {
            val model = PlaneModel()
            for (i in 0 until n) {
                if (!inRing[i] || skip?.get(i) == true) continue
                if (maxDistance != Int.MAX_VALUE && distance(px[i], bg) > maxDistance) continue
                model.add((i % w).toDouble(), (i / w).toDouble(), px[i])
            }
            return model.solve(bg)
        }

        /** Mean colour distance between the ring and the model: small for flat colours and smooth gradients. */
        private fun residual(px: IntArray, w: Int, n: Int, inRing: BooleanArray, model: PlaneModel.Solved): Float {
            var sum = 0L
            var count = 0
            var i = 0
            val step = max(1, n / 8000)
            while (i < n) {
                if (inRing[i]) {
                    sum += distance(px[i], model.at((i % w).toDouble(), (i / w).toDouble()))
                    count++
                }
                i += step
            }
            return if (count == 0) Float.MAX_VALUE else sum.toFloat() / count
        }

        /**
         * Rebuilds a busy area from its own edges (a directional Coons patch): every pixel is blended from the
         * top and bottom edge in its column and the left and right edge in its row, weighted towards the
         * direction the background runs in (speed lines, rain, gradients carry on through), feathered at the edge.
         */
        private fun rebuildFromEdges(px: IntArray, w: Int, h: Int): IntArray? {
            if (w < 8 || h < 8) return null
            val band = 3
            val top = Array(w) { x -> averageColumn(px, w, x, 0, band) }
            val bottom = Array(w) { x -> averageColumn(px, w, x, h - band, h) }
            val left = Array(h) { y -> averageRow(px, w, y, 0, band) }
            val right = Array(h) { y -> averageRow(px, w, y, w - band, w) }
            var dv = 0.0
            for (x in 0 until w) dv += distance(top[x], bottom[x])
            var dh = 0.0
            for (y in 0 until h) dh += distance(left[y], right[y])
            dv /= w
            dh /= h
            // Columns that match top to bottom mean the background runs vertically, and the other way round
            val wv = (dh / (dv + dh + 1e-6)).toFloat()
            val out = px.copyOf()
            for (y in 0 until h) {
                val v = y / (h - 1f)
                for (x in 0 until w) {
                    val u = x / (w - 1f)
                    val vertical = mix(top[x], bottom[x], v)
                    val horizontal = mix(left[y], right[y], u)
                    val target = mix(horizontal, vertical, wv)
                    val edge = min(min(x, y), min(w - 1 - x, h - 1 - y)).toFloat()
                    val f = (edge / (FEATHER + 1f)).coerceIn(0f, 1f)
                    out[y * w + x] = if (f >= 1f) target else mix(px[y * w + x], target, f)
                }
            }
            return out
        }

        private fun averageColumn(px: IntArray, w: Int, x: Int, from: Int, to: Int): Int {
            var r = 0
            var g = 0
            var b = 0
            for (y in from until to) {
                val p = px[y * w + x]
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
            }
            val n = (to - from).coerceAtLeast(1)
            return Color.rgb(r / n, g / n, b / n)
        }

        private fun averageRow(px: IntArray, w: Int, y: Int, from: Int, to: Int): Int {
            var r = 0
            var g = 0
            var b = 0
            for (x in from until to) {
                val p = px[y * w + x]
                r += Color.red(p)
                g += Color.green(p)
                b += Color.blue(p)
            }
            val n = (to - from).coerceAtLeast(1)
            return Color.rgb(r / n, g / n, b / n)
        }

        private fun averageOf(px: IntArray): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            px.forEach {
                r += Color.red(it)
                g += Color.green(it)
                b += Color.blue(it)
            }
            val n = px.size.coerceAtLeast(1)
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
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
            val dirty = px.count { distance(it, bg) > FREE_DISTANCE }
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

    /** Least-squares plane a + b·x + c·y for each colour channel: a smooth model of a bubble's background. */
    private class PlaneModel {
        private var n = 0.0
        private var sx = 0.0
        private var sy = 0.0
        private var sxx = 0.0
        private var sxy = 0.0
        private var syy = 0.0
        private val sv = DoubleArray(3)
        private val sxv = DoubleArray(3)
        private val syv = DoubleArray(3)

        fun add(x: Double, y: Double, color: Int) {
            n++
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
            syy += y * y
            val c = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
            for (k in 0..2) {
                sv[k] += c[k]
                sxv[k] += x * c[k]
                syv[k] += y * c[k]
            }
        }

        class Solved(private val coef: Array<DoubleArray>?, private val flat: Int) {
            fun at(x: Double, y: Double): Int {
                val c = coef ?: return flat
                return Color.rgb(
                    (c[0][0] + c[0][1] * x + c[0][2] * y).roundToInt().coerceIn(0, 255),
                    (c[1][0] + c[1][1] * x + c[1][2] * y).roundToInt().coerceIn(0, 255),
                    (c[2][0] + c[2][1] * x + c[2][2] * y).roundToInt().coerceIn(0, 255),
                )
            }
        }

        fun solve(flat: Int): Solved {
            if (n < 12) return Solved(null, flat)
            // Normal equations [[n, sx, sy], [sx, sxx, sxy], [sy, sxy, syy]] · [a, b, c] = [sv, sxv, syv]
            val det = det3(n, sx, sy, sx, sxx, sxy, sy, sxy, syy)
            if (abs(det) < 1e-6) return Solved(null, flat)
            val coef = Array(3) { k ->
                doubleArrayOf(
                    det3(sv[k], sx, sy, sxv[k], sxx, sxy, syv[k], sxy, syy) / det,
                    det3(n, sv[k], sy, sx, sxv[k], sxy, sy, syv[k], syy) / det,
                    det3(n, sx, sv[k], sx, sxx, sxv[k], sy, sxy, syv[k]) / det,
                )
            }
            return Solved(coef, flat)
        }

        private fun det3(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, g: Double, h: Double, i: Double) =
            a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
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
            if (distance(px[i], color) <= SHARE_DISTANCE) near++
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
    private const val ARGB_PIXELS = 10_000_000L
    private const val JPEG_QUALITY = 92
    private const val PAD = 12f
    private const val PLAIN_SHARE = 0.4f
    private const val SMOOTH_RESIDUAL = 20f
    private const val WHOLE_EXTRA = 24f
    private const val MAX_TEXT_OF_WIDTH = 0.085f
    private const val INK = 28
    private const val SHARE_DISTANCE = 70
    private const val FREE_DISTANCE = 45
    private const val HALO = 8
    private const val MODEL_DISTANCE = 40
    private const val FEATHER = 5f
    private const val GROW = 2
    private const val MIN_TEXT_PX = 6f
    private val RTL_SCRIPTS = setOf("ar", "fa", "ur", "he", "iw")
    private val WIDE_SCRIPTS = setOf("ja", "zh", "ko", "th", "hi", "ru", "uk", "el", "bg")
}
