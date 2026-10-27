package eu.kanade.manhwa

import eu.kanade.presentation.manga.components.EffectChoice
import eu.kanade.presentation.manga.components.TitlePlate
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class FramesAndEffectsTest {

    @Test
    fun `there are a hundred drawn frames with their own keys`() {
        // Ten shapes in ten finishes, and each shape in the cover's own colours
        TitlePlate.drawnFrames shouldHaveSize 110
        TitlePlate.energyFrames shouldHaveSize 100
        TitlePlate.all.map { it.key }.toSet() shouldHaveSize 211
        TitlePlate.all.last().key shouldBe "NONE"
    }

    @Test
    fun `the hundred frames in the work's colours are ten families of ten`() {
        TitlePlate.energyFrames.map { it.energy?.family }.toSet() shouldHaveSize 10
        TitlePlate.energyFrames.all { it.key.startsWith("E_") } shouldBe true
        TitlePlate.of("E_THORN_0")?.energy?.variant shouldBe 0
        TitlePlate.of("E_CELESTIAL_9") shouldNotBe null
    }

    @Test
    fun `a frame picked in an older version still finds a frame`() {
        listOf("THORN", "VIOLET", "CRYSTAL", "GOLD", "STAR1", "STAR2", "STAR3", "STAR4", "SHIELD", "ANTLER", "STONE", "FLAME", "ROYAL", "STORM")
            .forEach { TitlePlate.of(it) shouldNotBe null }
    }

    @Test
    fun `there are fifty different mixes of two different effects`() {
        EffectChoice.mixes shouldHaveSize 50
        EffectChoice.mixes.toSet() shouldHaveSize 50
        EffectChoice.mixes.all { it.first != it.second } shouldBe true
    }

    @Test
    fun `every day gives a mix that changes from one day to the next`() {
        val today = EffectChoice.resolve(EffectChoice.DAILY, workId = 5L, day = 20_000L)
        today shouldHaveSize 2
        EffectChoice.resolve(EffectChoice.DAILY, workId = 5L, day = 20_000L) shouldBe today
        EffectChoice.resolve(EffectChoice.DAILY, workId = 5L, day = 20_001L) shouldNotBe today
    }

    @Test
    fun `none and unknown keys draw nothing`() {
        EffectChoice.resolve(null, 1L) shouldHaveSize 0
        EffectChoice.resolve("NONE", 1L) shouldHaveSize 0
        EffectChoice.resolve("MIX_999", 1L) shouldHaveSize 0
    }
}
