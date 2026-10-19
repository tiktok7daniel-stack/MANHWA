package eu.kanade.manhwa

import eu.kanade.translation.translator.OfflineRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OfflineRulesTest {

    @Test
    fun `the language of a bubble is read from its letters`() {
        OfflineRules.sourceOf("WHAT ARE YOU DOING?!", "zh") shouldBe "en"
        OfflineRules.sourceOf("안녕하세요 Jin", "en") shouldBe "ko"
        OfflineRules.sourceOf("ありがとう", "en") shouldBe "ja"
        OfflineRules.sourceOf("你好", "en") shouldBe "zh"
        OfflineRules.sourceOf("...", "ko") shouldBe "ko"
    }

    @Test
    fun `english in capitals goes in sentence case and other languages stay`() {
        OfflineRules.prepare("I TOLD YOU TO RUN.", "en") shouldBe "I told you to run."
        OfflineRules.prepare("안녕", "ko") shouldBe "안녕"
    }
}
