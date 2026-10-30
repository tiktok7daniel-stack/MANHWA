package eu.kanade.manhwa

import eu.kanade.tachiyomi.ui.reader.AutoColors
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutoColorsTest {

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `a washed-out page gets more contrast and a grey one no colour`() {
        val washed = IntArray(1000) { grey(70 + (it % 120)) }
        val tune = AutoColors.tuneFor(washed)
        tune.contrast shouldBeGreaterThan 0
        tune.saturation shouldBe 0
    }

    @Test
    fun `a page that uses the whole range is barely touched`() {
        val full = IntArray(1000) { grey(it * 255 / 999) }
        val tune = AutoColors.tuneFor(full)
        tune.contrast shouldBe 0
        tune.saturation shouldBe 0
    }

    @Test
    fun `a dull coloured page gets more colour`() {
        // Muted blue-grey: a little colour, not much
        val dull = IntArray(1000) { i -> val v = 40 + i % 200; (0xFF shl 24) or ((v * 85 / 100) shl 16) or ((v * 90 / 100) shl 8) or v }
        AutoColors.tuneFor(dull).saturation shouldBeGreaterThan 0
    }
}
