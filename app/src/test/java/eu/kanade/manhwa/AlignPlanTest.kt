package eu.kanade.manhwa

import eu.kanade.tachiyomi.novel.bridge.AlignPlan
import eu.kanade.tachiyomi.novel.bridge.NovelAligner
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AlignPlanTest {

    @Test
    fun `no place found before gives no prediction`() {
        AlignPlan.predict(5.0, emptyList()).shouldBeNull()
    }

    @Test
    fun `one place keeps the same pace from the start`() {
        // Manhwa chapter 5 was novel index 9 (chapter 10): two novel chapters a manhwa chapter
        AlignPlan.predict(10.0, listOf(5.0 to 9)) shouldBe 19
    }

    @Test
    fun `two places draw the line between them`() {
        val places = listOf(10.0 to 20, 20.0 to 40)
        AlignPlan.predict(15.0, places) shouldBe 30
        AlignPlan.predict(30.0, places) shouldBe 60
        AlignPlan.predict(5.0, places) shouldBe 10
    }

    @Test
    fun `the spread covers where an adaptation usually is`() {
        val spread = AlignPlan.coarse(10.0, 1000)
        spread shouldHaveSize AlignPlan.SAMPLES
        spread.first() shouldBe 5
        spread.last() shouldBe 50
    }

    @Test
    fun `a short novel is read whole`() {
        AlignPlan.coarse(2.0, 5) shouldContainExactly listOf(0, 1, 2, 3, 4)
    }

    @Test
    fun `the window stays inside the novel and is capped`() {
        AlignPlan.window(0, 100, 8) shouldContainExactly (0..8).toList()
        AlignPlan.window(99, 100, 30) shouldContainExactly (87..99).toList()
    }

    @Test
    fun `pages read for words skip the cover and the credits`() {
        NovelAligner.spreadPages(4) shouldContainExactly listOf(0, 1, 2, 3)
        val pages = NovelAligner.spreadPages(20)
        pages shouldHaveSize 6
        pages.first() shouldBe 1
        pages.last() shouldBe 18
    }
}
