package eu.kanade.manhwa

import eu.kanade.translation.render.CleanCheck
import eu.kanade.translation.render.CleanMemory
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CleanCheckTest {

    /** Dark grain like the reader's pages: 40 to 80, rough from pixel to pixel. */
    private fun grain(n: Int, seed: Int = 1): IntArray {
        val r = Random(seed)
        return IntArray(n) { 40 + r.nextInt(41) }
    }

    @Test
    fun `clean grain counts as clean`() {
        CleanCheck.residue(grain(2000, 2), grain(3000, 3)) shouldBeLessThan CleanCheck.LEFT
    }

    @Test
    fun `white letters left on grain are found`() {
        val inside = grain(2000, 4)
        // A tenth of the place still white (the fill of outlined letters)
        for (i in 0 until 200) inside[i * 10] = 245
        CleanCheck.residue(inside, grain(3000, 5)) shouldBeGreaterThan CleanCheck.LEFT
    }

    @Test
    fun `backgrounds are told apart`() {
        CleanCheck.groundOf(IntArray(500) { 250 }, 0f) shouldBe CleanCheck.Ground.PLAIN
        CleanCheck.groundOf(IntArray(500) { it / 3 }, 1f) shouldBe CleanCheck.Ground.GRADIENT
        val g = grain(500, 6)
        CleanCheck.groundOf(g, CleanCheck.roughness(g, 25)) shouldBe CleanCheck.Ground.GRAIN
        val art = IntArray(500) { if ((it / 7) % 2 == 0) 20 else 230 }
        CleanCheck.groundOf(art, CleanCheck.roughness(art, 25)) shouldBe CleanCheck.Ground.BUSY
    }

    @Test
    fun `the memory starts where most recent bubbles needed`() {
        CleanMemory.startFrom(listOf(1, 1)) shouldBe 0
        CleanMemory.startFrom(listOf(0, 1, 1, 1, 1, 0, 1)) shouldBe 1
        CleanMemory.startFrom(listOf(2, 2, 2, 2, 2, 1)) shouldBe 2
        CleanMemory.startFrom(listOf(2, 2, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) shouldBe 0
    }
}
