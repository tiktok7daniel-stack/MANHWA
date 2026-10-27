package eu.kanade.manhwa

import eu.kanade.translation.render.BubbleInk
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleInkTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `a white bubble gets near black ink and a black one white ink`() {
        BubbleInk.on(white) shouldBe 0xFF111111.toInt()
        BubbleInk.on(black) shouldBe white
    }

    @Test
    fun `ink on coloured bubbles always reads clearly`() {
        listOf(0xFFFFC0CB, 0xFFFFE066, 0xFF87CEEB, 0xFFE53935, 0xFF1E3A8A, 0xFF2E7D32, 0xFF9C27B0, 0xFFFF9800, 0xFF808080)
            .map { it.toInt() }
            .forEach { bg -> BubbleInk.contrast(BubbleInk.on(bg), bg) shouldBeGreaterThanOrEqual 4.0 }
    }

    @Test
    fun `a pink bubble gets a deep shade of its own hue rather than plain black`() {
        val ink = BubbleInk.on(0xFFFFC0CB.toInt())
        (ink != 0xFF111111.toInt()) shouldBe true
        // Red stays the strongest channel of the ink
        ((ink shr 16 and 0xFF) >= (ink and 0xFF)) shouldBe true
    }
}
