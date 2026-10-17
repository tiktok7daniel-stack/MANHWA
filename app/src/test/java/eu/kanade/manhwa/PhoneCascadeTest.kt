package eu.kanade.manhwa

import eu.kanade.translation.ChapterPrefetchManager
import eu.kanade.translation.collection.CollectionMerge
import eu.kanade.translation.collection.TranslationCollection.Source
import eu.kanade.translation.local.LocalAiRules
import eu.kanade.translation.local.LocalBenchmarkRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhoneCascadeTest {

    @Test
    fun `an equals sign keeps the draft and a written line replaces it`() {
        LocalAiRules.resolveEdits(listOf("=", "جين وو، اهرب!", "", "＝"), listOf("مرحبا", "اهرب", "لا", null)) shouldBe
            listOf("مرحبا", "جين وو، اهرب!", "لا", "＝")
    }

    @Test
    fun `fix-only answers are read from numbered lines`() {
        val raw = LocalAiRules.answers("1. =\n2. لا تقلق، سأحميك.\n3. =", 3)!!
        LocalAiRules.resolveEdits(raw, listOf("أين أنت؟", "لا تقلق", "ابقَ خلفي")) shouldBe listOf("أين أنت؟", "لا تقلق، سأحميك.", "ابقَ خلفي")
    }

    @Test
    fun `the same line again points at its first copy`() {
        LocalAiRules.firstOf(listOf("What?!", "Run!", "what?! ", "Run!", "Go")) shouldBe listOf(0, 1, 0, 1, 4)
    }

    @Test
    fun `the guard keeps a hot or tired phone light`() {
        val cool = LocalAiRules.State(thermal = 0, battery = 80, charging = false, saver = false)
        LocalAiRules.governor(cool) shouldBe LocalAiRules.Power.FULL
        LocalAiRules.governor(cool.copy(thermal = 2)) shouldBe LocalAiRules.Power.LIGHT
        LocalAiRules.governor(cool.copy(thermal = 4)) shouldBe LocalAiRules.Power.PAUSE
        LocalAiRules.governor(cool.copy(battery = 15)) shouldBe LocalAiRules.Power.LIGHT
        LocalAiRules.governor(cool.copy(battery = 15, charging = true)) shouldBe LocalAiRules.Power.FULL
        LocalAiRules.governor(cool.copy(saver = true)) shouldBe LocalAiRules.Power.LIGHT
    }

    @Test
    fun `the phone never writes over a key or the reader`() {
        // The phone's other wording of a team line: nothing changes
        CollectionMerge.decide(Source.LOCAL, Source.TEAM.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.SKIP
        CollectionMerge.decide(Source.LOCAL, Source.USER.confidence, byUser = true, same = false) shouldBe CollectionMerge.Action.KEEP
        // The phone again with the same words: one more use
        CollectionMerge.decide(Source.LOCAL, Source.LOCAL.confidence, byUser = false, same = true) shouldBe CollectionMerge.Action.KEEP
        // Two phone wordings: a hint only
        CollectionMerge.decide(Source.LOCAL, Source.LOCAL.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.SENSITIVE
        // A key over a phone line replaces it; the reader always wins
        CollectionMerge.decide(Source.TEAM, Source.LOCAL.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.REPLACE
        CollectionMerge.decide(Source.USER, Source.TEAM.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.USER
    }

    @Test
    fun `the team keeps the rules it had`() {
        CollectionMerge.decide(Source.TEAM, Source.TEAM.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.SENSITIVE
        CollectionMerge.decide(Source.TEAM, Source.EDITOR.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.SENSITIVE
        CollectionMerge.decide(Source.EDITOR, Source.TEAM.confidence, byUser = false, same = false) shouldBe CollectionMerge.Action.REPLACE
        CollectionMerge.decide(Source.TEAM, Source.TEAM.confidence, byUser = true, same = false) shouldBe CollectionMerge.Action.KEEP
    }

    @Test
    fun `fix-only is used only when it is faster and as accurate`() {
        LocalBenchmarkRules.editWins(fast = 3000L to 60.0, edit = 1800L to 59.5) shouldBe true
        LocalBenchmarkRules.editWins(fast = 3000L to 60.0, edit = 1800L to 55.0) shouldBe false
        LocalBenchmarkRules.editWins(fast = 3000L to 60.0, edit = 3200L to 70.0) shouldBe false
        LocalBenchmarkRules.editWins(fast = 3000L to 60.0, edit = null) shouldBe false
        LocalAiRules.Tuning.named("EDIT") shouldBe LocalAiRules.Tuning.EDIT
        LocalAiRules.Tuning.named("anything") shouldBe LocalAiRules.Tuning.FAST
    }

    @Test
    fun `the phone model translates ahead with no key, and offline`() {
        val good = ChapterPrefetchManager.Device(batteryPercent = 80, charging = false, powerSave = false, online = true)
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good) shouldBe false
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good, phoneModel = true) shouldBe true
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good.copy(online = false), phoneModel = true) shouldBe true
        ChapterPrefetchManager.allowed(manual = false, auto = false, workingKeys = 0, device = good, phoneModel = true) shouldBe false
    }
}
