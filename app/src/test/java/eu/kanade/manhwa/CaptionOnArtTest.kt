package eu.kanade.manhwa

import eu.kanade.translation.live.OcrSanity
import eu.kanade.translation.live.SentenceFlow
import eu.kanade.translation.render.LineGroups
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** White outlined narration straight on dark grain (no bubble), as in the reader's screenshots of pages 3 and 4. */
class CaptionOnArtTest {

    @Test
    fun `close stacked lines are cleaned as one area`() {
        // Four lines 40 px high, 10 px apart, each with a 18 px margin: the margins overlap
        val lines = (0 until 4).map { i -> floatArrayOf(0f, i * 50f - 18f, 600f, i * 50f + 40f + 18f) }
        LineGroups.groups(lines) shouldBe listOf(listOf(0, 1, 2, 3))
    }

    @Test
    fun `lines far apart stay apart`() {
        val lines = listOf(floatArrayOf(0f, 0f, 100f, 50f), floatArrayOf(0f, 200f, 100f, 250f), floatArrayOf(0f, 40f, 100f, 90f))
        LineGroups.groups(lines) shouldBe listOf(listOf(0, 2), listOf(1))
    }

    @Test
    fun `a sentence cut over two blocks runs on`() {
        SentenceFlow.continues("THE VERANDA OF THE NOBLEMAN'S", "MASTER ROOM.") shouldBe true
    }

    @Test
    fun `a finished sentence does not run on`() {
        SentenceFlow.continues("HE LEFT THE ROOM.", "THE NEXT DAY") shouldBe false
        SentenceFlow.continues("WHAT?!", "NO WAY") shouldBe false
        SentenceFlow.continues("UGH", "...") shouldBe false
    }

    @Test
    fun `digits read where letters were drawn are caught`() {
        OcrSanity.strayDigits("THE NOBLEMAN, ANGRY THAT A BOY WHO APPEARED 1 68 A SERVANT HAD RUDELY") shouldBe true
        OcrSanity.strayDigits("HE WALKED T0 THE MASTER R00M") shouldBe true
    }

    @Test
    fun `real numbers are left alone`() {
        OcrSanity.strayDigits("IT HAS BEEN 10 YEARS SINCE THAT DAY") shouldBe false
        OcrSanity.strayDigits("WELCOME TO FLOOR 3 OF THE TOWER") shouldBe false
        OcrSanity.strayDigits("THE 10TH PRINCE OF THE EMPIRE") shouldBe false
        OcrSanity.strayDigits("100") shouldBe false
    }
}
