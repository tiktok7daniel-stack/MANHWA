package eu.kanade.manhwa

import eu.kanade.translation.collection.CollectionReview
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CollectionReviewTest {

    private val line = CollectionReview.Line(k = "you came", src = "en", tgt = "ar", original = "You came!", translation = "جيت!", kind = "speech", uses = 4)

    @Test
    fun `a review file round trips and a foreign file is refused`() {
        val glossary = CollectionReview.Glossary(7L, "Lookism", listOf(CollectionReview.Term("Daniel", "دانيال", kind = "character", gender = "male")))
        val text = CollectionReview.encode(listOf(line), listOf(glossary), from = 1L, to = 2L, now = 3L)
        CollectionReview.decode(text)?.lines shouldBe listOf(line)
        CollectionReview.decode(text)?.glossaries shouldBe listOf(glossary)
        CollectionReview.decode("""{"format": "manhwa-glossary", "entries": []}""") shouldBe null
        CollectionReview.decode("not json") shouldBe null
    }

    @Test
    fun `a file touched by an editor still reads`() {
        val text = "﻿{\"format\": \"manhwa-collection-review\", \"lines\": [{\"k\": \"a\", \"src\": \"en\", \"tgt\": \"ar\", \"original\": \"A\", \"translation\": \"أ\"},],}"
        CollectionReview.decode(text)?.lines?.size shouldBe 1
        CollectionReview.decode(text)?.glossaries shouldBe emptyList()
    }

    @Test
    fun `a new wording corrects, the same approves, an empty one deletes`() {
        CollectionReview.outcome(line.copy(translation = "إنت جيت!"), "جيت!") shouldBe CollectionReview.Outcome.CORRECTED
        CollectionReview.outcome(line.copy(translation = " جيت! "), "جيت!") shouldBe CollectionReview.Outcome.APPROVED
        CollectionReview.outcome(line.copy(translation = ""), "جيت!") shouldBe CollectionReview.Outcome.DELETED
    }

    @Test
    fun `a name left empty in a glossary stays in the file, to be deleted`() {
        val glossary = CollectionReview.Glossary(7L, "Lookism", listOf(CollectionReview.Term("Daniel", ""), CollectionReview.Term("", "x")))
        val text = CollectionReview.encode(emptyList(), listOf(glossary), from = 0L, to = 0L, now = 1L)
        CollectionReview.decode(text)?.glossaries?.single()?.terms shouldBe listOf(CollectionReview.Term("Daniel", ""))
    }
}
