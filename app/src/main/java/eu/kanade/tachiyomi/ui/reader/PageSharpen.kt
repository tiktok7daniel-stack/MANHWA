package eu.kanade.tachiyomi.ui.reader

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * MANHWA: sharpens the pages on the GPU (an unsharp mask: each pixel pushed away from the average of its four
 * neighbours), for soft, upscaled or over-compressed scans. Android 13 and later, where views take shaders.
 */
object PageSharpen {

    private const val AGSL = """
        uniform shader content;
        uniform float amount;
        half4 main(float2 p) {
            half4 c = content.eval(p);
            half3 n = content.eval(p + float2(0.0, -1.0)).rgb + content.eval(p + float2(0.0, 1.0)).rgb
                + content.eval(p + float2(-1.0, 0.0)).rgb + content.eval(p + float2(1.0, 0.0)).rgb;
            half3 s = c.rgb * (1.0 + 4.0 * amount) - n * amount;
            return half4(clamp(s, 0.0, 1.0), c.a);
        }
    """

    /** The effect for a sharpness of [value] (0 to 100), or null for none. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun effect(value: Int): RenderEffect? {
        if (value <= 0) return null
        return runCatching {
            val shader = RuntimeShader(AGSL)
            shader.setFloatUniform("amount", value.coerceIn(0, 100) / 100f * MAX_AMOUNT)
            RenderEffect.createRuntimeShaderEffect(shader, "content")
        }.getOrNull()
    }

    /** At 100 the page is pushed this far from its neighbours' average: crisp, without halos on line art. */
    private const val MAX_AMOUNT = 0.7f
}
