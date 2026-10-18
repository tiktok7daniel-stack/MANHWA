package eu.kanade.manhwa

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.EnglishGuard
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EnglishGuardTest {

    private fun block(text: String, translation: String, y: Float, x: Float = 0f) =
        TranslationBlock(text = text, translation = translation, width = 100f, height = 20f, x = x, y = y, symHeight = 20f, symWidth = 10f, angle = 0f)

    @Test
    fun `a line left word for word right under an Arabic line is joined into it`() {
        val arabic = block("I WILL NOT", "لن أسمح بهذا أبدًا", y = 0f)
        val left = block("LET THIS GO", "LET THIS GO", y = 25f)
        EnglishGuard.joined(left, listOf(arabic, left)) shouldBe true
    }

    @Test
    fun `a far line or one in another bubble is not joined and Google fixes it`() {
        val arabic = block("I WILL NOT", "لن أسمح بهذا", y = 0f)
        val far = block("YES SIR", "YES SIR", y = 300f)
        EnglishGuard.joined(far, listOf(arabic, far)) shouldBe false
        val inBubble = arabic.apply { bubbleX = 0f; bubbleY = 0f; bubbleW = 100f; bubbleH = 50f }
        val other = block("MOONLIGHT STRIKE", "MOONLIGHT STRIKE", y = 30f).apply { bubbleX = 0f; bubbleY = 60f; bubbleW = 100f; bubbleH = 60f }
        other.y = 70f
        EnglishGuard.joined(other, listOf(inBubble, other)) shouldBe false
    }

    @Test
    fun `a line with its own English answer is never counted as joined`() {
        val arabic = block("I WILL NOT", "لن أسمح بهذا", y = 0f)
        val wrong = block("LET THIS GO", "Let it go", y = 25f)
        EnglishGuard.joined(wrong, listOf(arabic, wrong)) shouldBe false
        EnglishGuard.leftInSource(wrong) shouldBe true
    }
}
