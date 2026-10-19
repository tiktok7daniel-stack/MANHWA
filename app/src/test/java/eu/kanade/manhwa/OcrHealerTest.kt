package eu.kanade.manhwa

import eu.kanade.translation.live.OcrHealer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrHealerTest {

    @Test
    fun `digits read at the start of a capital word become letters`() {
        OcrHealer.heal("50ME OF THEM") shouldBe "SOME OF THEM"
        OcrHealer.heal("COME 8ACK HERE") shouldBe "COME BACK HERE"
        OcrHealer.heal("1T IS 0VER") shouldBe "1T IS OVER"
    }

    @Test
    fun `ordinals, units and real numbers are left as they are`() {
        OcrHealer.heal("THE 5TH FLOOR") shouldBe "THE 5TH FLOOR"
        OcrHealer.heal("AT 10PM SHARP") shouldBe "AT 10PM SHARP"
        OcrHealer.heal("IT WEIGHS 50KG") shouldBe "IT WEIGHS 50KG"
        OcrHealer.heal("100 TIMES") shouldBe "100 TIMES"
        OcrHealer.heal("THE 15TH DAY") shouldBe "THE 15TH DAY"
        OcrHealer.heal("WAIT 15MINUTES") shouldBe "WAIT 15MINUTES"
        OcrHealer.heal("FOR 10YEARS") shouldBe "FOR 10YEARS"
    }

    @Test
    fun `a 6 is never guessed and lower case lines are not touched`() {
        OcrHealer.heal("6ORT IT OUT") shouldBe "6ORT IT OUT"
        OcrHealer.heal("we have 50 men") shouldBe "we have 50 men"
    }

    @Test
    fun `the old mends still work`() {
        OcrHealer.heal("C0ME HERE") shouldBe "COME HERE"
        OcrHealer.heal("he1p me") shouldBe "help me"
        OcrHealer.heal("WAIT!!!!!!") shouldBe "WAIT!!!"
    }

    @Test
    fun `misread letters inside lowercase words are mended`() {
        OcrHealer.heal("Isn't it that S / tuation") shouldBe "Isn't it that Situation"
        OcrHealer.heal("what are yo0 doing") shouldBe "what are yoo doing"
        OcrHealer.heal("0kay then") shouldBe "okay then"
        OcrHealer.heal("5orry about that") shouldBe "Sorry about that"
        OcrHealer.heal("this and/or that") shouldBe "this and/or that"
    }
}
