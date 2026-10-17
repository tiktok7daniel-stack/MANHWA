package eu.kanade.manhwa

import eu.kanade.translation.team.KeySites
import eu.kanade.translation.translator.AiTranslator.Provider
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KeySitesTest {

    @Test
    fun `a plain sk key refused by OpenAI is tried on NaraRouter next`() {
        val others = KeySites.skAlternatives("sk-abc123", "openai").map { it.id }
        others.first() shouldBe "nara"
        others.contains("openai") shouldBe false
        others.containsAll(listOf("deepseek", "siliconflow", "moonshot", "qwen")) shouldBe true
        // Refused on NaraRouter: OpenAI comes first among the rest
        KeySites.skAlternatives("sk-abc123", "nara").first().id shouldBe "openai"
    }

    @Test
    fun `keys that tell their own site are never tried elsewhere`() {
        KeySites.skAlternatives("sk-or-v1-abc", "openrouter") shouldBe emptyList()
        KeySites.skAlternatives("sk-ant-abc", "claude") shouldBe emptyList()
        KeySites.skAlternatives("gsk_abc", "groq") shouldBe emptyList()
        KeySites.skAlternatives("AIzaabc", "gemini") shouldBe emptyList()
        // A plain sk key put on a site whose keys look different stays there
        KeySites.skAlternatives("sk-abc123", "groq") shouldBe emptyList()
    }

    @Test
    fun `free models come first on a router with no known good model`() {
        val nara = listOf("gpt-4o", "deepseek-v3", "llama-3.3-70b-free", "qwen3-235b:free", "text-embedding-3-small")
        KeySites.bestModel(nara, Provider.OPENAI_COMPATIBLE, "") shouldBe "qwen3-235b:free"
        KeySites.bestModel(nara, Provider.OPENAI_COMPATIBLE, "", exclude = setOf("qwen3-235b:free")) shouldBe "llama-3.3-70b-free"
        // No free model: the best by name
        KeySites.bestModel(listOf("gpt-4o", "deepseek-v3"), Provider.OPENAI_COMPATIBLE, "") shouldBe "deepseek-v3"
        // A site whose known good model is there keeps it
        KeySites.bestModel(listOf("deepseek-chat", "deepseek-free"), Provider.DEEPSEEK, "deepseek-chat") shouldBe "deepseek-chat"
    }

    @Test
    fun `free is read from the model name only as a word`() {
        KeySites.isFree("meta-llama/llama-3.3-70b:free") shouldBe true
        KeySites.isFree("Llama-3.3-70B-Instruct-Turbo-Free") shouldBe true
        KeySites.isFree("freedom-7b") shouldBe false
        KeySites.isFree("gpt-4o") shouldBe false
    }
}
