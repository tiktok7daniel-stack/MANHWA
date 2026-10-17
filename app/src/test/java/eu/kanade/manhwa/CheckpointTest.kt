package eu.kanade.manhwa

import eu.kanade.translation.CheckpointRules
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CheckpointRulesTest {

    private fun block(text: String, translation: String = "") =
        TranslationBlock(text = text, translation = translation, width = 10f, height = 10f, x = 0f, y = 0f, symHeight = 1f, symWidth = 1f, angle = 0f)

    private fun page(vararg blocks: TranslationBlock) = PageTranslation(blocks = blocks.toMutableList())

    @Test
    fun `pages translated before the app closed are put back and not sent again`() {
        val pages = mutableMapOf("001.jpg" to page(block("Hello"), block("Run!")), "002.jpg" to page(block("Wait")))
        val kept = mapOf("001.jpg" to page(block("Hello", "مرحبا"), block("Run!", "اجري!")))
        CheckpointRules.applyDone(pages, kept) shouldBe setOf("001.jpg")
        pages.getValue("001.jpg").blocks.map { it.translation } shouldBe listOf("مرحبا", "اجري!")
        pages.getValue("002.jpg").blocks.single().translation shouldBe ""
    }

    @Test
    fun `a page read differently this time is translated again`() {
        val pages = mutableMapOf("001.jpg" to page(block("Hello"), block("Run")))
        CheckpointRules.applyDone(pages, mapOf("001.jpg" to page(block("Hello", "مرحبا")))) shouldBe emptySet()
        CheckpointRules.applyDone(pages, mapOf("001.jpg" to page(block("Hello", "مرحبا"), block("Ran", "جرى")))) shouldBe emptySet()
        CheckpointRules.applyDone(pages, mapOf("001.jpg" to page(block("Hello"), block("Run")))) shouldBe emptySet()
        pages.getValue("001.jpg").blocks.map { it.translation } shouldBe listOf("", "")
    }
}
