package eu.kanade.manhwa

import eu.kanade.translation.collection.ReferencePicker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReferencePickerTest {

    private val reviewed = listOf(
        "Shadow Monarch" to "ملك الظلال",
        "I will kill you!" to "سأقتلك!",
        "Sumoon High" to "ثانوية سومون",
        "What are you doing?" to "بتعمل إيه؟",
    )

    @Test
    fun `a corrected term is found inside a new sentence`() {
        val out = ReferencePicker.pick(listOf("The Shadow Monarch has returned to Sumoon High!"), reviewed, 5)
        out.map { it.first }.toSet() shouldBe setOf("Shadow Monarch", "Sumoon High")
    }

    @Test
    fun `lines sharing only everyday words bring nothing`() {
        ReferencePicker.pick(listOf("What do you want from me?"), reviewed, 5) shouldBe emptyList()
    }

    @Test
    fun `the prompt lists each reference and is empty without any`() {
        ReferencePicker.prompt(emptyList()) shouldBe ""
        ReferencePicker.prompt(listOf("Shadow Monarch" to "ملك الظلال")).lines().last() shouldBe "- \"Shadow Monarch\" = \"ملك الظلال\""
    }
}
