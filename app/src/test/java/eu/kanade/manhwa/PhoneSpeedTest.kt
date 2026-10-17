package eu.kanade.manhwa

import eu.kanade.translation.local.NpuRules
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhoneSpeedTest {

    @Test
    fun `the NPU is tried only on a Qualcomm phone`() {
        // RedMagic 10S Pro (Snapdragon 8 Elite)
        NpuRules.qualcomm("QTI", "qcom", "sun") shouldBe true
        NpuRules.qualcomm("", "qcom", "") shouldBe true
        NpuRules.qualcomm("Mediatek", "mt6989", "k6989v1_64") shouldBe false
        NpuRules.qualcomm("Samsung", "s5e9945", "s5e9945") shouldBe false
        NpuRules.qualcomm(null, null, null) shouldBe false
    }
}
