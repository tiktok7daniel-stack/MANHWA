package eu.kanade.manhwa

import eu.kanade.translation.render.LetterMask
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LetterMaskTest {

    private val w = 40
    private val h = 30

    /** A busy drawing: stripes of two greys, everywhere. */
    private fun art() = IntArray(w * h) { i -> if ((i % w) / 3 % 2 == 0) 0xFF505050.toInt() else 0xFF8A8A8A.toInt() }

    @Test
    fun `white letters over a drawing are found and the drawing between them is kept`() {
        val px = art()
        // "Letters": white vertical bars inside the box (10..30, 10..20)
        for (y in 10 until 20) for (x in 10 until 30) if (x % 4 == 0) px[y * w + x] = 0xFFFFFFFF.toInt()
        val mask = LetterMask.build(px, w, h, 10, 10, 30, 20, 0).shouldNotBeNull()
        mask[12 * w + 12] shouldBe true
        // A grey pixel between the bars is the drawing: kept
        mask[12 * w + 13] shouldBe false
        // Nothing outside the box
        mask[2 * w + 2] shouldBe false
    }

    @Test
    fun `a box that looks like its surroundings gives no mask`() {
        LetterMask.build(art(), w, h, 10, 10, 30, 20, 1).shouldBeNull()
    }
}
