package eu.kanade.manhwa

import eu.kanade.translation.local.LocalAiRules
import eu.kanade.translation.translator.AiTranslator
import eu.kanade.translation.translator.EnglishGuard
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlainLinesTest {

    @Test
    fun `only the glossary names found in the lines go with a plain call`() {
        val glossary = mapOf("Jinwoo" to "جين وو", "Hae-in" to "هاي إن", "Hunter Association" to "جمعية الصيادين")
        AiTranslator.plainTerms(listOf("Jinwoo, are you okay?", "Run!"), glossary) shouldBe mapOf("Jinwoo" to "جين وو")
        AiTranslator.plainTerms(listOf("The hunter association won't ignore this."), glossary) shouldBe mapOf("Hunter Association" to "جمعية الصيادين")
        // Part of another word is not the name
        AiTranslator.plainTerms(listOf("Jinwooo"), glossary) shouldBe emptyMap()
        AiTranslator.plainTerms(listOf("Jinwoo"), emptyMap()) shouldBe emptyMap()
        // A Korean name with its particle joined is still found
        AiTranslator.plainTerms(listOf("진우는 어디야?"), mapOf("진우" to "جين وو")) shouldBe mapOf("진우" to "جين وو")
    }

    @Test
    fun `a plain reply is read line by line in its places`() {
        LocalAiRules.parseNumbered("1. إلى أين تظن نفسك ذاهبًا؟\n2) ابتعد عن طريقي!\n3. ابقَ خلفي.", 3) shouldBe
            listOf("إلى أين تظن نفسك ذاهبًا؟", "ابتعد عن طريقي!", "ابقَ خلفي.")
        // A line the model skipped stays empty, so it is asked again
        LocalAiRules.parseNumbered("1. أهلاً\n3. وداعاً", 3) shouldBe listOf("أهلاً", "", "وداعاً")
        LocalAiRules.parseNumbered("Sure! Here you go.", 2) shouldBe null
    }

    @Test
    fun `a line left in English is not taken as its translation`() {
        AiTranslator.plainAccepted("RUN AWAY NOW!", "RUN AWAY NOW!", arabic = true) shouldBe false
        AiTranslator.plainAccepted("RUN AWAY NOW!", "Run away!", arabic = true) shouldBe false
        AiTranslator.plainAccepted("RUN AWAY NOW!", "اهرب الآن!", arabic = true) shouldBe true
        AiTranslator.plainAccepted("BOOM", "-", arabic = true) shouldBe true
        // A site name answered with the watermark mark is taken, so it is removed from the page
        AiTranslator.plainAccepted("ARVENCOMICS.COM", AiTranslator.WATERMARK, arabic = true) shouldBe true
        AiTranslator.plainAccepted("!?", "!?", arabic = true) shouldBe true
        AiTranslator.plainAccepted("Hi", "Salut", arabic = false) shouldBe true
    }

    @Test
    fun `lines numbered the Arabic way are read too`() {
        LocalAiRules.parseNumbered("١. اهرب!\n٢- ابقَ خلفي.", 2) shouldBe listOf("اهرب!", "ابقَ خلفي.")
    }

    @Test
    fun `no bubble stays in English`() {
        EnglishGuard.leftInSource("PAMN IT, STOP NAGGING AIREADY!", "PAMN IT, STOP NAGGING AIREADY!") shouldBe true
        EnglishGuard.leftInSource("RUN AWAY NOW!", "اهرب الآن!") shouldBe false
        // Left as drawn on purpose, a watermark, or no words: not touched
        EnglishGuard.leftInSource("BOOM", "") shouldBe false
        EnglishGuard.leftInSource("ARVENCOMICS.COM", AiTranslator.WATERMARK) shouldBe false
        EnglishGuard.leftInSource("!!", "!!") shouldBe false
    }
}
