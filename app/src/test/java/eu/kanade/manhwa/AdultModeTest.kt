package eu.kanade.manhwa

import eu.kanade.domain.source.service.AdultFilter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AdultModeTest {

    @Test
    fun `hiding keeps the +18 sources out and the others in`() {
        AdultFilter.keepsOut(adult = true, hide = true, only = false) shouldBe true
        AdultFilter.keepsOut(adult = false, hide = true, only = false) shouldBe false
    }

    @Test
    fun `only +18 keeps every other source out`() {
        AdultFilter.keepsOut(adult = true, hide = false, only = true) shouldBe false
        AdultFilter.keepsOut(adult = false, hide = false, only = true) shouldBe true
    }

    @Test
    fun `with both off every source shows`() {
        AdultFilter.keepsOut(adult = true, hide = false, only = false) shouldBe false
        AdultFilter.keepsOut(adult = false, hide = false, only = false) shouldBe false
    }
}
