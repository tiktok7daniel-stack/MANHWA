package eu.kanade.manhwa

import eu.kanade.translation.unified.FusionRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FusionRulesTest {

    private val english = (1..100).map { it.toDouble() to "en$it" }

    @Test
    fun `the continuation starts after the last Arabic chapter, newest first`() {
        val ours = (1..25).map { it.toDouble() }
        val out = FusionRules.continuation(ours, english)
        out.size shouldBe 75
        out.first().second shouldBe "en100"
        out.last().second shouldBe "en26"
    }

    @Test
    fun `a chapter that comes out in Arabic replaces the English one by itself`() {
        val ours = (1..26).map { it.toDouble() }
        val out = FusionRules.continuation(ours, english)
        out.last().second shouldBe "en27"
        out.any { it.second == "en26" } shouldBe false
    }

    @Test
    fun `one chapter per number, extras without a number are left out`() {
        val theirs = listOf(30.0 to "a", 30.0 to "b", -1.0 to "extra", 31.0 to "c")
        FusionRules.continuation(listOf(25.0), theirs).map { it.second } shouldBe listOf("c", "a")
    }

    @Test
    fun `the reader goes to the next and previous chapter across sources`() {
        FusionRules.after(25.0, english) shouldBe "en26"
        FusionRules.after(100.0, english) shouldBe null
        val arabic = (1..25).map { it.toDouble() to "ar$it" }
        FusionRules.before(26.0, arabic) shouldBe "ar25"
        FusionRules.before(1.0, arabic) shouldBe null
    }

    @Test
    fun `the continuation's own copies of Arabic chapters are hidden in the reader`() {
        FusionRules.keepInContinuation(25.0, 25.0) shouldBe false
        FusionRules.keepInContinuation(26.0, 25.0) shouldBe true
        FusionRules.keepInContinuation(10.0, null) shouldBe true
    }

    @Test
    fun `only a source in another language with more chapters is offered`() {
        FusionRules.otherLanguage("ar", "en") shouldBe true
        FusionRules.otherLanguage("ar", "ar") shouldBe false
        FusionRules.otherLanguage("ar", "all") shouldBe false
        FusionRules.otherLanguage("pt-BR", "en") shouldBe true
        FusionRules.worthOffering(25.0, 100.0) shouldBe true
        FusionRules.worthOffering(25.0, 25.0) shouldBe false
        FusionRules.worthOffering(-1.0, -1.0) shouldBe false
    }

    @Test
    fun `English names in an Arabic description are used to look the work up`() {
        val titles = FusionRules.titlesOf(
            "الحداد الأسطوري",
            "الاسم الإنجليزي: Overgeared\nأسماء أخرى: Legendary Blacksmith, 템빨\nقصة رائعة جدا",
        )
        titles shouldBe listOf("الحداد الأسطوري", "Overgeared", "Legendary Blacksmith")
    }

    @Test
    fun `a fused work and its continuation are one line in the history, the newest one`() {
        // (mangaId, chapter) newest first; 2 is the continuation of 1
        val history = listOf(2L to "en27", 3L to "other", 1L to "ar26")
        val merged = FusionRules.mergeHistory(history, { it.first }, { if (it == 2L) 1L else null })
        merged.map { it.second } shouldBe listOf("en27", "other")
        // Not fused: nothing changes
        FusionRules.mergeHistory(history, { it.first }, { null }) shouldBe history
    }

    @Test
    fun `only a work already in the reading language offers its continuation to translate`() {
        FusionRules.sameLanguage("ar", "ar") shouldBe true
        FusionRules.sameLanguage("ar-EG", "ar") shouldBe true
        FusionRules.sameLanguage("en", "ar") shouldBe false
        FusionRules.sameLanguage("", "ar") shouldBe false
    }

    @Test
    fun `short language codes for badges`() {
        FusionRules.langCode("en-US") shouldBe "EN"
        FusionRules.langCode("ko") shouldBe "KO"
        FusionRules.langCode("") shouldBe "EN"
    }

    @Test
    fun `changing source keeps the chapters translated on the first one, the new one gives the rest`() {
        val ours = (1..26).map { it.toDouble() }
        // Translated on the first English source: 27 to 30
        val kept = (27..30).map { it.toDouble() to "a$it" }
        val newSource = (1..144).map { it.toDouble() to "b$it" }
        val out = FusionRules.layered(ours, kept, newSource)
        out.first().second shouldBe "b144"
        out.filter { it.second.startsWith("a") }.map { it.second } shouldBe listOf("a30", "a29", "a28", "a27")
        out.any { it.second == "b27" || it.second == "b30" } shouldBe false
        out.any { it.second == "b31" } shouldBe true
        out.size shouldBe 144 - 26
    }

    @Test
    fun `a library update reports only the continuation chapters it brought`() {
        val before = setOf(27L, 28L)
        val after = listOf(27L, 28L, 29L, 30L)
        FusionRules.newChapters(before, after) { it } shouldBe listOf(29L, 30L)
        FusionRules.newChapters(after.toSet(), after) { it } shouldBe emptyList()
    }

    @Test
    fun `a kept chapter in the middle ends the reader run so it is not skipped`() {
        // B (id 2) reads 26 27 29 30, chapter 28 was kept from the earlier source A (id 1)
        val fused = listOf(
            Triple(26.0, 2L, 126L),
            Triple(27.0, 2L, 127L),
            Triple(28.0, 1L, 28L),
            Triple(29.0, 2L, 129L),
            Triple(30.0, 2L, 130L),
            Triple(-1.0, 2L, 999L),
        )
        FusionRules.runAround(fused, 2L, 127L) shouldBe setOf(126L, 127L, 999L)
        FusionRules.runAround(fused, 2L, 130L) shouldBe setOf(129L, 130L, 999L)
        FusionRules.runAround(fused, 2L, null) shouldBe setOf(126L, 127L, 129L, 130L, 999L)
        FusionRules.runAround(fused, 2L, 555L) shouldBe setOf(126L, 127L, 129L, 130L, 999L)
    }
}

class FusionNameTest {

    @Test
    fun `the same title in another order or with other marks is the same work`() {
        FusionRules.sameName("Solo Leveling: Ragnarok", "Ragnarok - Solo Leveling") shouldBe true
        FusionRules.sameName("The Greatest Estate Developer", "Greatest Estate Developer (Official)") shouldBe true
        FusionRules.sameName("Overgeared", "overgeared") shouldBe true
    }

    @Test
    fun `a sequel or another work is never taken for this one`() {
        FusionRules.sameName("Solo Leveling", "Solo Leveling Season 2") shouldBe false
        FusionRules.sameName("Omniscient Reader", "Omniscient Reader's Viewpoint") shouldBe false
        FusionRules.sameName("Return", "Return") shouldBe true
    }
}
