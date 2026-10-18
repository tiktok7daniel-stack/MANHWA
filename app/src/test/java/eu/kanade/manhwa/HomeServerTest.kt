package eu.kanade.manhwa

import eu.kanade.translation.team.LocalServerRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HomeServerTest {

    @Test
    fun `the laptop translates while it answers and the others take over when it does not`() {
        val home = listOf("laptop")
        val others = listOf("gemini", "groq")
        LocalServerRules.pick(LocalServerRules.AUTO, answering = home, home = home, others = others) shouldBe home
        LocalServerRules.pick(LocalServerRules.AUTO, answering = emptyList(), home = home, others = others) shouldBe others
        // No other key: nothing, so the phone model translates
        LocalServerRules.pick(LocalServerRules.AUTO, answering = emptyList(), home = home, others = emptyList<String>()) shouldBe emptyList()
        LocalServerRules.pick(LocalServerRules.HOME, answering = emptyList(), home = home, others = others) shouldBe home
        LocalServerRules.pick(LocalServerRules.CLOUD, answering = home, home = home, others = others) shouldBe others
    }

    @Test
    fun `rocket mode only while the laptop translates and the reader keeps it on`() {
        LocalServerRules.rocket(enabled = true, laptopServing = true) shouldBe true
        LocalServerRules.rocket(enabled = true, laptopServing = false) shouldBe false
        LocalServerRules.rocket(enabled = false, laptopServing = true) shouldBe false
    }

    @Test
    fun `a ping is trusted for five minutes`() {
        LocalServerRules.fresh(at = 1_000, now = 1_000 + 60_000) shouldBe true
        LocalServerRules.fresh(at = 1_000, now = 1_000 + 5 * 60_000) shouldBe false
        LocalServerRules.fresh(at = 10_000, now = 1_000) shouldBe false
        // Away: asked again after half a minute
        LocalServerRules.fresh(at = 1_000, now = 1_000 + 20_000, answered = false) shouldBe true
        LocalServerRules.fresh(at = 1_000, now = 1_000 + 30_000, answered = false) shouldBe false
    }

    @Test
    fun `a typed address becomes a full server address`() {
        LocalServerRules.normalize("192.168.1.50:11434") shouldBe "http://192.168.1.50:11434/v1"
        LocalServerRules.normalize("http://laptop-name:11434/v1/") shouldBe "http://laptop-name:11434/v1"
        LocalServerRules.normalize("http://192.168.1.50:1234/v1/models") shouldBe "http://192.168.1.50:1234/v1"
        LocalServerRules.normalize("  ") shouldBe ""
        LocalServerRules.modelsUrl("192.168.1.50:11434") shouldBe "http://192.168.1.50:11434/v1/models"
    }

    @Test
    fun `the fastest strong ready model is picked from what the laptop has`() {
        LocalServerRules.preferredModel(listOf("llama3.1:8b", "gemma3:12b", "aya-expanse:8b")) shouldBe "aya-expanse:8b"
        LocalServerRules.preferredModel(listOf("llama3.1:8b", "gemma3:12b")) shouldBe "gemma3:12b"
        LocalServerRules.preferredModel(listOf("llama3.1:8b")) shouldBe "llama3.1:8b"
        LocalServerRules.preferredModel(emptyList()) shouldBe null
    }

    @Test
    fun `the models of an OpenAI style list are read`() {
        LocalServerRules.modelIds("""{"object":"list","data":[{"id":"qwen2.5:7b"},{"id":"llama3.1:8b"}]}""") shouldBe listOf("qwen2.5:7b", "llama3.1:8b")
        LocalServerRules.modelIds("not json") shouldBe null
    }
}
