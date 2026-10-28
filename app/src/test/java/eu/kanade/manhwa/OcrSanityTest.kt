package eu.kanade.manhwa

import eu.kanade.translation.live.OcrSanity
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class OcrSanityTest {

    // Unit tests run from the app module, where the bundled filter lives
    private val filter = OcrSanity.WordFilter.read(File("src/main/assets/english_words.bloom").readBytes())

    @Test
    fun `the bundled filter knows common words`() {
        val f = filter.shouldNotBeNull()
        listOf("fighter", "progressing", "know", "war", "peace", "shadow", "imperial", "sword").forEach { f.has(it) shouldBe true }
    }

    @Test
    fun `misread lines are garbled and real ones are not`() {
        val f = filter.shouldNotBeNull()
        OcrSanity.garbled("Nimgng flmlvs impti shord fokm", f::has) shouldBe true
        OcrSanity.garbled("How is the shadow fighter learning the imperial sword form progressing?", f::has) shouldBe false
        OcrSanity.garbled("WHY MUST I GO TO SUCH LENGTHS TO STAY ALIVE...?", f::has) shouldBe false
        OcrSanity.garbled("I-I DON'T KNOW...", f::has) shouldBe false
    }

    @Test
    fun `one odd word alone is not garbled`() {
        OcrSanity.garbled("Kraaah!", { false }) shouldBe false
    }
}
