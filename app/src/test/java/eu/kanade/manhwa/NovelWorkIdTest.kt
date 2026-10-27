package eu.kanade.manhwa

import eu.kanade.tachiyomi.novel.NovelStore
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class NovelWorkIdTest {

    @Test
    fun `a novel's id is negative so it never meets a manga's`() {
        listOf("1|/a", "42|https://site.example/novel/x", "").forEach { NovelStore.workId(it) shouldBeLessThan 0L }
    }

    @Test
    fun `the same novel keeps the same id`() {
        NovelStore.workId("7|/novel/one") shouldBe NovelStore.workId("7|/novel/one")
    }

    @Test
    fun `two novels get two ids`() {
        NovelStore.workId("7|/novel/one") shouldNotBe NovelStore.workId("7|/novel/two")
        NovelStore.workId("7|/novel/one") shouldNotBe NovelStore.workId("8|/novel/one")
    }
}
