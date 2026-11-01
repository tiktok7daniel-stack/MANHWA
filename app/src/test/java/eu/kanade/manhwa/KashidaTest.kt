package eu.kanade.manhwa

import eu.kanade.translation.render.Kashida
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KashidaTest {

    /** Every letter one unit wide, as a stand-in for the font. */
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `a line is stretched inside its words up to the target`() {
        val out = Kashida.justify("الفريق الذي", 14f, measure)
        out.length shouldBe 14
        out.replace("ـ", "") shouldBe "الفريق الذي"
    }

    @Test
    fun `never after a letter that does not join, nor inside lam alef`() {
        Kashida.places("دار") shouldBe emptyList()
        Kashida.places("لا") shouldBe emptyList()
    }

    @Test
    fun `a line already wide enough stays as it is`() {
        Kashida.justify("سلام", 4f, measure) shouldBe "سلام"
    }
}
