package eu.kanade.manhwa

import eu.kanade.translation.live.PageOcr
import eu.kanade.translation.model.LineBox
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.OcrFusion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StripCutTest {

    private fun block(vararg lines: Pair<String, LineBox>) = TranslationBlock(
        text = lines.joinToString("\n") { it.first },
        width = 100f,
        height = lines.maxOf { it.second.y + it.second.h },
        x = 0f,
        y = lines.minOf { it.second.y },
        symWidth = 10f,
        symHeight = 10f,
        angle = 0f,
        lines = lines.map { it.second },
    )

    @Test
    fun `a line cut by the bottom edge of a strip is left for the next strip`() {
        val b = block("WHO ARE YOU" to LineBox(0f, 900f, 100f, 30f), "IW" to LineBox(0f, 975f, 100f, 25f))
        val kept = PageOcr.dropCutLines(b, cutTop = false, cutBottom = true, stripH = 1000f)!!
        kept.text shouldBe "WHO ARE YOU"
        kept.lineBoxes.size shouldBe 1
    }

    @Test
    fun `a block cut whole is dropped and a whole one is kept`() {
        val cut = block("IF" to LineBox(0f, 0f, 100f, 20f))
        PageOcr.dropCutLines(cut, cutTop = true, cutBottom = false, stripH = 1000f) shouldBe null
        val whole = block("HELLO" to LineBox(0f, 400f, 100f, 30f))
        PageOcr.dropCutLines(whole, cutTop = true, cutBottom = true, stripH = 1000f) shouldBe whole
    }

    @Test
    fun `a short line only paddle saw needs more confidence`() {
        OcrFusion.addMin("IW") shouldBe OcrFusion.ADD_MIN_SHORT
        OcrFusion.addMin("WHERE ARE YOU") shouldBe OcrFusion.ADD_MIN
    }

    @Test
    fun `a line taller than the overlap is kept even when cut`() {
        val big = block("BOOM" to LineBox(0f, 700f, 100f, 300f))
        PageOcr.dropCutLines(big, cutTop = false, cutBottom = true, stripH = 1000f, maxLineH = 200f) shouldBe big
    }
}
