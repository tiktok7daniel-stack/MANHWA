package eu.kanade.manhwa

import eu.kanade.tachiyomi.novel.Role
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.speech.BubbleRoles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleRolesTest {

    private fun block(translation: String, kind: String = "speech") = TranslationBlock(
        text = "x", translation = translation, width = 10f, height = 10f, x = 0f, y = 0f,
        symHeight = 1f, symWidth = 1f, angle = 0f, kind = kind,
    )

    @Test
    fun `narration is the narrator`() {
        BubbleRoles.roleOf(block("في ذلك اليوم تغير كل شيء", kind = "narration")) shouldBe Role.NARRATOR
    }

    @Test
    fun `a speaker who talks of herself in the feminine is a woman`() {
        BubbleRoles.roleOf(block("أنا متعبة جدا اليوم")) shouldBe Role.FEMALE
        BubbleRoles.roleOf(block("لست متأكدة من هذا")) shouldBe Role.FEMALE
    }

    @Test
    fun `other speech is a man`() {
        BubbleRoles.roleOf(block("سأقتلك بيدي هاتين")) shouldBe Role.MALE
    }
}
