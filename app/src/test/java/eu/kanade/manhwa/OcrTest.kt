package eu.kanade.manhwa

import eu.kanade.translation.ocr.BubbleFinder
import eu.kanade.translation.ocr.OcrFusion
import eu.kanade.translation.ocr.OcrModelRules
import eu.kanade.translation.ocr.PaddleMath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PaddleMathTest {

    @Test
    fun `detector size keeps small pages whole and rounds to 32`() {
        PaddleMath.detSize(720, 1100, 2048) shouldBe (736 to 1088)
        PaddleMath.detSize(3000, 1000, 2048) shouldBe (2048 to 672)
        PaddleMath.detSize(10, 10, 2048) shouldBe (32 to 32)
    }

    @Test
    fun `recognizer width follows the line's shape at height 48`() {
        PaddleMath.recWidth(200, 40, 48, 1600) shouldBe 240
        PaddleMath.recWidth(10, 100, 48, 1600) shouldBe 16
        PaddleMath.recWidth(100_000, 10, 48, 1600) shouldBe 1600
    }

    @Test
    fun `tensor is NCHW in BGR order and normalised`() {
        // One pixel: red 255, green 0, blue 0
        val t = PaddleMath.toTensor(intArrayOf(0xFFFF0000.toInt()), 1, 1, 0, 0, 1, 1, 1, 1, PaddleMath.REC_MEAN, PaddleMath.REC_STD)
        t.size shouldBe 3
        // channel 0 = blue (0 -> -1), channel 2 = red (1 -> 1)
        (abs(t[0] - -1f) < 1e-5f) shouldBe true
        (abs(t[1] - -1f) < 1e-5f) shouldBe true
        (abs(t[2] - 1f) < 1e-5f) shouldBe true
    }

    @Test
    fun `CTC drops blanks and repeats, the class after the dictionary is a space`() {
        val dict = listOf("a", "b", "c")
        val classes = 5
        // steps: b, b, blank, b, space, c
        val best = intArrayOf(2, 2, 0, 2, 4, 3)
        val probs = FloatArray(best.size * classes) { 0.01f }
        best.forEachIndexed { t, c -> probs[t * classes + c] = 0.9f }
        val (text, confidence) = PaddleMath.ctcDecode(probs, best.size, classes, dict)
        text shouldBe "bb c"
        (abs(confidence - 0.9f) < 1e-5f) shouldBe true
        PaddleMath.ctcDecode(FloatArray(classes) { if (it == 0) 1f else 0f }, 1, classes, dict) shouldBe ("" to 0f)
    }

    @Test
    fun `two lines touching through a bridge are cut apart`() {
        PaddleMath.splitRows(intArrayOf(10, 10, 10, 10, 1, 1, 10, 10, 10, 10)) shouldBe listOf(0 to 3, 6 to 9)
        // One line with thin ends stays whole
        PaddleMath.splitRows(intArrayOf(2, 10, 10, 10, 10, 10, 2)) shouldBe listOf(0 to 6)
        val w = 40
        val h = 20
        val prob = FloatArray(w * h)
        for (y in 3..6) for (x in 5..30) prob[y * w + x] = 1f
        for (y in 10..13) for (x in 5..30) prob[y * w + x] = 1f
        for (y in 7..9) prob[y * w + 5] = 1f
        val boxes = PaddleMath.boxes(prob, w, h, 0.3f, 0.6f, 1.5f)
        boxes.size shouldBe 2
        (boxes[0].y < boxes[1].y) shouldBe true
    }

    @Test
    fun `vertical text is turned a quarter`() {
        // 2 wide, 3 tall: 1 2 / 3 4 / 5 6
        val img = intArrayOf(1, 2, 3, 4, 5, 6)
        val out = PaddleMath.rotateCrop(img, 2, PaddleMath.Box(0, 0, 2, 3, 1f))
        // 3 wide, 2 tall: the right column on top
        out.toList() shouldBe listOf(2, 4, 6, 1, 3, 5)
    }
}

class BubbleFinderTest {

    private fun page(ring: Boolean): IntArray {
        val w = 80
        val px = IntArray(w * w) { 0xFF707070.toInt() }
        for (y in 0 until w) for (x in 0 until w) {
            val d = Math.hypot((x - 40).toDouble(), (y - 40).toDouble())
            if (d < 30) px[y * w + x] = 0xFFFFFFFF.toInt()
            if (ring && d in 30.0..31.5) px[y * w + x] = 0xFF000000.toInt()
        }
        // The letters
        for (y in 36..43) for (x in 28..52 step 3) px[y * w + x] = 0xFF000000.toInt()
        return px
    }

    @Test
    fun `text in a closed bubble finds the bubble`() {
        val rect = BubbleFinder.bubbleOf(page(ring = true), 80, 80, 28, 36, 25, 8, 8)
        (rect != null) shouldBe true
        (rect!!.left in 5..20 && rect.right in 60..76) shouldBe true
    }

    @Test
    fun `two bubbles are apart, unknown decides nothing`() {
        val a = BubbleFinder.Rect(0, 0, 100, 100)
        val b = BubbleFinder.Rect(120, 0, 220, 100)
        BubbleFinder.same(a, b) shouldBe false
        BubbleFinder.same(a, BubbleFinder.Rect(10, 10, 90, 90)) shouldBe true
        BubbleFinder.same(a, null) shouldBe null
    }

    @Test
    fun `light area reaching the edge is no bubble`() {
        val w = 60
        val px = IntArray(w * w) { 0xFFFFFFFF.toInt() }
        for (y in 26..33) for (x in 20..40 step 3) px[y * w + x] = 0xFF000000.toInt()
        BubbleFinder.bubbleOf(px, w, w, 20, 26, 21, 8, 8) shouldBe null
    }
}

class OcrFusionTest {

    @Test
    fun `unsure ML Kit reading is replaced by a sure PaddleOCR one, a sure one stays`() {
        val unsure = OcrFusion.block("W@@RE |¦|", 0.5f, 100, 100, 120, 30)
        val sure = OcrFusion.block("HELLO", 0.97f, 100, 300, 120, 30)
        val paddle = listOf(
            OcrFusion.block("WHERE", 0.95f, 105, 102, 110, 26),
            OcrFusion.block("HELL0", 0.9f, 104, 302, 112, 26),
        )
        val out = OcrFusion.fuse(listOf(unsure, sure), paddle)
        out.size shouldBe 2
        out[0].text shouldBe "WHERE"
        out[1].text shouldBe "HELLO"
    }

    @Test
    fun `lines only PaddleOCR saw are added when sure and real`() {
        val ml = listOf(OcrFusion.block("HELLO", 0.97f, 100, 300, 120, 30))
        val paddle = listOf(
            OcrFusion.block("LEVEL UP", 0.9f, 50, 600, 200, 30),
            OcrFusion.block("xx", 0.4f, 50, 800, 40, 20),
            OcrFusion.block("!", 0.99f, 50, 900, 10, 20),
        )
        val out = OcrFusion.fuse(ml, paddle)
        out.map { it.text } shouldBe listOf("HELLO", "LEVEL UP")
    }

    @Test
    fun `garbled text is spotted`() {
        OcrFusion.garbled("W@@RE |¦|") shouldBe true
        OcrFusion.garbled("What... is this?") shouldBe false
        OcrFusion.garbled("") shouldBe true
    }
}

class OcrModelRulesTest {

    @Test
    fun `a known file must match its fingerprint, an unknown one must be a real model`() {
        OcrModelRules.valid("det.onnx", 4_819_576, "abc", "ABC", 0x08) shouldBe true
        OcrModelRules.valid("det.onnx", 4_819_576, "abd", "abc", 0x08) shouldBe false
        OcrModelRules.valid("rec.onnx", 16_000_000, "x", null, 0x08) shouldBe true
        // An error page saved as a model
        OcrModelRules.valid("rec.onnx", 16_000_000, "x", null, '<'.code) shouldBe false
        OcrModelRules.valid("rec.onnx", 5_000, "x", null, 0x08) shouldBe false
        OcrModelRules.valid("dict.txt", 74_012, "x", null, 'a'.code) shouldBe true
    }
}
