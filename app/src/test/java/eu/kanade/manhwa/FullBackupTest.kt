package eu.kanade.manhwa

import eu.kanade.tachiyomi.data.backup.vault.FullBackupRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FullBackupTest {

    @Test
    fun `paths from a backup never leave their folder`() {
        FullBackupRules.safePath("Source/Work/ch 1.json") shouldBe "Source/Work/ch 1.json"
        FullBackupRules.safePath("/Source//Work/") shouldBe "Source/Work"
        FullBackupRules.safePath("Source/../../etc") shouldBe null
        FullBackupRules.safePath("./x") shouldBe null
        FullBackupRules.safePath("") shouldBe null
    }

    @Test
    fun `phone models and passing files are left out`() {
        FullBackupRules.keepDir("models") shouldBe false
        FullBackupRules.keepDir("models/gemma") shouldBe false
        FullBackupRules.keepDir("glossary") shouldBe true
        FullBackupRules.keepDir("exts") shouldBe true
        FullBackupRules.keepFile("page.part") shouldBe false
        FullBackupRules.keepFile("001.jpg") shouldBe true
    }

    @Test
    fun `only the settings that belong to the reader travel`() {
        FullBackupRules.keepPrefs("manhwa_lessons_stats") shouldBe true
        FullBackupRules.keepPrefs("manhwa_vault") shouldBe false
        FullBackupRules.keepPrefs("manhwa_models") shouldBe false
        FullBackupRules.keepPrefs("tracking_queue") shouldBe false
    }

    @Test
    fun `pictures are stored as they are and progress stays in range`() {
        FullBackupRules.packed("Work/ch1/001.WEBP") shouldBe true
        FullBackupRules.packed("translations/x.json") shouldBe false
        FullBackupRules.percent(50, 200) shouldBe 25
        FullBackupRules.percent(5, 0) shouldBe 0
        FullBackupRules.percent(300, 200) shouldBe 100
    }
}
