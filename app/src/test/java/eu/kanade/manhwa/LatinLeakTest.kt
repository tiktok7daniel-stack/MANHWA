package eu.kanade.manhwa

import eu.kanade.translation.translator.LatinLeak
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LatinLeakTest {

    @Test
    fun `latin bits a misread source left in an arabic bubble are taken out`() {
        LatinLeak.strip("قبض IW عليه لك.", "I'LL CATCH HIM FOR YOU.") shouldBe "قبض عليه لك."
        LatinLeak.strip("كان IFI في الماضي.", "x") shouldBe "كان في الماضي."
        LatinLeak.strip("هو هذا\nsoz", "x") shouldBe "هو هذا"
        LatinLeak.strip("ليس أن / S\ntuation أصبحت مثيرة", "x") shouldBe "ليس أن\nأصبحت مثيرة"
    }

    @Test
    fun `ranks numbers and bubbles not in arabic stay`() {
        LatinLeak.strip("صياد من الرتبة S", "AN S-RANK HUNTER, S") shouldBe "صياد من الرتبة S"
        LatinLeak.strip("لديه 600 قطعة", "600") shouldBe "لديه 600 قطعة"
        LatinLeak.strip("السعر 3.5 IW", "x") shouldBe "السعر 3.5"
        LatinLeak.strip("Hello", "Hello") shouldBe "Hello"
        LatinLeak.strip("مرحبا (بصوت عال)", "x") shouldBe "مرحبا (بصوت عال)"
    }
}
