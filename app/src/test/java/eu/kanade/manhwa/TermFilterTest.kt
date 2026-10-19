package eu.kanade.manhwa

import eu.kanade.translation.glossary.TermFilter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TermFilterTest {

    @Test
    fun `names and short terms in Arabic are kept`() {
        TermFilter.accepts("Doyul", "دويول", arabicTarget = true) shouldBe true
        TermFilter.accepts("12 NATIONS", "الأمم الاثنتا عشرة", arabicTarget = true) shouldBe true
        TermFilter.accepts("Shadow Monarch", "ملك الظلال", arabicTarget = true) shouldBe true
    }

    @Test
    fun `an English answer is dropped for an Arabic glossary`() {
        TermFilter.accepts("AMARAN ACH", "Amaran Ach", arabicTarget = true) shouldBe false
        TermFilter.accepts("12 NATIONS", "The Twelve Nations", arabicTarget = true) shouldBe false
        TermFilter.accepts("12 NATIONS", "The Twelve Nations", arabicTarget = false) shouldBe true
    }

    @Test
    fun `whole lines, questions and cries are not names`() {
        TermFilter.accepts("...WOW. ARE YOU THE SCHOOL BOSS?", "سؤال", arabicTarget = true) shouldBe false
        TermFilter.accepts("..HUH?", "مرتبك", arabicTarget = true) shouldBe false
        TermFilter.accepts("ALRIGHT, KEEP ACTING BRAVE!", "شجاع", arabicTarget = true) shouldBe false
        TermFilter.accepts("ALL 21 GANG MEMBERS PRESENT", "العصابة", arabicTarget = true) shouldBe false
    }

    @Test
    fun `site credits and handles are dropped`() {
        TermFilter.accepts("12 Nations (Read at HIVETDON.COM)", "منظمة", arabicTarget = true) shouldBe false
        TermFilter.accepts("12 Nations (Faster Updates)", "أمر", arabicTarget = true) shouldBe false
        TermFilter.accepts("+3 (O]@nusamnu", "+3", arabicTarget = true) shouldBe false
    }

    @Test
    fun `a cleaned glossary pack says it replaces, an old pack does not`() {
        val cleaned = """{"format": "manhwa-glossary", "replace": true, "entries": [{"source": "Doyul", "translation": "دو يول"}]}"""
        eu.kanade.translation.glossary.GlossaryPack.decode(cleaned)?.replace shouldBe true
        eu.kanade.translation.glossary.GlossaryPack.decode("""{"format": "manhwa-glossary", "entries": []}""")?.replace shouldBe false
    }

    @Test
    fun `a glossary file touched by an editor still imports`() {
        val pack = eu.kanade.translation.glossary.GlossaryPack
        val bom = "\uFEFF{\"format\": \"manhwa-glossary\", \"entries\": [{\"source\": \"Doyul\", \"translation\": \"دو يول\"},],}"
        pack.decode(bom)?.entries?.map { it.translation } shouldBe listOf("دو يول")
        pack.decode("""[{"source": "Doyul", "translation": "دو يول"}]""")?.entries?.size shouldBe 1
        pack.decode("""{"Doyul": "دو يول", "Dong-gu": "دونغ غو"}""")?.entries?.size shouldBe 2
        pack.decode("""{"entries": [{"source": "Doyul", "translation": "دو يول"}]}""")?.entries?.size shouldBe 1
    }

    @Test
    fun `an exported glossary is the whole glossary`() {
        val entries = listOf(eu.kanade.translation.glossary.GlossaryStore.Entry("Doyul", "دو يول"))
        val pack = eu.kanade.translation.glossary.GlossaryPack
        pack.decode(pack.encode(entries, emptyList()))?.replace shouldBe true
    }
}
