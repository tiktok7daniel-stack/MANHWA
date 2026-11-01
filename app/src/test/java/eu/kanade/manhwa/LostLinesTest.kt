package eu.kanade.manhwa

import eu.kanade.translation.translator.LostLines
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LostLinesTest {

    @Test
    fun `the lines of one bubble are one group`() {
        // "PHYSICAL-" / "TYPE, RISK LEVEL:" / "INCOMPREHENSIBLE." under each other, 40 px high, 8 px apart
        val boxes = listOf(
            floatArrayOf(300f, 100f, 500f, 140f),
            floatArrayOf(240f, 148f, 560f, 188f),
            floatArrayOf(220f, 196f, 580f, 236f),
        )
        LostLines.clusters(boxes, listOf(40f, 40f, 40f)) shouldBe listOf(listOf(0, 1, 2))
    }

    @Test
    fun `two bubbles apart stay two`() {
        val boxes = listOf(floatArrayOf(0f, 0f, 200f, 40f), floatArrayOf(0f, 200f, 200f, 240f), floatArrayOf(600f, 40f, 800f, 80f))
        LostLines.clusters(boxes, listOf(40f, 40f, 40f)) shouldBe listOf(listOf(0), listOf(1), listOf(2))
    }
}
