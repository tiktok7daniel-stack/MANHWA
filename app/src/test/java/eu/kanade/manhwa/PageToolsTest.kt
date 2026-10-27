package eu.kanade.manhwa

import eu.kanade.tachiyomi.ui.reader.CreditPages
import eu.kanade.tachiyomi.ui.reader.PageTune
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageToolsTest {

    @Test
    fun `a neutral tune leaves every colour as it is`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        val m = PageTune(0, 0, 0, 0).values()
        identity.indices.all { kotlin.math.abs(identity[it] - m[it]) < 1e-4f } shouldBe true
        PageTune(0, 0, 0, 0).isNeutral shouldBe true
    }

    @Test
    fun `brightness lifts every channel by the same step`() {
        val m = PageTune(brightness = 50, contrast = 0, saturation = 0, warmth = 0).values()
        (m[4] == m[9] && m[9] == m[14] && m[4] > 0f) shouldBe true
    }

    @Test
    fun `warmth raises red and lowers blue`() {
        val m = PageTune(brightness = 0, contrast = 0, saturation = 0, warmth = 100).values()
        (m[0] > 1f && m[12] < 1f) shouldBe true
    }

    @Test
    fun `two group words make a credit page`() {
        CreditPages.textLooksLikeCredit("Join our Discord! We are recruiting a translator") shouldBe true
        CreditPages.textLooksLikeCredit("انضم لقناتنا على ديسكورد") shouldBe true
    }

    @Test
    fun `story text is not a credit page`() {
        CreditPages.textLooksLikeCredit("I will never forgive you for what you did to my sister") shouldBe false
        CreditPages.textLooksLikeCredit("Discord between the clans grew") shouldBe false
        CreditPages.textLooksLikeCredit("") shouldBe false
    }
}
