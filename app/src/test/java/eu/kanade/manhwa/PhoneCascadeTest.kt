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
            listOf("مرحبا", "جين وو، اهرب!", "لا", "")
    }

    @Test
    fun `a reply that copies the line back keeps only its answer`() {
        val draft = "أقدر عرضك... ولكن معرف ترغب في البقاء هنا."
        LocalAiRules.resolveEdits(listOf("$draft ⇒ $draft (=)"), listOf(draft)) shouldBe listOf(draft)
        LocalAiRules.resolveEdits(listOf("Stay behind me. ⇒ ابقَ خلفي."), listOf("ابق ورائي")) shouldBe listOf("ابقَ خلفي.")
        LocalAiRules.resolveEdits(listOf("ابقَ خلفي. ="), listOf("ابق ورائي")) shouldBe listOf("ابق ورائي")
        LocalAiRules.resolveEdits(listOf("ابقَ خلفي.(=)"), listOf("ابق ورائي")) shouldBe listOf("ابق ورائي")
    }

    @Test
    fun `a saved line with the leaked reply is repaired and never learnt`() {
        val draft = "مجرد حقيقة أنك جعلت هذا العرض موجود بالفعل من وسائل شرف."
        LocalAiRules.repairLeak("$draft ⇒ $draft (=)") shouldBe draft
        LocalAiRules.repairLeak(draft) shouldBe null
        LocalAiRules.usable("Just the fact", "$draft ⇒ $draft (=)", "ar") shouldBe false
        LocalAiRules.usable("Just the fact", draft, "ar") shouldBe true
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
    fun `full strength at any battery, one model only when critically hot`() {
        val cool = LocalAiRules.State(thermal = 0, battery = 80, charging = false, saver = false)
        LocalAiRules.governor(cool) shouldBe LocalAiRules.Power.FULL
        LocalAiRules.governor(cool.copy(battery = 1)) shouldBe LocalAiRules.Power.FULL
        LocalAiRules.governor(cool.copy(battery = 15, saver = true)) shouldBe LocalAiRules.Power.FULL
        LocalAiRules.governor(cool.copy(thermal = 3)) shouldBe LocalAiRules.Power.FULL
        LocalAiRules.governor(cool.copy(thermal = 4)) shouldBe LocalAiRules.Power.LIGHT
        LocalAiRules.governor(cool.copy(thermal = 6)) shouldBe LocalAiRules.Power.LIGHT
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
        // Not measured yet: the model translates each line once
        LocalAiRules.Tuning.named("AUTO") shouldBe LocalAiRules.Tuning.FAST
        LocalAiRules.Tuning.named("FAST") shouldBe LocalAiRules.Tuning.FAST
    }

    @Test
    fun `the phone model translates ahead with no key, and offline`() {
        val good = ChapterPrefetchManager.Device(batteryPercent = 80, charging = false, powerSave = false, online = true)
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good) shouldBe false
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good, phoneModel = true) shouldBe true
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good.copy(online = false), phoneModel = true) shouldBe true
        ChapterPrefetchManager.allowed(manual = false, auto = false, workingKeys = 0, device = good, phoneModel = true) shouldBe false
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 0, device = good.copy(batteryPercent = 1, powerSave = true), phoneModel = true) shouldBe true
    }

    @Test
    fun `closing the app for memory does not count against the saved queue`() {
        eu.kanade.translation.RestoreRules.crashed(3) shouldBe false // low memory
        eu.kanade.translation.RestoreRules.crashed(13) shouldBe false // other (the phone maker)
        eu.kanade.translation.RestoreRules.crashed(10) shouldBe false // user requested
        eu.kanade.translation.RestoreRules.crashed(4) shouldBe true
        eu.kanade.translation.RestoreRules.crashed(5) shouldBe true
        eu.kanade.translation.RestoreRules.crashed(6) shouldBe true
        eu.kanade.translation.RestoreRules.crashed(null) shouldBe true
    }

    @Test
    fun `a chapter that failed for a passing reason is tried again a few times`() {
        eu.kanade.translation.RetryRules.retry(eu.kanade.translation.model.Translation.Note.NO_INTERNET, 0) shouldBe true
        eu.kanade.translation.RetryRules.retry(eu.kanade.translation.model.Translation.Note.STALLED, 2) shouldBe true
        eu.kanade.translation.RetryRules.retry(eu.kanade.translation.model.Translation.Note.STALLED, 3) shouldBe false
        eu.kanade.translation.RetryRules.retry(null, 0) shouldBe true
        eu.kanade.translation.RetryRules.retry(eu.kanade.translation.model.Translation.Note.KEY_INVALID, 0) shouldBe false
        eu.kanade.translation.RetryRules.retry(eu.kanade.translation.model.Translation.Note.NOT_DOWNLOADED, 0) shouldBe false
        eu.kanade.translation.RetryRules.retry(eu.kanade.translation.model.Translation.Note.SAVE_FAILED, 0) shouldBe false
    }
}
