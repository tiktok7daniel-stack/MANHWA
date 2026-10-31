package eu.kanade.manhwa

import eu.kanade.translation.translator.TextSanitizer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleSoundsTest {

    @Test
    fun `a bracket left open or closed alone is dropped`() {
        TextSanitizer.balanceBrackets("هاه...)") shouldBe "هاه..."
        TextSanitizer.balanceBrackets("(هاه...") shouldBe "هاه..."
    }

    @Test
    fun `paired brackets stay`() {
        TextSanitizer.balanceBrackets("(تنهد) حسناً") shouldBe "(تنهد) حسناً"
        TextSanitizer.balanceBrackets("【المهمة】") shouldBe "【المهمة】"
    }
}
