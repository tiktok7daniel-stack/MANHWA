package eu.kanade.manhwa

import eu.kanade.translation.translator.PlainMt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PlainMtTest {

    @Test
    fun `voiced sounds are written in Arabic`() {
        PlainMt.sound("HUFF...") shouldBe "هاه..."
        PlainMt.sound("(TSK)") shouldBe "تسك"
        PlainMt.sound("UGHHH!") shouldBe "آه!"
        PlainMt.sound("HA HA") shouldBe "ها ها"
        PlainMt.sound("HUH?") shouldBe "هاه؟"
    }

    @Test
    fun `real words are left to the translator`() {
        PlainMt.sound("THAT'S YOU THREE?").shouldBeNull()
        PlainMt.sound("OH NO").shouldBeNull()
        PlainMt.sound("RUN!").shouldBeNull()
    }
}
