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
    fun recognize(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        paddle: ((Bitmap, (eu.kanade.translation.ocr.PaddleMath.Box) -> Boolean) -> List<eu.kanade.translation.ocr.PaddleOcrCore.Line>?)? = null,
        mode: eu.kanade.translation.ocr.PaddleOcrEngine.Mode = eu.kanade.translation.ocr.PaddleOcrEngine.Mode.MLKIT,
    ): PageTranslation {
        val w = bitmap.width
        val h = bitmap.height
        val stripH = (w * STRIP_RATIO).toInt().coerceAtLeast(64)
        if (h <= stripH * 1.15f) {
            val blocks = readStrip(recognizer, bitmap, paddle, mode)
            return PageTranslation(markSfx(mergeLines(blocks, w.toFloat(), bubbleFinder(bitmap))), w.toFloat(), h.toFloat())
        }
        val overlap = (stripH * OVERLAP).toInt()
        val found = mutableListOf<TranslationBlock>()
        var top = 0
        while (top < h) {
            val sh = min(stripH, h - top)
            val strip = Bitmap.createBitmap(bitmap, 0, top, w, sh)
            val part = try {
                readStrip(recognizer, strip, paddle, mode)
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
        return PageTranslation(markSfx(mergeLines(found, w.toFloat(), bubbleFinder(bitmap))), w.toFloat(), h.toFloat())
    }

    /**
     * MANHWA bubble finder: the speech bubble a block sits in (null when it is not in a closed light shape),
     * read from the page around the block only, and only for blocks that are about to be joined.
     */
    private fun bubbleFinder(bitmap: Bitmap): (TranslationBlock) -> eu.kanade.translation.ocr.BubbleFinder.Rect? = { b ->
        runCatching {
            val w = bitmap.width
            val h = bitmap.height
            val lineH = b.lineHeight().toInt().coerceAtLeast(4)
            val margin = (max(lineH * 3, max(b.width, b.height).toInt() / 2) + lineH * 4).coerceAtMost(BUBBLE_MARGIN_MAX)
            val left = (b.x.toInt() - margin).coerceIn(0, w - 1)
            val top = (b.y.toInt() - margin).coerceIn(0, h - 1)
            val right = (b.x + b.width).toInt().plus(margin).coerceIn(left + 1, w)
            val bottom = (b.y + b.height).toInt().plus(margin).coerceIn(top + 1, h)
            val rw = right - left
            val rh = bottom - top
            val region = IntArray(rw * rh)
            bitmap.getPixels(region, 0, rw, left, top, rw, rh)
            eu.kanade.translation.ocr.BubbleFinder.bubbleOf(
                region, rw, rh, b.x.toInt() - left, b.y.toInt() - top, b.width.toInt(), b.height.toInt(), lineH,
            )?.let { eu.kanade.translation.ocr.BubbleFinder.Rect(it.left + left, it.top + top, it.right + left, it.bottom + top) }
        }.getOrNull()
    }

    fun toPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation =
        PageTranslation(markSfx(mergeLines(toBlocks(blocks), width.toFloat())), width.toFloat(), height.toFloat())

    /**
     * MANHWA: marks sound effects drawn as art (huge letters, one short word, or a known onomatopoeia) with the
     * sfx kind before anything is translated, so they can be left exactly as drawn.
     */
    private fun markSfx(blocks: MutableList<TranslationBlock>): MutableList<TranslationBlock> {
        val heights = blocks.map { it.lineHeight() }.sorted()
        val median = if (heights.size >= SFX_MIN_BLOCKS) heights[heights.size / 2] else 0f
        blocks.forEach {
            when {
                looksLikeSfx(it, median) -> it.kind = SFX
                // MANHWA art text: a skill, a spell or a sign drawn in huge letters, not a sound and not a bubble
                looksLikeArt(it, median) -> it.kind = ART
            }
        }
        return blocks
    }

    /** Text drawn into the art (skill and spell names, signs, titles): far bigger than the page's text and longer than a sound. */
    fun looksLikeArt(block: TranslationBlock, medianLineHeight: Float): Boolean {
        if (medianLineHeight <= 0f) return false
        val letters = block.text.count { it.isLetter() }
        return letters > SFX_BIG_CHARS && block.lineHeight() > medianLineHeight * ART_SIZE_FACTOR
    }

    /** Whether [block] is a sound effect: a known onomatopoeia, or one short word in letters far bigger than the page's text. */
    fun looksLikeSfx(block: TranslationBlock, medianLineHeight: Float): Boolean {
        val compact = block.text.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact.length > SFX_MAX_CHARS) return false
        if (block.text.trim().contains(' ') || compact.any { it in SENTENCE_MARKS }) return false
        val letters = compact.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        if (KNOWN_SFX.matches(letters.lowercase())) return true
        // Hangul made only of the syllables sound effects are built from (쾅, 쿵쾅, 두근두근...)
        if (letters.length <= 6 && letters.all { it in SFX_SYLLABLES } && letters.any { it in SFX_STRONG }) return true
        // Short katakana with a small tsu or a long mark (ドン, バッ, ゴゴゴ...)
        if (letters.length <= 6 && letters.all { it in '゠'..'ヿ' } && (letters.any { it == 'ッ' || it == 'ー' } || letters.toSet().size < letters.length)) return true
        return medianLineHeight > 0f && block.lineHeight() > medianLineHeight * SFX_SIZE_FACTOR && letters.length <= SFX_BIG_CHARS
    }

    /**
     * MANHWA: reads one strip as well as the OCR can. Narrow pages (small letters) are read enlarged; a strip with
     * dark panels (system windows, night scenes, black captions) is read a second time with its colours turned
     * to dark-on-light, and what only that reading found is added. Boxes stay in [strip] pixels.
     */
    private fun readStrip(
        recognizer: TextRecognizer,
        strip: Bitmap,
        paddle: ((Bitmap, (eu.kanade.translation.ocr.PaddleMath.Box) -> Boolean) -> List<eu.kanade.translation.ocr.PaddleOcrCore.Line>?)?,
        mode: eu.kanade.translation.ocr.PaddleOcrEngine.Mode,
    ): List<TranslationBlock> {
        val scale = if (strip.width < SMALL_WIDTH) UPSCALE else 1f
        // MANHWA dual OCR: ML Kit reads first (at plain size: PaddleOCR takes care of small and light text), then
        // PaddleOCR looks at the whole strip but reads only the lines ML Kit did not read surely. Null when
        // PaddleOCR can't: ML Kit alone, as before.
        if (paddle != null) {
            val dual = mode != eu.kanade.translation.ocr.PaddleOcrEngine.Mode.PADDLE
            val mlkit = if (dual) runCatching { readAt(recognizer, strip, 1f) }.getOrDefault(emptyList()) else emptyList()
            val sure = mlkit.filter { it.ocrConfidence >= eu.kanade.translation.ocr.OcrFusion.TRUST && !eu.kanade.translation.ocr.OcrFusion.garbled(it.text) }
            val lines = runCatching { paddle(strip) { box -> sure.none { covers(it, box) } } }.getOrNull()
            if (lines != null) {
                val found = lines.map { eu.kanade.translation.ocr.OcrFusion.block(it.text, it.confidence, it.x, it.y, it.w, it.h) }
                return if (dual) eu.kanade.translation.ocr.OcrFusion.fuse(mlkit, found) else found
            }
        }
        val base = readAt(recognizer, strip, scale)
        if (!hasDarkPanels(strip)) return base
        val inverted = runCatching { invertedGray(strip) }.getOrNull() ?: return base
        val extra = try {
            readAt(recognizer, inverted, scale)
        } catch (e: Exception) {
            emptyList()
        } finally {
            inverted.recycle()
        }
        return base + extra.filter { e -> base.none { overlapRatio(it, e) > 0.3f } }
    }

    /** The PaddleOCR line [box] sits inside [block] (its centre), so it was already read. */
    private fun covers(block: TranslationBlock, box: eu.kanade.translation.ocr.PaddleMath.Box): Boolean {
        val cx = box.x + box.w / 2f
        val cy = box.y + box.h / 2f
        val margin = min(block.width, block.height) * 0.15f + 4f
        return cx >= block.x - margin && cx <= block.x + block.width + margin && cy >= block.y - margin && cy <= block.y + block.height + margin
    }

    private fun readAt(recognizer: TextRecognizer, bitmap: Bitmap, scale: Float): List<TranslationBlock> {
        if (scale == 1f) return toBlocks(read(recognizer, bitmap))
        val big = runCatching {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }.getOrNull() ?: return toBlocks(read(recognizer, bitmap))
        return try {
            toBlocks(read(recognizer, big)).map { it.scaled(1f / scale) }
        } finally {
            if (big !== bitmap) big.recycle()
        }
    }

    /** A good part of the strip is dark (where light letters on a dark panel can hide from the OCR). */
    private fun hasDarkPanels(bitmap: Bitmap): Boolean {
        val stepX = max(1, bitmap.width / DARK_GRID)
        val stepY = max(1, bitmap.height / DARK_GRID)
        var dark = 0
        var all = 0
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val lum = (android.graphics.Color.red(c) * 299 + android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000
                if (lum < DARK_LUMA) dark++
                all++
                x += stepX
            }
            y += stepY
        }
        return all > 0 && dark.toFloat() / all >= DARK_SHARE
    }

    /** The strip in grey with its light and dark swapped (light letters on dark become dark on light). */
    private fun invertedGray(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val matrix = android.graphics.ColorMatrix(
            floatArrayOf(
                -0.299f, -0.587f, -0.114f, 0f, 255f,
                -0.299f, -0.587f, -0.114f, 0f, 255f,
                -0.299f, -0.587f, -0.114f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(matrix) }
        android.graphics.Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    private fun TranslationBlock.scaled(f: Float): TranslationBlock =
        copy(
            x = x * f,
            y = y * f,
            width = width * f,
            height = height * f,
            symWidth = symWidth * f,
            symHeight = symHeight * f,
            lines = lineBoxes.map { LineBox(it.x * f, it.y * f, it.w * f, it.h * f, it.bg) },
        )

    private fun read(recognizer: TextRecognizer, bitmap: Bitmap): List<Text.TextBlock> =
        recognizer.recognize(InputImage.fromBitmap(bitmap, 0)).textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

    private fun toBlocks(blocks: List<Text.TextBlock>): List<TranslationBlock> = blocks.mapNotNull { block ->
        // MANHWA: the least sure line decides how sure the bubble is (a misread word spoils the whole line)
        val confidence = block.lines.mapNotNull { line -> runCatching { line.confidence }.getOrNull()?.takeIf { it > 0f } }.minOrNull() ?: 1f
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
            ocrConfidence = confidence,
        )
    }

    private fun TranslationBlock.shifted(dy: Float): TranslationBlock =
        copy(y = y + dy, lines = lines.map { it.copy(y = it.y + dy) })

    /**
     * Joins blocks that are lines of the same bubble: close one under the other, with the same text size and
     * overlapping columns, or boxes that overlap. Never past a size limit, so far-apart text stays apart.
     */
    private fun mergeLines(
        blocks: List<TranslationBlock>,
        pageWidth: Float,
        bubbleOf: ((TranslationBlock) -> eu.kanade.translation.ocr.BubbleFinder.Rect?)? = null,
    ): MutableList<TranslationBlock> {
        val list = blocks.sortedWith(compareBy({ it.y }, { it.x })).toMutableList()
        // Each block's bubble is looked for once, and only when it is about to be joined
        val bubbles = java.util.IdentityHashMap<TranslationBlock, eu.kanade.translation.ocr.BubbleFinder.Rect?>()
        fun bubble(b: TranslationBlock) = if (bubbles.containsKey(b)) bubbles[b] else bubbleOf?.invoke(b).also { bubbles[b] = it }
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    // Two different bubbles (when both are known) are never one, however close
                    if (shouldJoin(list[i], list[j], pageWidth) &&
                        (bubbleOf == null || eu.kanade.translation.ocr.BubbleFinder.same(bubble(list[i]), bubble(list[j])) != false)
                    ) {
                        val joined = join(list[i], list[j])
                        if (bubbleOf != null) bubbles[joined] = bubble(list[i]) ?: bubble(list[j])
                        list[i] = joined
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
            ocrConfidence = min(a.ocrConfidence, b.ocrConfidence),
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
    fun finish(page: PageTranslation, keepSfxArt: Boolean = false) {
        // Bubbles from the vision model whose letters could not be found on the page are never drawn, and
        // (MANHWA) sound effects left untranslated stay as drawn: neither drawn over nor erased
        val blocks = page.blocks.filterNot { it.needsSnap || (keepSfxArt && it.kind.equals(SFX, ignoreCase = true)) }.toMutableList()
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
    private const val BUBBLE_MARGIN_MAX = 200

    /** Pages narrower than this are read enlarged by [UPSCALE] (their letters are small for the OCR). */
    private const val SMALL_WIDTH = 800
    private const val UPSCALE = 1.5f

    /** A strip is read a second time inverted when at least [DARK_SHARE] of it is darker than [DARK_LUMA]. */
    private const val DARK_GRID = 48
    private const val DARK_LUMA = 70
    private const val DARK_SHARE = 0.08f
    private const val OVERLAP = 0.15f
    private const val MAX_WIDTH = 0.95f
    private const val MAX_HEIGHT = 0.6f
    private const val MAX_LINES = 12
    private const val NMS_IOU = 0.15f

    const val SFX = "sfx"

    /** Kind of the text drawn into the art: translated as a caption under it, the art is never erased. */
    const val ART = "art"
    private const val SFX_MIN_BLOCKS = 3
    private const val SFX_MAX_CHARS = 10
    private const val SFX_BIG_CHARS = 6
    private const val SFX_SIZE_FACTOR = 2.2f
    private const val ART_SIZE_FACTOR = 2.0f
    private val SENTENCE_MARKS = setOf('.', ',', '?', '"', '\'', ':', ';', '。', '、', '？', '…', '「', '」', '(', ')')
    private val KNOWN_SFX = Regex(
        "(boom|bam|bang|crash|thud|thump|whoosh|swish|swoosh|slash|click|clack|crack|snap|drip|buzz|rumble|shing|clang|" +
            "zing|pow|wham|smash|splash|crunch|creak|thunk|whack|fwoosh|vroom|zap|ding|dong|tap|knock|grr+|kaboom|ba-?dump|" +
            "badump|thwack)+!*",
    )
    /** Syllables almost only found in sound effects: at least one must be there (keeps words like 파악 out). */
    private val SFX_STRONG = "쾅쿵퍽휙쉭탁툭펑콰쨍슉찰칵철컥꽈띵똑뚝쩍척칙쯧헉훅탕팡빵삐뎅쏴촤쫙짹두근".toSet()
    private val SFX_SYLLABLES = (
        "쾅쿵퍽휙쉭탁툭펑콰광앙두근철컥찰칵쨍슉휘익파앗직우지끈빠쿠궁덜털썩청꽈당부르지잉띵똑뚝드륵화번쩍스윽슥휭쏴짝팍빡뻑" +
            "쿠웅쾅쩍척착칙쯧흡헉훅휴흑덜컹쿵탕팡빵삐릭딩동뎅쨍그랑와장창우르릉쿠르릉콰르릉쏴아촤악쫙짹"
        ).toSet()
}
