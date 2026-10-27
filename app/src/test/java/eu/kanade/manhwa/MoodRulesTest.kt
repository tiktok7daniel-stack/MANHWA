package eu.kanade.manhwa

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.home.mood.MoodRules
import eu.kanade.tachiyomi.ui.home.mood.MoodRules.Mood
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MoodRulesTest {

    private fun work(vararg genres: String, chapters: Long = 100, status: Long = SManga.ONGOING.toLong(), unread: Long = 5) =
        MoodRules.Work(genres.toList(), chapters, status, unread)

    @Test
    fun `light action leaves out the heavy works`() {
        MoodRules.matches(Mood.LIGHT_ACTION, work("Action", "Comedy")) shouldBe true
        MoodRules.matches(Mood.LIGHT_ACTION, work("Action", "Tragedy")) shouldBe false
    }

    @Test
    fun `arabic genres count too`() {
        MoodRules.matches(Mood.ROMANCE, work("رومانسي", "دراما")) shouldBe true
        MoodRules.matches(Mood.SAD, work("رومانسي", "دراما")) shouldBe true
    }

    @Test
    fun `one day wants a short finished work`() {
        MoodRules.matches(Mood.ONE_DAY, work("Action", chapters = 50, status = SManga.COMPLETED.toLong())) shouldBe true
        MoodRules.matches(Mood.ONE_DAY, work("Action", chapters = 200, status = SManga.COMPLETED.toLong())) shouldBe false
    }

    @Test
    fun `unfinished works come first`() {
        val done = "done" to work("Comedy", chapters = 10, unread = 0)
        val open = "open" to work("Comedy", chapters = 90, unread = 3)
        MoodRules.pick(Mood.FUNNY, listOf(done, open)) { it.second }.map { it.first } shouldBe listOf("open", "done")
    }
}
