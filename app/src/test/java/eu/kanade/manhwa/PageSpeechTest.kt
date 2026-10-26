package eu.kanade.manhwa

import eu.kanade.translation.speech.PageSpeechRules
import eu.kanade.translation.speech.TextLine
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageSpeechTest {

    @Test
    fun `Arabic read from left to right comes back in reading order`() {
        PageSpeechRules.reverseArabic("ءايحألا ةوهقلا") shouldBe "القهوة الأحياء"
        PageSpeechRules.reverseArabic("coffee") shouldBe "coffee"
    }

    @Test
    fun `stacked lines are one bubble and bubbles go top down then right to left`() {
        val lines = listOf(
            // A bubble on the left, two lines
            TextLine("لم يكن بوسعي", 100, 100, 300, 40),
            TextLine("حتى الحلم", 120, 145, 260, 40),
            // A bubble on the right, same row
            TextLine("مرحبا بك", 600, 110, 200, 40),
            // A bubble lower down
            TextLine("في الأحياء الفقيرة", 200, 700, 400, 40),
            // A page number: left out
            TextLine("7", 10, 1200, 20, 30),
        )
        PageSpeechRules.bubbles(lines) shouldBe listOf("مرحبا بك", "لم يكن بوسعي حتى الحلم", "في الأحياء الفقيرة")
    }

    @Test
    fun `a page without text has nothing to say`() {
        PageSpeechRules.bubbles(emptyList()) shouldBe emptyList()
        PageSpeechRules.isArabic("hello") shouldBe false
        PageSpeechRules.isArabic("مرحبا") shouldBe true
    }
}
