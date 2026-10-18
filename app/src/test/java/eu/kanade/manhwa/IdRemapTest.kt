package eu.kanade.manhwa

import eu.kanade.tachiyomi.data.backup.vault.IdMap
import eu.kanade.tachiyomi.data.backup.vault.IdRemapRules
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test

class IdRemapTest {

    private val works = mapOf(10L to 110L, 20L to 220L, 30L to 330L)
    private val chapters = mapOf(500L to 1500L)
    private val m: (Long) -> Long? = { works[it] }
    private val c: (Long) -> Long? = { chapters[it] }

    @Test
    fun `files named after a work or a chapter take the new id`() {
        IdRemapRules.renamed("glossary/10.json", m, c) shouldBe "glossary/110.json"
        IdRemapRules.renamed("story/20.json", m, c) shouldBe "story/220.json"
        IdRemapRules.renamed("lessons/global.json", m, c) shouldBe "lessons/global.json"
        IdRemapRules.renamed("translation_edits/d500.json", m, c) shouldBe "translation_edits/d1500.json"
        IdRemapRules.renamed("translation_checkpoints/500.done.json", m, c) shouldBe "translation_checkpoints/1500.done.json"
        IdRemapRules.renamed("characters/solo.json", m, c) shouldBe "characters/solo.json"
        // A work that is not on this phone: its file is left out rather than given to another work
        IdRemapRules.renamed("story/99.json", m, c) shouldBe null
    }

    @Test
    fun `a fusion keeps its works and chapters on the new phone`() {
        val fusions = Json.parseToJsonElement(
            """{"fusions":[{"baseMangaId":10,"otherMangaId":20,"otherSourceId":777,"kept":[{"mangaId":30,"sourceId":5,""" +
                """"chapters":[{"number":1.0,"chapterId":500},{"number":2.0,"chapterId":501}]}]},{"baseMangaId":99,"otherMangaId":20}],"dismissed":[10,99]}""",
        )
        val out = IdRemapRules.remap("chapter_fusions.json", fusions, m, c).jsonObject
        val list = out["fusions"]!!.jsonArray
        list.size shouldBe 1
        val fusion = list[0].jsonObject
        fusion["baseMangaId"]!!.jsonPrimitive.long shouldBe 110L
        fusion["otherMangaId"]!!.jsonPrimitive.long shouldBe 220L
        fusion["otherSourceId"]!!.jsonPrimitive.long shouldBe 777L
        val kept = fusion["kept"]!!.jsonArray[0].jsonObject
        kept["mangaId"]!!.jsonPrimitive.long shouldBe 330L
        kept["chapters"]!!.jsonArray.map { it.jsonObject["chapterId"]!!.jsonPrimitive.long } shouldBe listOf(1500L)
        out["dismissed"]!!.jsonArray.map { it.jsonPrimitive.long } shouldBe listOf(110L)
    }

    @Test
    fun `maps keyed by a work take the new keys`() {
        val unified = Json.parseToJsonElement(
            """{"10":{"links":[{"sourceId":1,"sourceName":"A","mangaId":20,"title":"t"},{"sourceId":2,"sourceName":"B","mangaId":98,"title":"u"}]},"99":{"links":[]}}""",
        )
        val out = IdRemapRules.remap("unified_sources.json", unified, m, c).jsonObject
        out.keys shouldBe setOf("110")
        out["110"]!!.jsonObject["links"]!!.jsonArray.size shouldBe 1
        IdRemapRules.remap("glossary_works.json", Json.parseToJsonElement("""{"10":"a","20":"a","99":"b"}"""), m, c).jsonObject.keys shouldBe setOf("110", "220")
    }

    @Test
    fun `the ids a backup needs are all found`() {
        val fusions = Json.parseToJsonElement("""{"fusions":[{"baseMangaId":10,"otherMangaId":20,"kept":[{"mangaId":30,"chapters":[{"chapterId":500}]}]}]}""")
        IdRemapRules.idsOf("chapter_fusions.json", fusions) shouldBe (setOf(10L, 20L, 30L) to setOf(500L))
        IdRemapRules.idsOf("glossary/42.json", null) shouldBe (setOf(42L) to emptySet())
        IdRemapRules.idsOf("translation_edits/l7.json", null) shouldBe (emptySet<Long>() to setOf(7L))
    }

    @Test
    fun `the map travels as it is`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val map = IdMap(
            listOf(IdMap.MangaRef(1, 2, "/w", "Work", false, IdMap.Details(status = 1), listOf(IdMap.ChapterData("/c", "Ch 1", 1.0)))),
            listOf(IdMap.ChapterRef(5, 1, "/c")),
        )
        json.decodeFromString(IdMap.serializer(), json.encodeToString(IdMap.serializer(), map)) shouldBe map
    }
}
