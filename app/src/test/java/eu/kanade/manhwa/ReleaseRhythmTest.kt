package eu.kanade.manhwa

import eu.kanade.tachiyomi.ui.home.schedule.ReleaseRhythm
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class ReleaseRhythmTest {

    private val zone = ZoneOffset.UTC
    private fun at(date: LocalDate) = date.atTime(12, 0).toInstant(zone).toEpochMilli()

    @Test
    fun `a weekly work keeps its day`() {
        val first = LocalDate.of(2026, 9, 3) // a Thursday
        val uploads = (0 until 6).map { at(first.plusWeeks(it.toLong())) }
        val now = at(first.plusWeeks(5).plusDays(2))
        val rhythm = ReleaseRhythm.of(uploads, now, zone)
        rhythm shouldNotBe null
        rhythm!!.weekday shouldBe DayOfWeek.THURSDAY
        rhythm.lateDays shouldBe 0
    }

    @Test
    fun `a missed week shows as late`() {
        val first = LocalDate.of(2026, 9, 3)
        val uploads = (0 until 6).map { at(first.plusWeeks(it.toLong())) }
        val now = at(first.plusWeeks(6).plusDays(3))
        ReleaseRhythm.of(uploads, now, zone)!!.lateDays shouldBe 3
    }

    @Test
    fun `too few chapters give no rhythm`() {
        val first = LocalDate.of(2026, 9, 3)
        ReleaseRhythm.of(listOf(at(first), at(first.plusWeeks(1))), at(first.plusWeeks(2)), zone) shouldBe null
    }

    @Test
    fun `a batch upload counts once`() {
        val first = LocalDate.of(2026, 9, 3)
        val uploads = (0 until 5).flatMap { w -> List(3) { at(first.plusWeeks(w.toLong())) } }
        ReleaseRhythm.of(uploads, at(first.plusWeeks(4).plusDays(1)), zone)!!.gapDays shouldBe 7.0
    }
}
