package eu.kanade.manhwa

import eu.kanade.translation.translator.GoogleRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException

class GoogleRulesTest {

    @Test
    fun `bubbles in capitals are sent in sentence case`() {
        GoogleRules.prepare("WHAT ARE YOU DOING?! I TOLD YOU TO RUN.") shouldBe "What are you doing?! I told you to run."
        GoogleRules.prepare("I'M THE HUNTER. IT'S MINE") shouldBe "I'm the hunter. It's mine"
    }

    @Test
    fun `text already in normal case or too short is left as it is`() {
        GoogleRules.prepare("Hello there, Jin-Woo.") shouldBe "Hello there, Jin-Woo."
        GoogleRules.prepare("OK!") shouldBe "OK!"
        GoogleRules.prepare("안녕하세요") shouldBe "안녕하세요"
    }

    @Test
    fun `every sentence of the answer is joined`() {
        val body = """[[["مرحبا. ","Hello. ",null,null,10],["كيف حالك؟","How are you?",null,null,10]],null,"en"]"""
        GoogleRules.parse(body) shouldBe "مرحبا. كيف حالك؟"
    }

    @Test
    fun `an answer that is not a translation is refused`() {
        GoogleRules.parse("<html>Our systems have detected unusual traffic</html>") shouldBe null
        GoogleRules.parse("") shouldBe null
    }

    @Test
    fun `the wait grows each try and follows what Google asks`() {
        GoogleRules.waitMillis(1, IOException()) shouldBe 1000L
        GoogleRules.waitMillis(2, IOException()) shouldBe 2000L
        GoogleRules.waitMillis(3, null) shouldBe 4000L
        GoogleRules.waitMillis(1, GoogleRules.Busy(429, 5)) shouldBe 5000L
        GoogleRules.waitMillis(1, GoogleRules.Busy(429, 600)) shouldBe 30000L
    }

    @Test
    fun `no network fails at once and a busy answer is asked again`() {
        GoogleRules.worthRetry(java.net.UnknownHostException("translate.google.com")) shouldBe false
        GoogleRules.worthRetry(java.net.ConnectException()) shouldBe false
        GoogleRules.worthRetry(GoogleRules.Busy(429, null)) shouldBe true
        GoogleRules.worthRetry(java.net.SocketTimeoutException()) shouldBe true
    }
}
