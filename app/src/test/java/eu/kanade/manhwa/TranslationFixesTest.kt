package eu.kanade.manhwa

import eu.kanade.translation.live.OcrHealer
import eu.kanade.translation.ocr.BubbleFinder
import eu.kanade.translation.team.QualityCheck
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationFixesTest {

    @Test
    fun `a long line translated as a word or two is sent again`() {
        QualityCheck.check(
            "HOW IS THE SHADOW FIGHTER LEARNING THE IMPERIAL SWORD FORM PROGRESSING?",
            "المقاتل؟",
            emptyMap(),
            targetLatin = false,
        ) shouldBe QualityCheck.Problem.TOO_SHORT
        QualityCheck.check(
            "HOW IS THE SHADOW FIGHTER LEARNING THE IMPERIAL SWORD FORM PROGRESSING?",
            "كيف يتقدم مقاتل الظل في تعلم أسلوب السيف الإمبراطوري؟",
            emptyMap(),
            targetLatin = false,
        ).shouldBeNull()
    }

    @Test
    fun `a stuttered I read as digits is mended`() {
        OcrHealer.heal("1-1 DON'T KNOW...") shouldBe "I-I DON'T KNOW..."
        OcrHealer.heal("I-1 DON'T KNOW...") shouldBe "I-I DON'T KNOW..."
    }

    /** A dark gradient caption box with a light border, light letters inside, on a busy drawing. */
    private fun captionPage(w: Int, h: Int): IntArray {
        val px = IntArray(w * h) { i -> if ((i / w + i % w) % 3 == 0) 0xFF808080.toInt() else 0xFF303030.toInt() }
        for (y in 20 until 80) for (x in 20 until 180) {
            val border = y < 22 || y >= 78 || x < 22 || x >= 178
            val shade = 70 - (y - 20) // brown to black, top to bottom
            px[y * w + x] = if (border) 0xFFF0F0F0.toInt() else (0xFF shl 24) or (shade.coerceAtLeast(0) shl 16) or ((shade * 2 / 3) shl 8) or (shade / 3)
        }
        // Letters
        for (y in 44 until 56) for (x in 60 until 140) if ((x / 3) % 2 == 0) px[y * w + x] = 0xFFFFFFFF.toInt()
        return px
    }

    @Test
    fun `a dark gradient caption box is found around its text`() {
        val w = 200
        val h = 100
        val box = BubbleFinder.boxOf(captionPage(w, h), w, h, 60, 44, 80, 12, 12).shouldNotBeNull()
        (box.left <= 30 && box.right >= 170 && box.top <= 30 && box.bottom >= 70) shouldBe true
    }

    @Test
    fun `text on an open drawing has no box`() {
        val w = 200
        val h = 100
        val px = IntArray(w * h) { 0xFF223344.toInt() }
        for (y in 44 until 56) for (x in 60 until 140) if ((x / 3) % 2 == 0) px[y * w + x] = 0xFFFFFFFF.toInt()
        BubbleFinder.boxOf(px, w, h, 60, 44, 80, 12, 12).shouldBeNull()
    }
}
