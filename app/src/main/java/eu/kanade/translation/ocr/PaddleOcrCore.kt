package eu.kanade.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANHWA PaddleOCR (PP-OCRv5) on the phone, through ONNX Runtime: the detector (DBNet) finds every line of
 * text on the image, the recognizer (SVTR, CTC) reads each line. Plain Kotlin on ARGB pixels, no Android
 * types, so the same code is run on a real image on a computer before it ships.
 *
 * One instance keeps its two sessions loaded; [read] is not thread-safe (the caller goes one page at a time).
 */
class PaddleOcrCore(detModel: File, recModel: File, private val dict: List<String>) : AutoCloseable {

    /** One line of text read on the image, in the image's own pixels. */
    data class Line(val text: String, val confidence: Float, val x: Int, val y: Int, val w: Int, val h: Int)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(THREADS)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det: OrtSession = env.createSession(detModel.absolutePath, options)
    private val rec: OrtSession = env.createSession(recModel.absolutePath, options)
    private val detInput = det.inputNames.first()
    private val recInput = rec.inputNames.first()

    /** Every line of text in the [w]×[h] image [argb], top to bottom. */
    fun read(argb: IntArray, w: Int, h: Int): List<Line> {
        if (w < 8 || h < 8 || argb.size < w * h) return emptyList()
        val boxes = detect(argb, w, h)
        return boxes.mapNotNull { box -> recognize(argb, w, h, box) }
            .sortedWith(compareBy({ it.y }, { it.x }))
    }

    /** The boxes of the lines of text, in image pixels. */
    fun detect(argb: IntArray, w: Int, h: Int): List<PaddleMath.Box> {
        val (dw, dh) = PaddleMath.detSize(w, h, DET_LIMIT)
        val input = PaddleMath.toTensor(argb, w, h, 0, 0, w, h, dw, dh, PaddleMath.DET_MEAN, PaddleMath.DET_STD)
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, dh.toLong(), dw.toLong())).use { tensor ->
            det.run(mapOf(detInput to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val fb = t.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        if (prob.size < dw * dh) return emptyList()
        return PaddleMath.boxes(prob, dw, dh, DET_THRESH, BOX_THRESH, UNCLIP)
            .map { it.scaled(w.toFloat() / dw, h.toFloat() / dh, w, h) }
            .filter { it.w >= MIN_SIDE && it.h >= MIN_SIDE }
    }

    /** Reads the line in [box]; null when nothing readable is there. */
    fun recognize(argb: IntArray, w: Int, h: Int, box: PaddleMath.Box): Line? {
        // Tall narrow boxes are vertical text (Japanese, Chinese): read turned a quarter
        val vertical = box.h >= box.w * VERTICAL_RATIO && box.h > REC_H
        val (rw, input) = if (vertical) {
            val rotated = PaddleMath.rotateCrop(argb, w, box)
            val cw = box.h
            val ch = box.w
            val width = PaddleMath.recWidth(cw, ch, REC_H, REC_MAX_W)
            width to PaddleMath.toTensor(rotated, cw, ch, 0, 0, cw, ch, width, REC_H, PaddleMath.REC_MEAN, PaddleMath.REC_STD)
        } else {
            val width = PaddleMath.recWidth(box.w, box.h, REC_H, REC_MAX_W)
            width to PaddleMath.toTensor(argb, w, h, box.x, box.y, box.w, box.h, width, REC_H, PaddleMath.REC_MEAN, PaddleMath.REC_STD)
        }
        val (text, confidence) = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_H.toLong(), rw.toLong())).use { tensor ->
            rec.run(mapOf(recInput to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val fb = t.floatBuffer
                val probs = FloatArray(fb.remaining()).also { fb.get(it) }
                PaddleMath.ctcDecode(probs, steps, classes, dict)
            }
        }
        val clean = text.trim()
        if (clean.isEmpty() || confidence < MIN_CONFIDENCE) return null
        return Line(clean, confidence, box.x, box.y, box.w, box.h)
    }

    override fun close() {
        runCatching { det.close() }
        runCatching { rec.close() }
        runCatching { options.close() }
    }

    companion object {
        // The longer side is only reduced past this: small manhwa text keeps its size for the detector
        const val DET_LIMIT = 2048
        const val DET_THRESH = 0.3f
        const val BOX_THRESH = 0.6f
        const val UNCLIP = 1.5f
        const val REC_H = 48
        const val REC_MAX_W = 1600
        const val MIN_SIDE = 4
        const val VERTICAL_RATIO = 1.5f
        const val MIN_CONFIDENCE = 0.5f
        const val THREADS = 2

        /** The characters of a recognizer's dictionary file (one per line). */
        fun readDict(file: File): List<String> = file.readLines(Charsets.UTF_8).map { it.trimEnd('\r') }
    }
}

/** The pure arithmetic of PaddleOCR (tested without a phone and without a model). */
object PaddleMath {

    /** ImageNet mean and deviation, in the BGR order the models were trained on. */
    val DET_MEAN = floatArrayOf(0.406f, 0.456f, 0.485f)
    val DET_STD = floatArrayOf(0.225f, 0.224f, 0.229f)
    val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float) {
        fun scaled(sx: Float, sy: Float, maxW: Int, maxH: Int): Box {
            val x0 = (x * sx).roundToInt().coerceIn(0, maxW - 1)
            val y0 = (y * sy).roundToInt().coerceIn(0, maxH - 1)
            val x1 = ((x + w) * sx).roundToInt().coerceIn(x0 + 1, maxW)
            val y1 = ((y + h) * sy).roundToInt().coerceIn(y0 + 1, maxH)
            return Box(x0, y0, x1 - x0, y1 - y0, score)
        }
    }

    /** The detector's input size: the longer side at most [limit], both sides multiples of 32. */
    fun detSize(w: Int, h: Int, limit: Int): Pair<Int, Int> {
        val ratio = if (max(w, h) > limit) limit.toFloat() / max(w, h) else 1f
        fun round32(v: Float) = max(32, (v / 32f).roundToInt() * 32)
        return round32(w * ratio) to round32(h * ratio)
    }

    /** The recognizer's input width for a [w]×[h] line at height [height]. */
    fun recWidth(w: Int, h: Int, height: Int, maxWidth: Int): Int =
        ceil(height * w.toFloat() / max(1, h)).toInt().coerceIn(16, maxWidth)

    /**
     * The region ([rx], [ry], [rw], [rh]) of the [w]-wide image [argb], resized to [outW]×[outH] (bilinear) as a
     * normalised NCHW tensor in BGR order: (value / 255 - mean) / std.
     */
    fun toTensor(
        argb: IntArray,
        w: Int,
        h: Int,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        outW: Int,
        outH: Int,
        mean: FloatArray,
        std: FloatArray,
    ): FloatArray {
        val plane = outW * outH
        val out = FloatArray(3 * plane)
        val sx = rw.toFloat() / outW
        val sy = rh.toFloat() / outH
        for (oy in 0 until outH) {
            val fy = ((oy + 0.5f) * sy - 0.5f).coerceIn(0f, (rh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, rh - 1)
            val wy = fy - y0
            val row0 = (ry + y0).coerceIn(0, h - 1) * w
            val row1 = (ry + y1).coerceIn(0, h - 1) * w
            for (ox in 0 until outW) {
                val fx = ((ox + 0.5f) * sx - 0.5f).coerceIn(0f, (rw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, rw - 1)
                val wx = fx - x0
                val c00 = argb[row0 + (rx + x0).coerceIn(0, w - 1)]
                val c01 = argb[row0 + (rx + x1).coerceIn(0, w - 1)]
                val c10 = argb[row1 + (rx + x0).coerceIn(0, w - 1)]
                val c11 = argb[row1 + (rx + x1).coerceIn(0, w - 1)]
                val i = oy * outW + ox
                // Channel 0 = blue, 1 = green, 2 = red (the models were trained on BGR images)
                for (c in 0..2) {
                    val shift = c * 8
                    val v00 = (c00 shr shift) and 0xFF
                    val v01 = (c01 shr shift) and 0xFF
                    val v10 = (c10 shr shift) and 0xFF
                    val v11 = (c11 shr shift) and 0xFF
                    val top = v00 + (v01 - v00) * wx
                    val bottom = v10 + (v11 - v10) * wx
                    val v = (top + (bottom - top) * wy) / 255f
                    out[c * plane + i] = (v - mean[c]) / std[c]
                }
            }
        }
        return out
    }

    /**
     * The detector's probability map turned into line boxes: pixels over [thresh] grouped by 4-connectivity;
     * a group is kept when its mean probability reaches [boxThresh], then grown by the DB "unclip" distance
     * (area × [unclip] / perimeter), as PaddleOCR does.
     */
    fun boxes(prob: FloatArray, w: Int, h: Int, thresh: Float, boxThresh: Float, unclip: Float): List<Box> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val pixels = IntArray(w * h)
        val out = mutableListOf<Box>()
        for (start in 0 until w * h) {
            if (seen[start] || prob[start] <= thresh) continue
            var top = 0
            var count = 0
            stack[top++] = start
            seen[start] = true
            var minY = h
            var maxY = -1
            while (top > 0) {
                val p = stack[--top]
                pixels[count++] = p
                val x = p % w
                val y = p / w
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) push(p - 1, prob, thresh, seen, stack, top).let { if (it) top++ }
                if (x < w - 1) push(p + 1, prob, thresh, seen, stack, top).let { if (it) top++ }
                if (y > 0) push(p - w, prob, thresh, seen, stack, top).let { if (it) top++ }
                if (y < h - 1) push(p + w, prob, thresh, seen, stack, top).let { if (it) top++ }
            }
            if (count < 6) continue
            // Lines that touch (tight leading) are cut apart where the rows between them are nearly empty
            val rows = IntArray(maxY - minY + 1)
            for (k in 0 until count) rows[pixels[k] / w - minY]++
            for ((from, to) in splitRows(rows)) {
                var minX = w
                var maxX = -1
                var sum = 0f
                var n = 0
                for (k in 0 until count) {
                    val p = pixels[k]
                    val y = p / w - minY
                    if (y < from || y > to) continue
                    val x = p % w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    sum += prob[p]
                    n++
                }
                val bw = maxX - minX + 1
                val bh = to - from + 1
                if (n < 6 || min(bw, bh) < 3) continue
                val score = sum / n
                if (score < boxThresh) continue
                val area = (bw * bh).toFloat()
                val distance = area * unclip / (2f * (bw + bh))
                val x0 = (minX - distance).roundToInt().coerceAtLeast(0)
                val y0 = (minY + from - distance).roundToInt().coerceAtLeast(0)
                val x1 = (maxX + 1 + distance).roundToInt().coerceAtMost(w)
                val y1 = (minY + to + 1 + distance).roundToInt().coerceAtMost(h)
                out += Box(x0, y0, x1 - x0, y1 - y0, score)
            }
        }
        return out
    }

    /**
     * The bands of rows of one component that hold a line each: rows with under [SPLIT_SHARE] of the fullest
     * row's pixels separate lines. A band is kept only when it is at least [MIN_BAND] rows tall.
     */
    fun splitRows(rows: IntArray): List<Pair<Int, Int>> {
        val peak = rows.maxOrNull() ?: return emptyList()
        if (peak == 0) return emptyList()
        val limit = max(1f, peak * SPLIT_SHARE)
        val bands = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in rows.indices) {
            val full = rows[i] >= limit
            if (full && start < 0) start = i
            if (!full && start >= 0) {
                bands += start to i - 1
                start = -1
            }
        }
        if (start >= 0) bands += start to rows.size - 1
        // Each part must be a real line (not the tail of a letter): a fair share of the component's height
        val minBand = max(MIN_BAND, (rows.size * MIN_BAND_SHARE).toInt())
        val kept = bands.filter { (a, b) -> b - a + 1 >= minBand }
        // One band (or none worth keeping): the component is one line, whole
        return if (kept.size <= 1) listOf(0 to rows.size - 1) else kept
    }

    private const val SPLIT_SHARE = 0.4f
    private const val MIN_BAND_SHARE = 0.2f
    private const val MIN_BAND = 4

    private fun push(p: Int, prob: FloatArray, thresh: Float, seen: BooleanArray, stack: IntArray, top: Int): Boolean {
        if (seen[p] || prob[p] <= thresh) return false
        seen[p] = true
        stack[top] = p
        return true
    }

    /** The region of [box], turned a quarter counter-clockwise (vertical text becomes a horizontal line). */
    fun rotateCrop(argb: IntArray, w: Int, box: Box): IntArray {
        val cw = box.h
        val ch = box.w
        val out = IntArray(cw * ch)
        for (y in 0 until box.h) {
            for (x in 0 until box.w) {
                // (x, y) in the box goes to (y, w - 1 - x)
                out[(box.w - 1 - x) * cw + y] = argb[(box.y + y) * w + box.x + x]
            }
        }
        return out
    }

    /**
     * CTC greedy decoding of the recognizer's [steps]×[classes] output: the best class at every step, blanks
     * (class 0) and repeats dropped; class i is [dict][i - 1], the class after the dictionary is a space.
     * The confidence is the mean probability of the kept characters.
     */
    fun ctcDecode(probs: FloatArray, steps: Int, classes: Int, dict: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var sum = 0f
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            var bestP = probs[t * classes]
            for (c in 1 until classes) {
                val p = probs[t * classes + c]
                if (p > bestP) {
                    bestP = p
                    best = c
                }
            }
            if (best != 0 && best != previous) {
                text.append(dict.getOrNull(best - 1) ?: " ")
                sum += bestP
                kept++
            }
            previous = best
        }
        return text.toString() to if (kept == 0) 0f else sum / kept
    }
}
