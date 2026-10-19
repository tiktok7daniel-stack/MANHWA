package eu.kanade.translation.ocr

import eu.kanade.translation.model.LineBox
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min

/**
 * MANHWA dual OCR: ML Kit's reading and PaddleOCR's, joined into one.
 * - A bubble both read: ML Kit's reading stays when it is sure and clean; when it is unsure (under [TRUST]) or
 *   garbled, PaddleOCR's lines inside it take its place if PaddleOCR is at least as sure.
 * - A line only PaddleOCR found (stylised letters, small text, light text on dark): added.
 * Pure: tested without a phone.
 */
object OcrFusion {

    /** A PaddleOCR line as a block of one line (strip pixels). */
    fun block(text: String, confidence: Float, x: Int, y: Int, w: Int, h: Int): TranslationBlock =
        TranslationBlock(
            text = text,
            width = w.toFloat(),
            height = h.toFloat(),
            x = x.toFloat(),
            y = y.toFloat(),
            symHeight = h.toFloat(),
            symWidth = (w.toFloat() / max(1, text.length)).coerceAtMost(h.toFloat()),
            angle = 0f,
            ocrConfidence = confidence,
            lines = listOf(LineBox(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())),
        )

    fun fuse(mlkit: List<TranslationBlock>, paddle: List<TranslationBlock>): List<TranslationBlock> {
        val used = BooleanArray(paddle.size)
        val fused = mlkit.map { block ->
            val inside = paddle.indices.filter { !used[it] && centreInside(paddle[it], block) }
            if (inside.isEmpty()) return@map block
            inside.forEach { used[it] = true }
            val lines = inside.map { paddle[it] }.sortedWith(compareBy({ it.y }, { it.x }))
            val text = lines.joinToString("\n") { it.text }
            val confidence = lines.map { it.ocrConfidence }.average().toFloat()
            val mlUnsure = block.ocrConfidence < TRUST || garbled(block.text)
            // MANHWA: letters only PaddleOCR saw inside the bubble are erased too, whichever reading is kept
            val extraLines = lines.flatMap { it.lineBoxes }.filter { box -> block.lineBoxes.none { boxOverlap(it, box) > 0.5f } }
            val withLines = if (extraLines.isEmpty()) block else block.copy(lines = (block.lineBoxes + extraLines).sortedBy { it.y })
            if (mlUnsure && confidence >= block.ocrConfidence && text.isNotBlank() && !garbled(text)) {
                withLines.copy(text = text, ocrConfidence = confidence)
            } else {
                withLines
            }
        }
        // What only PaddleOCR saw: added when it is sure and is real text
        val added = paddle.indices
            .filter { !used[it] }
            .map { paddle[it] }
            .filter { p -> p.ocrConfidence >= addMin(p.text) && meaningful(p.text) && mlkit.none { overlap(it, p) > 0.3f } }
        return fused + added
    }

    /** Text that looks misread: many characters that are neither letters, digits, spaces nor usual punctuation. */
    fun garbled(text: String): Boolean {
        val chars = text.filterNot { it.isWhitespace() }
        if (chars.isEmpty()) return true
        val odd = chars.count { !it.isLetterOrDigit() && it !in USUAL }
        return odd.toFloat() / chars.length > GARBLED_SHARE
    }

    /** A short line only PaddleOCR saw ("IW", "IFI") is often a misread piece: it needs more confidence. */
    fun addMin(text: String): Float = if (text.count { it.isLetterOrDigit() } <= SHORT_LINE) ADD_MIN_SHORT else ADD_MIN

    private fun boxOverlap(a: LineBox, b: LineBox): Float {
        val w = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
        val h = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        if (w <= 0f || h <= 0f) return 0f
        return w * h / min(a.w * a.h, b.w * b.h).coerceAtLeast(1f)
    }

    /** At least two letters or digits: a lone mark is noise. */
    fun meaningful(text: String): Boolean = text.count { it.isLetterOrDigit() } >= 2

    private fun centreInside(line: TranslationBlock, block: TranslationBlock): Boolean {
        val cx = line.x + line.width / 2
        val cy = line.y + line.height / 2
        val margin = min(block.height, block.width) * 0.15f + 4f
        return cx >= block.x - margin && cx <= block.x + block.width + margin &&
            cy >= block.y - margin && cy <= block.y + block.height + margin
    }

    /** Intersection over the smaller box. */
    private fun overlap(a: TranslationBlock, b: TranslationBlock): Float {
        val w = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val h = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        if (w <= 0f || h <= 0f) return 0f
        return w * h / min(a.width * a.height, b.width * b.height).coerceAtLeast(1f)
    }

    /** Under this, ML Kit's reading is checked against PaddleOCR's. */
    const val TRUST = 0.85f

    /** A line only PaddleOCR saw is added from this confidence. */
    const val ADD_MIN = 0.6f
    const val ADD_MIN_SHORT = 0.85f
    private const val SHORT_LINE = 3

    private const val GARBLED_SHARE = 0.3f
    private val USUAL = setOf('.', ',', '!', '?', '\'', '"', '-', '…', ':', ';', '(', ')', '[', ']', '~', '*', '/', '&', '%', '+', '#', '“', '”', '’', '‘', '—', '–', '。', '、', '！', '？', '「', '」', '『', '』', '♡', '♥')
}
