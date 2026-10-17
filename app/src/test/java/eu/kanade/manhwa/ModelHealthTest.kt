package eu.kanade.manhwa

import eu.kanade.translation.team.KeySites
import eu.kanade.translation.team.KeyReportRules as R
import eu.kanade.translation.team.ModelHealthManager
import eu.kanade.translation.team.ModelHealthManager.Problem
import eu.kanade.translation.team.ModelHealthManager.Status
import eu.kanade.translation.team.QuotaForecast
import eu.kanade.translation.translator.AiTranslator.Provider
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModelHealthTest {

    @Test
    fun `cohere answer limit is read from the error`() {
        ModelHealthManager.classify(400, "HTTP 400: invalid request: max_tokens must be less than or equal to 8192") shouldBe Problem.MaxTokens(8192)
        ModelHealthManager.classify(400, "max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens") shouldBe Problem.MaxTokens(8192)
    }

    @Test
    fun `a model name with numbers is not a limit`() {
        ModelHealthManager.readLimit("model command-a-03-2025: max_tokens must be less than or equal to 8192") shouldBe 8192
    }

    @Test
    fun `groq dead model is a model problem`() {
        ModelHealthManager.classify(404, "HTTP 404: The model `llama-3.3-70b-versatile` does not exist or you do not have access to it.") shouldBe
            Problem.ModelInvalid
        ModelHealthManager.classify(400, "model_not_found: the model has been decommissioned") shouldBe Problem.ModelInvalid
    }

    @Test
    fun `a wrong address is not a dead model`() {
        ModelHealthManager.classify(404, "404 page not found") shouldBe Problem.Other
    }

    @Test
    fun `rate limits keep the wait the site asks for`() {
        ModelHealthManager.classify(429, "rate limit", "12") shouldBe Problem.RateLimited(12_000)
        ModelHealthManager.classify(429, "Rate limit reached. Please try again in 7.5s") shouldBe Problem.RateLimited(7_500)
        ModelHealthManager.classify(429, "slow down") shouldBe Problem.RateLimited(null)
    }

    @Test
    fun `refused keys, server errors and long requests`() {
        ModelHealthManager.classify(401, "Invalid API key") shouldBe Problem.AuthFailed
        ModelHealthManager.classify(403, "Forbidden") shouldBe Problem.AuthFailed
        ModelHealthManager.classify(503, "overloaded") shouldBe Problem.Temporary
        ModelHealthManager.classify(500, "") shouldBe Problem.Temporary
        ModelHealthManager.classify(null, "timeout") shouldBe Problem.Temporary
        ModelHealthManager.classify(400, "This endpoint's maximum context length is 131072 tokens. However, you requested about 140000 tokens") shouldBe
            Problem.ContextTooLong(131072)
    }

    @Test
    fun `a temporary error never puts a key out, two refusals do`() {
        ModelHealthManager.statusAfter(Problem.Temporary, 0) shouldBe Status.TEMPORARY_ERROR
        ModelHealthManager.statusAfter(Problem.AuthFailed, 1) shouldBe Status.AUTH_FAILED
        ModelHealthManager.statusAfter(Problem.AuthFailed, 2) shouldBe Status.DISABLED
        ModelHealthManager.statusAfter(Problem.RateLimited(null), 0) shouldBe Status.RATE_LIMITED
        ModelHealthManager.statusAfter(Problem.ModelInvalid, 0) shouldBe Status.MODEL_INVALID
    }

    @Test
    fun `router ranking leaves out disabled and resting keys`() {
        fun c(id: String, status: Status, cooldown: Long = 0, strength: Int = 50, quota: Int = 80, errors: Float = 0f) =
            ModelHealthManager.Candidate(id, status, cooldown, strength, quota, errors, 1000, 0)
        val ranked = ModelHealthManager.rank(
            listOf(
                c("a", Status.ACTIVE),
                c("b", Status.RATE_LIMITED, cooldown = 5_000, strength = 90),
                c("c", Status.DISABLED, strength = 99),
                c("d", Status.ACTIVE, strength = 70),
                c("e", Status.ACTIVE, strength = 99, errors = 0.6f),
            ),
            now = 1_000,
        ).map { it.id }
        ranked shouldBe listOf("d", "a", "e")
    }

    @Test
    fun `when every key rests, the one back first is tried`() {
        val ranked = ModelHealthManager.rank(
            listOf(
                ModelHealthManager.Candidate("late", Status.RATE_LIMITED, 9_000, 50, 50, 0f, 0, 0),
                ModelHealthManager.Candidate("soon", Status.TEMPORARY_ERROR, 2_000, 50, 50, 0f, 0, 0),
                ModelHealthManager.Candidate("out", Status.AUTH_FAILED, 0, 50, 50, 0f, 0, 0),
            ),
            now = 1_000,
        ).map { it.id }
        ranked shouldBe listOf("soon", "late")
    }

    @Test
    fun `best model skips dead models`() {
        val groq = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b", "whisper-large-v3")
        KeySites.bestModel(groq, Provider.GROQ, "llama-3.3-70b-versatile") shouldBe "llama-3.3-70b-versatile"
        KeySites.bestModel(groq, Provider.GROQ, "llama-3.3-70b-versatile", exclude = setOf("llama-3.3-70b-versatile")) shouldBe "qwen/qwen3-32b"
        KeySites.bestModel(listOf("whisper-large-v3"), Provider.GROQ, "") shouldBe null
        KeySites.bestModel(listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro"), Provider.GEMINI, "gemini-2.5-flash") shouldBe "gemini-2.5-flash"
    }

    @Test
    fun `quota forecast`() {
        QuotaForecast.callsLeft(percentLeft = 50, usedToday = 30) shouldBe 30
        QuotaForecast.chaptersLeft(percentLeft = 50, usedToday = 30, callsPerChapter = 6f) shouldBe 5
        QuotaForecast.chaptersLeft(percentLeft = 0, usedToday = 30, callsPerChapter = 6f) shouldBe 0
        QuotaForecast.callsLeft(percentLeft = 100, usedToday = 0) shouldBe null
    }

    @Test
    fun `the new key sites are told from their keys and each has its own id`() {
        KeySites.forKey("nvapi-abc")?.id shouldBe "nvidia"
        KeySites.forKey("hf_abc")?.id shouldBe "huggingface"
        KeySites.forKey("xai-abc")?.id shouldBe "xai"
        KeySites.all.map { it.id }.toSet().size shouldBe KeySites.all.size
        KeySites.byUrl("https://integrate.api.nvidia.com/v1")?.id shouldBe "nvidia"
    }

    @Test
    fun `the best keys report scores keys by how they did lately`() {
        val unused = R.Totals(ok = 0, failed = 0, avgLatencyMs = 0)
        R.score(unused, 80) shouldBe 0
        R.verdict(unused, 0) shouldBe R.Verdict.UNUSED
        val great = R.Totals(ok = 60, failed = 2, avgLatencyMs = 2_500)
        R.verdict(great, R.score(great, 80)) shouldBe R.Verdict.EXCELLENT
        val bad = R.Totals(ok = 5, failed = 10, avgLatencyMs = 20_000)
        R.verdict(bad, R.score(bad, 40)) shouldBe R.Verdict.WEAK
        (R.score(great, 80) > R.score(bad, 40)) shouldBe true
    }

    @Test
    fun `sites are ranked by their used keys`() {
        R.rankSites(listOf("groq" to 90, "groq" to 70, "gemini" to 60, "mistral" to 0)) shouldBe listOf("groq" to 80, "gemini" to 60)
    }
}

