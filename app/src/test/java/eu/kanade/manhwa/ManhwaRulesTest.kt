package eu.kanade.manhwa

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthManager
import eu.kanade.tachiyomi.ui.home.discover.SourceHealthRules
import eu.kanade.tachiyomi.ui.library.ai.LibraryOrganizer
import eu.kanade.tachiyomi.ui.library.ai.LibraryRules
import eu.kanade.translation.ChapterPrefetchManager
import eu.kanade.translation.characters.CastRules
import eu.kanade.translation.characters.CharacterStore
import eu.kanade.translation.glossary.WorkIdentity
import eu.kanade.translation.night.NightRules
import eu.kanade.translation.team.ChapterPlanner
import eu.kanade.translation.team.TranslationConsensusEngine
import eu.kanade.translation.translator.ArabicGrammarEngine
import eu.kanade.translation.translator.ArabicTransliterator
import eu.kanade.translation.brain.BrainRules
import eu.kanade.translation.brain.SeriesBrain
import eu.kanade.translation.style.StyleProfile
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceHealthTest {

    private val fail = false to "HTTP 503"
    private val ok = true to null

    @Test
    fun `three failures in a row hide a source, never before`() {
        var map = emptyMap<Long, SourceHealthManager.Entry>()
        repeat(2) {
            val (next, change) = SourceHealthRules.round(map, mapOf(1L to fail, 2L to ok), online = true, now = 100)
            change.hidden shouldBe emptyList()
            map = next
        }
        val (next, change) = SourceHealthRules.round(map, mapOf(1L to fail, 2L to ok), online = true, now = 100)
        change.hidden shouldBe listOf(1L)
        next.getValue(1L).hidden shouldBe true
        next.getValue(1L).nextCheck shouldBe 100 + SourceHealthRules.backoff(0)
        next.getValue(2L).hidden shouldBe false
    }

    @Test
    fun `offline or everything failing at once is not counted`() {
        val (offline, _) = SourceHealthRules.round(emptyMap(), mapOf(1L to fail, 2L to ok), online = false, now = 1)
        offline.getValue(1L).fails shouldBe 0
        val (allDown, _) = SourceHealthRules.round(emptyMap(), mapOf(1L to fail, 2L to fail), online = true, now = 1)
        allDown.getValue(1L).fails shouldBe 0
        allDown.getValue(2L).fails shouldBe 0
    }

    @Test
    fun `a new source that fails is hidden at once`() {
        val start = mapOf(7L to SourceHealthManager.Entry(fresh = true))
        val (next, change) = SourceHealthRules.round(start, mapOf(7L to fail), online = true, now = 5)
        change.hidden shouldBe listOf(7L)
        next.getValue(7L).fresh shouldBe false
    }

    @Test
    fun `a hidden source that works again comes back`() {
        val hidden = mapOf(1L to SourceHealthManager.Entry(fails = 3, hiddenAt = 10, nextCheck = 20))
        val (next, change) = SourceHealthRules.recheck(hidden, mapOf(1L to ok), now = 30)
        change.restored shouldBe listOf(1L)
        next.getValue(1L).hidden shouldBe false
        next.getValue(1L).fails shouldBe 0
        val (fromHome, home) = SourceHealthRules.round(hidden, mapOf(1L to ok), online = true, now = 30)
        home.restored shouldBe listOf(1L)
        fromHome.getValue(1L).hidden shouldBe false
    }

    @Test
    fun `recheck waits longer each time`() {
        val hidden = mapOf(1L to SourceHealthManager.Entry(fails = 3, hiddenAt = 10, nextCheck = 20, rechecks = 0))
        val (next, _) = SourceHealthRules.recheck(hidden, mapOf(1L to fail), now = 1_000)
        next.getValue(1L).rechecks shouldBe 1
        next.getValue(1L).nextCheck shouldBe 1_000 + SourceHealthRules.backoff(1)
        next.getValue(1L).hidden shouldBe true
        (SourceHealthRules.backoff(0) < SourceHealthRules.backoff(1)) shouldBe true
        (SourceHealthRules.backoff(1) < SourceHealthRules.backoff(2)) shouldBe true
    }
}

class LibraryRulesTest {

    @Test
    fun `finished only by the source status`() {
        LibraryRules.isFinished(SManga.COMPLETED.toLong()) shouldBe true
        LibraryRules.isFinished(SManga.PUBLISHING_FINISHED.toLong()) shouldBe true
        LibraryRules.isFinished(SManga.ONGOING.toLong()) shouldBe false
        LibraryRules.isFinished(SManga.UNKNOWN.toLong()) shouldBe false
    }

    @Test
    fun `completed category names`() {
        LibraryRules.isCompletedName("Completed") shouldBe true
        LibraryRules.isCompletedName("مكتملة") shouldBe true
        LibraryRules.isCompletedName("Action") shouldBe false
    }

    @Test
    fun `the AI can never put an ongoing series in completed, and duplicates merge`() {
        val groups = listOf(
            LibraryOrganizer.Group("Completed", listOf(1L, 2L)),
            LibraryOrganizer.Group("System & Leveling", listOf(3L)),
            LibraryOrganizer.Group("system and leveling", listOf(4L)),
        )
        val status = mapOf(1L to SManga.COMPLETED.toLong(), 2L to SManga.ONGOING.toLong(), 3L to 1L, 4L to 1L)
        val cleaned = LibraryRules.clean(groups, status)
        cleaned.first { LibraryRules.isCompletedName(it.name) }.mangaIds shouldBe listOf(1L)
        cleaned.count { LibraryRules.normalize(it.name) == "system leveling" } shouldBe 1
        cleaned.first { LibraryRules.normalize(it.name) == "system leveling" }.mangaIds shouldBe listOf(3L, 4L)
    }

    @Test
    fun `the reader's own categories stay, completed follows the rule both ways`() {
        LibraryRules.finalCategories(listOf(5L, 7L, 9L), setOf(8L), managed = setOf(7L, 8L), completedId = 9L, finished = false) shouldBe listOf(5L, 8L)
        LibraryRules.finalCategories(listOf(5L, 7L), setOf(8L), managed = setOf(7L, 8L), completedId = 9L, finished = true) shouldBe listOf(5L, 8L, 9L)
        // Running twice gives the same result
        val once = LibraryRules.finalCategories(listOf(5L), setOf(8L), setOf(8L), 9L, true)
        LibraryRules.finalCategories(once, setOf(8L), setOf(8L), 9L, true) shouldBe once
    }

    @Test
    fun `only empty categories the organizer made are removed`() {
        LibraryRules.removableEmpty(setOf("Murim"), mapOf("Murim" to 0, "Mine" to 0, "Isekai" to 2)) shouldBe setOf("Murim")
        LibraryRules.removableEmpty(setOf("Isekai"), mapOf("Isekai" to 2)) shouldBe emptySet()
    }
}

class TranslationLogicTest {

    @Test
    fun `scene-aware batches keep neighbours together and pictures apart`() {
        val pages = linkedMapOf(
            "1" to ChapterPlanner.PageStats(5, 300),
            "2" to ChapterPlanner.PageStats(5, 300),
            "3" to ChapterPlanner.PageStats(0, 0),
            "4" to ChapterPlanner.PageStats(6, 400),
            "5" to ChapterPlanner.PageStats(2, 50, needsImage = true),
            "6" to ChapterPlanner.PageStats(3, 100),
        )
        val plan = ChapterPlanner.plan(pages)
        plan.batches.first().pages shouldBe listOf("1", "2")
        plan.batches.first { it.needsImage }.pages shouldBe listOf("5")
        plan.batches.flatMap { it.pages }.sorted() shouldBe listOf("1", "2", "4", "5", "6")
        plan.batches.map { it.weight } shouldBe plan.batches.map { it.weight }.sortedDescending()
        ChapterPlanner.plan(pages, maxWeight = 8f).batches.all { it.pages.size == 1 } shouldBe true
    }

    @Test
    fun `one work across sources, never two works mixed`() {
        WorkIdentity.key("Solo Leveling (Official)") shouldBe "sololeveling"
        WorkIdentity.key("The Solo Leveling") shouldBe "sololeveling"
        WorkIdentity.same("Omniscient Reader's Viewpoint", "Omniscient Reader’s Viewpoint [Webtoon]") shouldBe true
        WorkIdentity.same("Solo Leveling", "Solo Leveling: Ragnarok") shouldBe false
        WorkIdentity.same("Abc", "abc") shouldBe false
    }

    @Test
    fun `arabic numbers and names`() {
        ArabicGrammarEngine.fixCounts("بعد 2 دقائق") shouldBe "بعد دقيقتين"
        ArabicGrammarEngine.fixCounts("بقي 1 دقائق") shouldBe "بقي دقيقة واحدة"
        ArabicGrammarEngine.fixCounts("بقي 5 دقيقة") shouldBe "بقي 5 دقائق"
        ArabicGrammarEngine.fixCounts("بقي 11 دقائق") shouldBe "بقي 11 دقيقة"
        ArabicGrammarEngine.fixCounts("بقي 25 طابق") shouldBe "بقي 25 طابقًا"
        ArabicTransliterator.transliterate("Arthur") shouldBe "آرثر"
        ArabicTransliterator.transliterate("Kang") shouldBe "كانغ"
        ArabicTransliterator.transliterate("Jin-Woo") shouldBe "جين-وو"
    }

    @Test
    fun `second opinion prefers the glossary and no english`() {
        val glossary = mapOf("Sung Jinwoo" to "سونغ جين وو")
        val good = TranslationConsensusEngine.score("Sung Jinwoo attacks!", "سونغ جين وو يهاجم!", glossary)
        val bad = TranslationConsensusEngine.score("Sung Jinwoo attacks!", "Sung Jinwoo يهاجم!", glossary)
        (good >= bad + TranslationConsensusEngine.MARGIN) shouldBe true
        TranslationConsensusEngine.score("Hi", "", glossary) shouldBe -100
    }

    @Test
    fun `night translation only for english sources and untranslated titles`() {
        NightRules.translatable("en") shouldBe true
        NightRules.translatable("en-US") shouldBe true
        NightRules.translatable("ar") shouldBe false
        NightRules.translatable("all") shouldBe false
        NightRules.looksArabic("الفصل 5") shouldBe true
        NightRules.looksArabic("Chapter 5") shouldBe false
    }

    @Test
    fun `prefetch waits for enough keys, battery and internet`() {
        val good = ChapterPrefetchManager.Device(batteryPercent = 60, charging = false, powerSave = false, online = true)
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 3, device = good) shouldBe true
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 2, device = good) shouldBe false
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 5, device = good.copy(batteryPercent = 10)) shouldBe false
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 5, device = good.copy(batteryPercent = 10, charging = true)) shouldBe true
        ChapterPrefetchManager.allowed(manual = false, auto = true, workingKeys = 5, device = good.copy(powerSave = true)) shouldBe false
        ChapterPrefetchManager.allowed(manual = true, auto = false, workingKeys = 0, device = good) shouldBe true
        ChapterPrefetchManager.allowed(manual = true, auto = true, workingKeys = 5, device = good.copy(online = false)) shouldBe false
    }

    @Test
    fun `the hero is the first main character`() {
        val cast = listOf(
            CharacterStore.Character(1, "Cha Hae-In", role = "SUPPORTING"),
            CharacterStore.Character(2, "Sung Jin-Woo", role = "MAIN"),
        )
        CastRules.heroOf(cast)?.id shouldBe 2
        cast.sortedBy { CastRules.roleOrder(it.role) }.first().id shouldBe 2
    }
}

class ManhwaFeaturesTest {

    @Test
    fun `missing chapters are whole-number gaps only`() {
        eu.kanade.tachiyomi.ui.manga.ChapterGaps.missing(listOf(1.0, 2.0, 3.0, 4.0, 6.0, 7.0, 8.0, 9.0, 10.0)) shouldBe listOf(5)
        eu.kanade.tachiyomi.ui.manga.ChapterGaps.missing(listOf(1.0, 1.5, 2.0, 3.0)) shouldBe emptyList()
        // Numbered by volume (huge jumps): not missing chapters
        eu.kanade.tachiyomi.ui.manga.ChapterGaps.missing(listOf(1.0, 100.0, 200.0, 300.0)) shouldBe emptyList()
        eu.kanade.tachiyomi.ui.manga.ChapterGaps.describe(listOf(3, 4, 5, 9)) shouldBe "3–5, 9"
    }

    @Test
    fun `glossary pack round trip and foreign files refused`() {
        val entries = listOf(eu.kanade.translation.glossary.GlossaryStore.Entry("Sung Jinwoo", "سونغ جين وو", kind = "character", gender = "male"))
        val text = eu.kanade.translation.glossary.GlossaryPack.encode(entries, emptyList())
        eu.kanade.translation.glossary.GlossaryPack.decode(text)?.entries shouldBe entries
        eu.kanade.translation.glossary.GlossaryPack.decode("{}") shouldBe null
        eu.kanade.translation.glossary.GlossaryPack.decode("not json") shouldBe null
    }

    @Test
    fun `chapter quality score`() {
        val report = eu.kanade.translation.team.ChapterQuality.Report(fromLibrary = 60, byAi = 40, doubtful = listOf("a", "b", "c", "d"))
        report.libraryPercent shouldBe 60
        report.score shouldBe 96
        eu.kanade.translation.team.ChapterQuality.Report().score shouldBe 0
    }
}

class WhatsNewTest {
    @Test
    fun `shown once for every new build`() {
        eu.kanade.translation.whatsnew.WhatsNewRule.shouldShow(null, "120-abc") shouldBe true
        eu.kanade.translation.whatsnew.WhatsNewRule.shouldShow("120-abc", "120-abc") shouldBe false
        eu.kanade.translation.whatsnew.WhatsNewRule.shouldShow("119-zzz", "120-abc") shouldBe true
    }
}

class FiveFeaturesTest {

    @Test
    fun `inpainting fills a hole with the drawing around it`() {
        val w = 120
        val h = 60
        val dark = 0xFF202020.toInt()
        val light = 0xFFE0E0E0.toInt()
        val px = IntArray(w * h) { i -> if (((i % w + i / w) / 6) % 2 == 0) dark else light }
        val hole = BooleanArray(w * h) { i -> (i % w) in 40 until 80 && (i / w) in 20 until 40 }
        for (i in hole.indices) if (hole[i]) px[i] = 0xFFFF0000.toInt()
        eu.kanade.translation.render.ExemplarInpainter.fill(px, w, h, hole) shouldBe true
        // Every pixel of the hole is a colour of the drawing, none of the letters' red is left
        px.indices.filter { hole[it] }.all { px[it] == dark || px[it] == light } shouldBe true
        eu.kanade.translation.render.ExemplarInpainter.fill(px.copyOf(), w, h, BooleanArray(w * h) { true }) shouldBe false
    }

    @Test
    fun `style profile`() {
                StyleProfile.libraryKey("ARABIC", StyleProfile.Dialect.EGYPTIAN) shouldBe "ARABIC_EG"
        StyleProfile.libraryKey("ARABIC", StyleProfile.Dialect.LIGHT) shouldBe "ARABIC"
        StyleProfile.libraryKey("ENGLISH", StyleProfile.Dialect.EGYPTIAN) shouldBe "ENGLISH"
        var p = StyleProfile.Profile()
        p = StyleProfile.withChoice(p, "Let's go!", "يلا بينا!", "هيا بنا لنذهب!")
        p.shorter shouldBe 1
        p.choices.size shouldBe 1
        p = StyleProfile.withChoice(p, "Let's go!", "يلا!", "يلا بينا!")
        p.choices.size shouldBe 1
        val text = StyleProfile.instructionFor(StyleProfile.Dialect.EGYPTIAN, StyleProfile.Honorifics.KEEP, p)
        text.contains("Egyptian") shouldBe true
        text.contains("يلا!") shouldBe true
    }

    @Test
    fun `work brain never shows what comes after the last chapter read`() {
                var brain = SeriesBrain.Brain()
        brain = BrainRules.merge(brain, listOf(BrainRules.NewEntity("Jinwoo", "جين وو", "character", "The hero", "E-rank")), emptyList(), SeriesBrain.Event(1.0, "Ch 1", "Start"), 1.0)
        brain = BrainRules.merge(brain, listOf(BrainRules.NewEntity("Jinwoo", "", "character", "", "S-rank"), BrainRules.NewEntity("Igris", "إيغريس", "character", "Knight", "")), listOf(SeriesBrain.Relation("Igris", "Jinwoo", "تابع", 80.0)), SeriesBrain.Event(80.0, "Ch 80", "Reawakening"), 80.0)
        val early = BrainRules.upTo(brain, 10.0)
        early.entities.map { it.name } shouldBe listOf("Jinwoo")
        early.entities.first().history.map { it.text } shouldBe listOf("E-rank")
        early.relations shouldBe emptyList()
        early.events.size shouldBe 1
        BrainRules.upTo(brain, 80.0).entities.first { it.name == "Jinwoo" }.history.last().text shouldBe "S-rank"
        BrainRules.describe(early, 2000).contains("S-rank") shouldBe false
    }

    @Test
    fun `one work across sources`() {
        eu.kanade.translation.unified.UnifiedRules.sameWork("Solo Leveling", "solo leveling (Official)") shouldBe true
        eu.kanade.translation.unified.UnifiedRules.sameWork("Solo Leveling", "Solo Leveling: Ragnarok") shouldBe false
        val theirs = listOf(
            eu.kanade.translation.unified.UnifiedSources.AltChapter(5.0, "5", "A", 1, 10),
            eu.kanade.translation.unified.UnifiedSources.AltChapter(5.0, "5", "B", 2, 20),
            eu.kanade.translation.unified.UnifiedSources.AltChapter(7.0, "7", "B", 2, 21),
            eu.kanade.translation.unified.UnifiedSources.AltChapter(2.0, "2", "A", 1, 11),
        )
        eu.kanade.translation.unified.UnifiedRules.missing(listOf(1.0, 2.0, 3.0, 4.0, 6.0), theirs).map { it.number to it.sourceName } shouldBe
            listOf(5.0 to "A", 7.0 to "B")
    }

    @Test
    fun `on-device model replies`() {
        eu.kanade.translation.local.LocalAiRules.parse("Sure! {\"t\":[\"مرحبا\",\"يلا\"]} done") shouldBe listOf("مرحبا", "يلا")
        eu.kanade.translation.local.LocalAiRules.parse("no json") shouldBe null
        eu.kanade.translation.local.LocalAiRules.usable("Hello", "مرحبا", "ar") shouldBe true
        eu.kanade.translation.local.LocalAiRules.usable("Hello", "Hello", "ar") shouldBe false
        eu.kanade.translation.local.LocalAiRules.usable("Hello", "Hello friend", "ar") shouldBe false
        eu.kanade.translation.local.LocalAiRules.usable("Hello", "", "ar") shouldBe false
    }
}
