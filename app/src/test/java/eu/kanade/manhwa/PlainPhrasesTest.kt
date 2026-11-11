package eu.kanade.manhwa

import eu.kanade.translation.translator.PlainMt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlainPhrasesTest {

    @Test
    fun `known short lines are written directly with their punctuation`() {
        PlainMt.phrase("WHAT THE HELL?!") shouldBe "ما هذا بحق الجحيم؟!"
        PlainMt.phrase("I SEE.") shouldBe "فهمت."
        PlainMt.phrase("No... way!") shouldBe "مستحيل!"
        PlainMt.phrase("WHAT'S\nGOING ON?") shouldBe "ما الذي يحدث؟"
    }

    @Test
    fun `any other line goes to the engine`() {
        PlainMt.phrase("THAT'S YOU THREE?").shouldBeNull()
        PlainMt.direct("THE NOBLEMAN WAS ANGRY.").shouldBeNull()
    }

    @Test
    fun `spoken English is spelled out before it is sent`() {
        PlainMt.plainEnglish("W-WHAT ARE YOU GONNA DO?") shouldBe "WHAT ARE YOU GOING TO DO?"
        PlainMt.plainEnglish("I ain't doin' nothin', ya know") shouldBe "I is not doing nothing, you know"
        PlainMt.plainEnglish("The king's yacht") shouldBe "The king's yacht"
    }
}
