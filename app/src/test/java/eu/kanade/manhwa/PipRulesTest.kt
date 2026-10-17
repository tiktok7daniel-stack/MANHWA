package eu.kanade.manhwa

import eu.kanade.tachiyomi.ui.reader.PipRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PipRulesTest {

    @Test
    fun `the small window keeps the reader's shape within Android's limits`() {
        PipRules.ratio(1080, 2400) shouldBe (1080 to 2400)
        PipRules.ratio(2400, 1080) shouldBe (2400 to 1080)
        // Thinner than 1 to 2.39: made as thin as Android allows
        val (w, h) = PipRules.ratio(1000, 4000)
        (w.toDouble() / h > 1 / 2.39) shouldBe true
        PipRules.ratio(0, 0) shouldBe (9 to 16)
    }
}
