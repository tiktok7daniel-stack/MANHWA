package eu.kanade.manhwa

import eu.kanade.translation.local.LocalAiRules
import eu.kanade.translation.local.LocalBenchmark
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalAiRulesTest {

    @Test
    fun `numbered answers are read in their places, with a missing one left empty`() {
        val reply = "1. أين أنت؟\n2) لا تقلق\n\n4- ابقَ خلفي"
        LocalAiRules.parseNumbered(reply, 4) shouldBe listOf("أين أنت؟", "لا تقلق", "", "ابقَ خلفي")
        LocalAiRules.parseNumbered("no numbers here", 2) shouldBe null
    }

    @Test
    fun `answers fall back to the JSON list`() {
        LocalAiRules.answers("{\"t\":[\"أ\",\"ب\"]}", 2) shouldBe listOf("أ", "ب")
        LocalAiRules.answers("1. أ\n2. ب", 2) shouldBe listOf("أ", "ب")
    }

    @Test
    fun `numbers past the count and repeats are ignored`() {
        LocalAiRules.parseNumbered("1. أ\n1. مكرر\n3. زائد", 2) shouldBe listOf("أ", "")
    }

    @Test
    fun `a draft is doubtful when it is missing, English, lost a name or runs far too long`() {
        val glossary = mapOf("Jinwoo" to "جين وو")
        LocalAiRules.doubtful("Hello", null, glossary, "ar") shouldBe true
        LocalAiRules.doubtful("Jinwoo, run!", "جين وو، اهرب!", glossary, "ar") shouldBe false
        LocalAiRules.doubtful("Jinwoo, run!", "اهرب!", glossary, "ar") shouldBe true
        LocalAiRules.doubtful("Run now", "اهرب now", glossary, "ar") shouldBe true
        LocalAiRules.doubtful("Go", "اذهب ".repeat(20), glossary, "ar") shouldBe true
        LocalAiRules.doubtful("Use the HP potion", "استخدم جرعة HP", glossary, "ar") shouldBe false
    }

    @Test
    fun `percentiles by nearest rank`() {
        val values = (1L..100L).toList().shuffled()
        LocalAiRules.percentile(values, 0.5) shouldBe 50L
        LocalAiRules.percentile(values, 0.95) shouldBe 95L
        LocalAiRules.percentile(emptyList(), 0.5) shouldBe 0L
    }

    @Test
    fun `chrF is 100 for the same line and falls as it drifts`() {
        LocalAiRules.chrF("لا تقلق، سأحميك.", "لا تقلق، سأحميك.") shouldBe 100.0
        val close = LocalAiRules.chrF("لا تقلق، سوف أحميك.", "لا تقلق، سأحميك.")
        val far = LocalAiRules.chrF("البوابة تغلق", "لا تقلق، سأحميك.")
        close shouldBeGreaterThan 50.0
        far shouldBeLessThan 20.0
        LocalAiRules.chrF("", "أ") shouldBe 0.0
    }

    @Test
    fun `call time grows with the lines and has a ceiling`() {
        LocalAiRules.timeoutFor(8) shouldBe 68_000L
        LocalAiRules.timeoutFor(12) shouldBe 92_000L
        LocalAiRules.timeoutFor(50) shouldBe 120_000L
    }

    @Test
    fun `the fast way asks fewer calls and numbered lines`() {
        LocalAiRules.Tuning.FAST.linesPerCall shouldBe 12
        LocalAiRules.Tuning.FAST.numbered shouldBe true
        LocalAiRules.Tuning.LEGACY.numbered shouldBe false
        LocalBenchmark.LINES.size shouldBe 24
    }
}
