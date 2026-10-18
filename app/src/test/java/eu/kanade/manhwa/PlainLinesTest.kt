package eu.kanade.manhwa

import eu.kanade.translation.local.LocalAiRules
import eu.kanade.translation.translator.AiTranslator
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
}
