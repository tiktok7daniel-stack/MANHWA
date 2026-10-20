package eu.kanade.manhwa

import eu.kanade.translation.site.SiteReader
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SiteReaderTest {

    @Test
    fun `only mangatek opens in the site reader`() {
        SiteReader.handles("MangaTek", "https://mangatek.com") shouldBe true
        SiteReader.handles("Manga Tek (AR)", "https://www.mangatek.com") shouldBe true
        SiteReader.handles("ميجا", "https://mangatek.com") shouldBe true
        SiteReader.handles("Asura Scans", "https://asuracomic.net") shouldBe false
        SiteReader.handles("MangaFire", null) shouldBe false
    }

    @Test
    fun `the reader stays on the site and its check`() {
        val base = "https://mangatek.com/reader/laws-of-the-good-child/42"
        SiteReader.allowed("mangatek.com", base) shouldBe true
        SiteReader.allowed("www.mangatek.com", base) shouldBe true
        SiteReader.allowed("cdn.mangatek.com", base) shouldBe true
        SiteReader.allowed("challenges.cloudflare.com", base) shouldBe true
        SiteReader.allowed("ads.example.com", base) shouldBe false
        SiteReader.allowed("notmangatek.com", base) shouldBe false
        SiteReader.allowed(null, base) shouldBe false
    }

    @Test
    fun `the page script is built`() {
        SiteReader.SCRIPT.contains("depth >= 2") shouldBe true
        SiteReader.SCRIPT.contains("bridge.done(index)") shouldBe true
    }
}
