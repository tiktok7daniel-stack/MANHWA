package eu.kanade.manhwa

import eu.kanade.translation.team.KeySites
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GeminiPickTest {

    private val live = listOf(
        "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-pro", "gemini-3.5-pro-preview-06", "gemini-3-flash", "gemini-pro-latest",
    )

    @Test
    fun `the newest stable Pro is picked`() {
        KeySites.newestGemini(live, "pro") shouldBe "gemini-3-pro"
        KeySites.newestGemini(live, "flash") shouldBe "gemini-3-flash"
    }

    @Test
    fun `a preview counts only when no stable one is there`() {
        KeySites.newestGemini(listOf("gemini-3-pro-preview-05", "gemini-2.5-flash"), "pro") shouldBe "gemini-3-pro-preview-05"
    }

    @Test
    fun `the alias is used when nothing else is`() {
        KeySites.newestGemini(listOf("gemini-pro-latest", "gemini-2.0-flash"), "pro") shouldBe "gemini-pro-latest"
        KeySites.newestGemini(listOf("gemini-2.0-flash"), "pro").shouldBeNull()
    }
}
