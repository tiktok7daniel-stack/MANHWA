package eu.kanade.manhwa

import eu.kanade.translation.glossary.GlossaryEnforcer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GlossaryEnforcerTest {

    private val glossary = mapOf("Sung Jinwoo" to "سونغ جين وو", "Cha Haein" to "تشا هاي إن", "Hunter" to "صياد")

    @Test
    fun `a name spelt a little differently gets the glossary spelling`() {
        GlossaryEnforcer.fix("Sung Jinwoo is here", "سونج جين وو هنا", glossary) shouldBe "سونغ جين وو هنا"
        GlossaryEnforcer.fix("Cha Haein attacks", "تشا هاي ان تهاجم", glossary) shouldBe "تشا هاي ان تهاجم"
    }

    @Test
    fun `a joined letter or the article in front of the name stays`() {
        GlossaryEnforcer.fix("and Sung Jinwoo left", "وسونق جين وو غادر", glossary) shouldBe "وسونغ جين وو غادر"
    }

    @Test
    fun `nothing changes when the name is already right, missing or not in the source`() {
        GlossaryEnforcer.fix("Sung Jinwoo is here", "سونغ جين وو هنا", glossary) shouldBe "سونغ جين وو هنا"
        GlossaryEnforcer.fix("Sung Jinwoo is here", "هو هنا", glossary) shouldBe "هو هنا"
        GlossaryEnforcer.fix("He is here", "سونج جين وو هنا", glossary) shouldBe "سونج جين وو هنا"
    }

    @Test
    fun `short terms are never guessed at`() {
        GlossaryEnforcer.fix("A Hunter came", "جاء صائد", glossary) shouldBe "جاء صائد"
    }

    @Test
    fun `the series glossary comes before the codex and wins over it`() {
        val merged = GlossaryEnforcer.seriesFirst(mapOf("Gate" to "بوابة الظل"), mapOf("Gate" to "بوابة", "Mana" to "مانا"))
        merged.keys.toList() shouldBe listOf("Gate", "Mana")
        merged["Gate"] shouldBe "بوابة الظل"
    }
}
