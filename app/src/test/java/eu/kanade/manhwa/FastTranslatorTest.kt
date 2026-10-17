package eu.kanade.manhwa

import eu.kanade.translation.translator.FastTranslatorRules
import eu.kanade.translation.translator.FastTranslatorRules.Engine
import eu.kanade.translation.translator.FastTranslatorRules.Keys
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FastTranslatorTest {

    @Test
    fun `engines with a key come first then google then the phone`() {
        FastTranslatorRules.order(Keys(), null) shouldBe listOf(Engine.GOOGLE, Engine.MLKIT)
        FastTranslatorRules.order(Keys(deepl = "k:fx"), null) shouldBe listOf(Engine.DEEPL, Engine.GOOGLE, Engine.MLKIT)
        FastTranslatorRules.order(Keys(deepl = "a", microsoft = "b", googleCloud = "c"), Engine.AUTO) shouldBe
            listOf(Engine.DEEPL, Engine.MICROSOFT, Engine.GOOGLE_CLOUD, Engine.GOOGLE, Engine.MLKIT)
    }

    @Test
    fun `the chosen engine goes first only when it can run`() {
        FastTranslatorRules.order(Keys(deepl = "a", microsoft = "b"), Engine.MICROSOFT) shouldBe
            listOf(Engine.MICROSOFT, Engine.DEEPL, Engine.GOOGLE, Engine.MLKIT)
        FastTranslatorRules.order(Keys(), Engine.DEEPL) shouldBe listOf(Engine.GOOGLE, Engine.MLKIT)
        FastTranslatorRules.order(Keys(), Engine.MLKIT) shouldBe listOf(Engine.MLKIT, Engine.GOOGLE)
    }

    @Test
    fun `free deepl keys use the free server`() {
        FastTranslatorRules.deeplUrl("abc:fx") shouldBe "https://api-free.deepl.com/v2/translate"
        FastTranslatorRules.deeplUrl("abc") shouldBe "https://api.deepl.com/v2/translate"
    }

    @Test
    fun `language codes per engine`() {
        FastTranslatorRules.deeplTarget("ar") shouldBe "AR"
        FastTranslatorRules.deeplTarget("en") shouldBe "EN-US"
        FastTranslatorRules.deeplTarget("zh-CN") shouldBe "ZH-HANS"
        FastTranslatorRules.microsoftTarget("ar") shouldBe "ar"
        FastTranslatorRules.microsoftTarget("zh-TW") shouldBe "zh-Hant"
    }

    @Test
    fun `batch sizes and out of quota codes`() {
        FastTranslatorRules.batchSize(Engine.DEEPL) shouldBe 50
        FastTranslatorRules.batchSize(Engine.MICROSOFT) shouldBe 100
        FastTranslatorRules.batchSize(Engine.GOOGLE) shouldBe 1
        FastTranslatorRules.engineOut(456) shouldBe true
        FastTranslatorRules.engineOut(429) shouldBe true
        FastTranslatorRules.engineOut(500) shouldBe false
    }

    @Test
    fun `glossary terms are put in whole words longest first`() {
        val glossary = mapOf("Sung Jinwoo" to "سونغ جين وو", "Sung" to "سونغ", "Jin" to "جين")
        FastTranslatorRules.protectTerms("sung jinwoo attacks", glossary) shouldBe "سونغ جين وو attacks"
        FastTranslatorRules.protectTerms("Jinx and Jin", glossary) shouldBe "Jinx and جين"
    }

    @Test
    fun `usage survives a round trip and resets on a new month`() {
        val stored = FastTranslatorRules.formatUsage("2026-10", mapOf("DEEPL" to 1200L, "GOOGLE" to 30L))
        FastTranslatorRules.parseUsage(stored, "2026-10") shouldBe mapOf("DEEPL" to 1200L, "GOOGLE" to 30L)
        FastTranslatorRules.parseUsage(stored, "2026-11") shouldBe emptyMap()
        FastTranslatorRules.parseUsage("", "2026-10") shouldBe emptyMap()
    }
}
