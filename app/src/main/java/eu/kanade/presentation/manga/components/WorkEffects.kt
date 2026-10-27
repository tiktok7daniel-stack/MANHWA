package eu.kanade.presentation.manga.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.music.MusicStore
import eu.kanade.tachiyomi.music.rememberBeat
import tachiyomi.i18n.at.ATMR
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * MANHWA: living effects drawn behind a work's cover or over its page: blue fire, glowing eyes, lightning, a warp of
 * stars, an energy orb, a plasma burst, a magic circle, beams of light, rising spirits and embers. All drawn here in
 * code (no pictures), in any colour, and they can pulse with the work's song.
 */
enum class WorkEffect(val label: StringResource) {
    NONE(ATMR.strings.effect_none),
    FIRE(ATMR.strings.effect_fire),
    EYES(ATMR.strings.effect_eyes),
    LIGHTNING(ATMR.strings.effect_lightning),
    WARP(ATMR.strings.effect_warp),
    ORB(ATMR.strings.effect_orb),
    PLASMA(ATMR.strings.effect_plasma),
    CIRCLE(ATMR.strings.effect_circle),
    BEAMS(ATMR.strings.effect_beams),
    SPIRIT(ATMR.strings.effect_spirit),
    EMBERS(ATMR.strings.effect_embers),
    AURORA(ATMR.strings.effect_aurora),
    GALAXY(ATMR.strings.effect_galaxy),
    SAKURA(ATMR.strings.effect_sakura),
    SNOW(ATMR.strings.effect_snow),
    RAIN(ATMR.strings.effect_rain),
    BOKEH(ATMR.strings.effect_bokeh),
    SHOCKWAVE(ATMR.strings.effect_shockwave),
    BLACKHOLE(ATMR.strings.effect_blackhole),
    FIREFLIES(ATMR.strings.effect_fireflies),
    SMOKE(ATMR.strings.effect_smoke),
    ;

    companion object {
        fun of(key: String?): WorkEffect = entries.firstOrNull { it.name == key } ?: NONE
    }
}

/** The colours an effect can take; [AUTO] follows the cover. */
enum class EffectColor(val color: Color?) {
    AUTO(null),
    BLUE(Color(0xFF2F8CFF)),
    CYAN(Color(0xFF19E3FF)),
    PURPLE(Color(0xFFA24BFF)),
    PINK(Color(0xFFFF3FA4)),
    RED(Color(0xFFFF3B30)),
    GOLD(Color(0xFFFFB020)),
    GREEN(Color(0xFF2BFF88)),
    WHITE(Color(0xFFE8ECFF)),
    ;

    companion object {
        fun of(key: String?): EffectColor = entries.firstOrNull { it.name == key } ?: AUTO
    }
}

/**
 * Draws [effect] filling its bounds in [color]. [beat] (0..1, read every frame) makes it flare with the song;
 * [strength] dims it, for the page.
 */
@Composable
fun EffectLayer(
    effect: WorkEffect,
    color: Color,
    modifier: Modifier = Modifier,
    strength: Float = 1f,
    beat: () -> Float = { 0f },
    still: Boolean = false,
) {
    if (effect == WorkEffect.NONE) return
    // A still effect (the picker's small previews) is drawn once, at one moment, and never again
    // MANHWA battery: the app's one decoration clock (about 30 frames a second, resting when left alone)
    val clock = if (still) null else eu.kanade.presentation.components.LocalDecorTime.current
    val seeds = remember(effect) { Random(effect.ordinal * 7919 + 17).let { r -> FloatArray(SEEDS) { r.nextFloat() } } }
    Canvas(modifier) {
        val t = clock?.value ?: 7.3f
        val b = beat().coerceIn(0f, 1f)
        when (effect) {
            WorkEffect.NONE -> Unit
            WorkEffect.FIRE -> fire(t, color, b, strength, seeds)
            WorkEffect.EYES -> eyes(t, color, b, strength)
            WorkEffect.LIGHTNING -> lightning(t, color, b, strength)
            WorkEffect.WARP -> warp(t, color, b, strength, seeds)
            WorkEffect.ORB -> orb(t, color, b, strength)
            WorkEffect.PLASMA -> plasma(t, color, b, strength)
            WorkEffect.CIRCLE -> magicCircle(t, color, b, strength)
            WorkEffect.BEAMS -> beams(t, color, b, strength, seeds)
            WorkEffect.SPIRIT -> spirit(t, color, b, strength, seeds)
            WorkEffect.EMBERS -> embers(t, color, b, strength, seeds)
            WorkEffect.AURORA -> aurora(t, color, b, strength)
            WorkEffect.GALAXY -> galaxy(t, color, b, strength, seeds)
            WorkEffect.SAKURA -> sakura(t, color, b, strength, seeds)
            WorkEffect.SNOW -> snow(t, color, b, strength, seeds)
            WorkEffect.RAIN -> rain(t, color, b, strength, seeds)
            WorkEffect.BOKEH -> bokeh(t, color, b, strength, seeds)
            WorkEffect.SHOCKWAVE -> shockwave(t, color, b, strength)
            WorkEffect.BLACKHOLE -> blackHole(t, color, b, strength, seeds)
            WorkEffect.FIREFLIES -> fireflies(t, color, b, strength, seeds)
            WorkEffect.SMOKE -> smoke(t, color, b, strength, seeds)
        }
    }
}

private const val SEEDS = 1024

private fun fract(x: Float) = x - floor(x)

/** A soft round light, added onto what is under it. */
private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (radius <= 0.5f || alpha <= 0.003f) return
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent), center, radius),
        radius,
        center,
        blendMode = BlendMode.Plus,
    )
}

/** A line with a wide faint halo and a bright thin core: how light looks on a dark page. */
private fun DrawScope.lightLine(path: Path, color: Color, width: Float, alpha: Float) {
    drawPath(path, color.copy(alpha = 0.10f * alpha), style = Stroke(width * 6f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawPath(path, color.copy(alpha = 0.35f * alpha), style = Stroke(width * 2.4f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawPath(path, Color.White.copy(alpha = 0.85f * alpha), style = Stroke(width, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
}

private fun white(color: Color, amount: Float) = lerp(color, Color.White, amount.coerceIn(0f, 1f))

// ------------------------------------------------------------------------------------------------ Fire

private fun DrawScope.fire(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val base = h * 0.98f
    val height = h * (0.78f + 0.12f * b)
    // The bed of the flame
    glow(Offset(cx, base), w * 0.42f, c, 0.45f * k)
    val n = 120
    for (i in 0 until n) {
        val life = 1.2f + s[i] * 0.9f
        val age = fract(t / life + s[i + 120])
        val spread = (s[i + 240] - 0.5f) * 2f
        val fall = (1f - age).pow(0.7f)
        val x = cx + spread * w * 0.30f * fall + sin(t * 4.2f + i * 1.7f) * w * 0.05f * age
        val y = base - age * height * (0.65f + 0.35f * s[i + 360])
        val r = w * (0.035f + 0.05f * s[i + 400]) * (1f - age).pow(0.8f) * (1f + 0.35f * b) + 2f
        val tone = if (age < 0.25f) white(c, 0.75f - age * 2.4f) else c
        glow(Offset(x, y), r * 2.2f, tone, (0.55f - age * 0.5f) * k)
    }
    // The white-hot heart
    glow(Offset(cx, base - h * 0.12f), w * 0.16f * (1f + 0.3f * b), white(c, 0.7f), 0.55f * k)
}

// ------------------------------------------------------------------------------------------------ Eyes

private fun DrawScope.eyes(t: Float, c: Color, b: Float, k: Float) {
    val w = size.width
    val h = size.height
    val cy = h * 0.32f
    val gap = w * 0.11f
    val ew = w * 0.2f
    // A blink every few seconds
    val cycle = fract(t / 5.3f)
    val open = if (cycle > 0.96f) abs(cycle - 0.98f) / 0.02f else 1f
    val eh = ew * 0.26f * open.coerceIn(0.05f, 1f)
    val flicker = 0.85f + 0.15f * sin(t * 23f) * sin(t * 7f) + 0.25f * b
    for (side in listOf(-1f, 1f)) {
        val inner = Offset(w / 2 + side * gap, cy + ew * 0.10f)
        val outer = Offset(w / 2 + side * (gap + ew), cy - ew * 0.10f)
        val mid = Offset((inner.x + outer.x) / 2, (inner.y + outer.y) / 2)
        val eye = Path().apply {
            moveTo(inner.x, inner.y)
            quadraticTo(mid.x, mid.y - eh * 1.4f, outer.x, outer.y)
            quadraticTo(mid.x + side * ew * 0.1f, mid.y + eh * 0.9f, inner.x, inner.y)
            close()
        }
        // The light pouring out of the eye
        glow(mid, ew * 1.3f, c, 0.35f * k * flicker)
        drawPath(eye, c.copy(alpha = 0.18f * k), style = Stroke(ew * 0.22f), blendMode = BlendMode.Plus)
        drawPath(eye, white(c, 0.4f).copy(alpha = 0.45f * k), style = Stroke(ew * 0.08f), blendMode = BlendMode.Plus)
        drawPath(eye, Color.White.copy(alpha = (0.95f * k * flicker).coerceAtMost(1f)), blendMode = BlendMode.Plus)
        // Streaks of light from the outer corner
        for (j in 0 until 3) {
            val len = ew * (0.5f + 0.3f * j + 0.25f * sin(t * 9f + j * 2f) + 0.4f * b)
            val streak = Path().apply {
                moveTo(outer.x, outer.y)
                quadraticTo(outer.x + side * len * 0.5f, outer.y - eh * (0.6f + j * 0.5f), outer.x + side * len, outer.y - eh * (0.4f + j * 0.9f))
            }
            lightLine(streak, c, 1.4f + (2 - j) * 0.6f, k * flicker * (0.9f - j * 0.25f))
        }
    }
}

// ------------------------------------------------------------------------------------------------ Lightning

private fun DrawScope.lightning(t: Float, c: Color, b: Float, k: Float) {
    val w = size.width
    val h = size.height
    val tick = floor(t * 7f).toInt()
    val phase = fract(t * 7f)
    val bolts = 2 + (b * 3).toInt()
    for (n in 0 until bolts) {
        val rnd = Random(tick * 131 + n * 17)
        // Not every bolt strikes every moment
        if (rnd.nextFloat() > 0.55f + 0.35f * b) continue
        val alpha = (1f - phase) * k
        val horizontal = rnd.nextBoolean()
        var p = if (horizontal) Offset(0f, h * (0.15f + 0.7f * rnd.nextFloat())) else Offset(w * (0.1f + 0.8f * rnd.nextFloat()), 0f)
        val path = Path().apply { moveTo(p.x, p.y) }
        val branches = mutableListOf<Path>()
        val steps = 14
        for (i in 1..steps) {
            p = if (horizontal) {
                Offset(w * i / steps, p.y + (rnd.nextFloat() - 0.5f) * h * 0.12f)
            } else {
                Offset(p.x + (rnd.nextFloat() - 0.5f) * w * 0.12f, h * i / steps)
            }
            path.lineTo(p.x, p.y)
            if (rnd.nextFloat() < 0.25f) {
                var q = p
                val branch = Path().apply { moveTo(q.x, q.y) }
                repeat(4) {
                    q = Offset(q.x + (rnd.nextFloat() - 0.3f) * w * 0.07f, q.y + (rnd.nextFloat() - 0.3f) * h * 0.07f)
                    branch.lineTo(q.x, q.y)
                }
                branches += branch
            }
        }
        lightLine(path, c, 2.2f, alpha)
        branches.forEach { lightLine(it, c, 1.1f, alpha * 0.7f) }
    }
    // The sky lights up with the strike
    glow(Offset(w / 2, h / 2), maxOf(w, h) * 0.7f, c, 0.12f * k * (1f - phase) * (0.4f + b))
}

// ------------------------------------------------------------------------------------------------ Warp

private fun DrawScope.warp(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2, h / 2)
    val far = maxOf(w, h) * 0.75f
    glow(center, min(w, h) * 0.35f, c, 0.35f * k)
    for (i in 0 until 160) {
        val a = s[i] * 2f * PI.toFloat()
        val z = fract(t * (0.25f + 0.35f * s[i + 160]) + s[i + 320])
        val r1 = z * z * far
        val r0 = ((z - 0.05f - 0.06f * b).coerceAtLeast(0f)).let { it * it * far }
        val d = Offset(cos(a), sin(a))
        val from = center + d * r0
        val to = center + d * r1
        val tone = if (i % 3 == 0) c else white(c, 0.7f)
        drawLine(tone.copy(alpha = (z * 0.9f * k).coerceAtMost(1f)), from, to, strokeWidth = 0.6f + z * 3.2f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
    }
}

// ------------------------------------------------------------------------------------------------ Orb

private fun DrawScope.orb(t: Float, c: Color, b: Float, k: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val r = min(size.width, size.height) * 0.30f * (1f + 0.08f * b)
    glow(center, r * 2.4f, c, 0.40f * k)
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.9f * k),
            0.35f to white(c, 0.45f).copy(alpha = 0.75f * k),
            0.8f to c.copy(alpha = 0.55f * k),
            1f to c.copy(alpha = 0f),
            center = center + Offset(-r * 0.2f, -r * 0.25f),
            radius = r * 1.1f,
        ),
        r,
        center,
        blendMode = BlendMode.Plus,
    )
    // Rings of energy turning round it
    for (i in 0 until 3) {
        rotate(t * (40f + i * 25f) * if (i % 2 == 0) 1f else -1f, center) {
            val ring = Size(r * (2.5f + i * 0.35f), r * (1.1f + i * 0.25f))
            val topLeft = center - Offset(ring.width / 2, ring.height / 2)
            drawArc(c.copy(alpha = 0.22f * k), 0f, 360f, false, topLeft, ring, style = Stroke(9f), blendMode = BlendMode.Plus)
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, white(c, 0.6f).copy(alpha = 0.95f * k), Color.Transparent), center),
                t * 90f,
                160f,
                false,
                topLeft,
                ring,
                style = Stroke(2.5f, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ Plasma

private fun DrawScope.plasma(t: Float, c: Color, b: Float, k: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val reach = min(size.width, size.height) * (0.42f + 0.1f * b)
    val tick = floor(t * 9f).toInt()
    val rnd = Random(tick)
    glow(center, reach * 1.2f, c, 0.35f * k)
    for (i in 0 until 46) {
        var a = i / 46f * 2f * PI.toFloat() + rnd.nextFloat() * 0.2f
        var p = center
        val len = reach * (0.55f + 0.45f * rnd.nextFloat())
        val path = Path().apply { moveTo(p.x, p.y) }
        val steps = 9
        for (j in 1..steps) {
            a += (rnd.nextFloat() - 0.5f) * 0.7f
            p += Offset(cos(a), sin(a)) * (len / steps)
            path.lineTo(p.x, p.y)
        }
        drawPath(path, c.copy(alpha = 0.30f * k), style = Stroke(3.5f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        drawPath(path, white(c, 0.55f).copy(alpha = 0.6f * k), style = Stroke(1.1f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    }
    glow(center, reach * 0.35f * (1f + 0.4f * b), Color.White, 0.85f * k)
}

// ------------------------------------------------------------------------------------------------ Magic circle

private fun DrawScope.magicCircle(t: Float, c: Color, b: Float, k: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val r = min(size.width, size.height) * 0.46f
    val a = k * (0.8f + 0.2f * sin(t * 2f) + 0.3f * b)
    fun ring(radius: Float, width: Float) {
        drawCircle(c.copy(alpha = 0.18f * a), radius, center, style = Stroke(width * 5f), blendMode = BlendMode.Plus)
        drawCircle(white(c, 0.5f).copy(alpha = 0.85f * a), radius, center, style = Stroke(width), blendMode = BlendMode.Plus)
    }
    glow(center, r * 1.15f, c, 0.22f * k)
    rotate(t * 10f, center) {
        ring(r, 2.4f)
        ring(r * 0.9f, 1.4f)
        // Runes between the two outer rings
        for (i in 0 until 72) {
            val ang = i / 72f * 2f * PI.toFloat()
            val d = Offset(cos(ang), sin(ang))
            val long = i % 6 == 0
            drawLine(
                white(c, 0.4f).copy(alpha = 0.8f * a),
                center + d * (r * 0.915f),
                center + d * (r * if (long) 0.985f else 0.95f),
                strokeWidth = if (long) 2.4f else 1.3f,
                blendMode = BlendMode.Plus,
            )
        }
    }
    rotate(-t * 18f, center) {
        // Two triangles: the six-pointed star
        for (start in listOf(-90f, 90f)) {
            val star = Path()
            for (i in 0..3) {
                val ang = ((start + i * 120f) * PI / 180f).toFloat()
                val p = center + Offset(cos(ang), sin(ang)) * (r * 0.8f)
                if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
            }
            lightLine(star, c, 1.6f, a)
        }
        for (i in 0 until 6) {
            val ang = ((-90f + i * 60f) * PI / 180f).toFloat()
            val p = center + Offset(cos(ang), sin(ang)) * (r * 0.8f)
            drawCircle(white(c, 0.5f).copy(alpha = 0.9f * a), r * 0.06f, p, style = Stroke(1.6f), blendMode = BlendMode.Plus)
            glow(p, r * 0.12f, c, 0.4f * a)
        }
    }
    ring(r * 0.42f, 1.6f)
    glow(center, r * 0.3f * (1f + 0.5f * b), white(c, 0.5f), 0.45f * k)
}

// ------------------------------------------------------------------------------------------------ Beams

private fun DrawScope.beams(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    val origin = Offset(w / 2, h * 1.02f)
    for (i in 0 until 40) {
        val ang = ((-90f + (s[i] - 0.5f) * 130f) * PI / 180f).toFloat()
        val len = h * (0.45f + 0.5f * s[i + 40]) * (0.85f + 0.15f * sin(t * (0.8f + s[i + 80]) + i)) * (1f + 0.15f * b)
        val half = (0.008f + 0.02f * s[i + 120])
        val tip = origin + Offset(cos(ang), sin(ang)) * len
        val l = origin + Offset(cos(ang - half), sin(ang - half)) * len
        val rr = origin + Offset(cos(ang + half), sin(ang + half)) * len
        val path = Path().apply {
            moveTo(origin.x, origin.y)
            lineTo(l.x, l.y)
            lineTo(rr.x, rr.y)
            close()
        }
        val flicker = (0.55f + 0.45f * sin(t * (1.5f + 2f * s[i + 160]) + i * 3f)) * (0.7f + 0.5f * b)
        val tone = if (i % 4 == 0) white(c, 0.7f) else c
        drawPath(
            path,
            Brush.linearGradient(listOf(tone.copy(alpha = (0.55f * flicker * k).coerceAtMost(1f)), Color.Transparent), origin, tip),
            blendMode = BlendMode.Plus,
        )
    }
    glow(origin, w * 0.45f, white(c, 0.4f), 0.6f * k)
}

// ------------------------------------------------------------------------------------------------ Spirit

private fun DrawScope.spirit(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    for (i in 0 until 22) {
        val x0 = w * (0.2f + 0.6f * s[i])
        val freq = 6f + 8f * s[i + 22]
        val amp = w * (0.02f + 0.05f * s[i + 44]) * (1f + 0.5f * b)
        val speed = 0.12f + 0.12f * s[i + 66]
        val segments = 40
        var prev: Offset? = null
        for (j in 0..segments) {
            val v = j / segments.toFloat()
            // Each strand climbs and thins out as it rises
            val rise = fract(v + t * speed + s[i + 88])
            val y = h * (1f - v) * 0.95f + h * 0.04f
            val x = x0 + sin(v * freq + t * 2f + i) * amp * v * 1.6f + (w / 2 - x0) * v * v * 0.5f
            val p = Offset(x, y)
            prev?.let { q ->
                val a = (sin(rise * PI.toFloat()) * (1f - v * 0.7f) * 0.55f * k).coerceAtLeast(0f)
                drawLine(c.copy(alpha = a), q, p, strokeWidth = 2.2f * (1f - v * 0.6f), cap = StrokeCap.Round, blendMode = BlendMode.Plus)
                drawLine(Color.White.copy(alpha = a * 0.45f), q, p, strokeWidth = 0.8f, blendMode = BlendMode.Plus)
            }
            prev = p
        }
    }
    glow(Offset(w / 2, h), w * 0.4f, c, 0.4f * k)
}

// ------------------------------------------------------------------------------------------------ Embers

private fun DrawScope.embers(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    for (i in 0 until 80) {
        val life = 3f + 4f * s[i]
        val age = fract(t / life + s[i + 80])
        val x = w * s[i + 160] + sin(t * (0.8f + s[i + 240]) + i) * w * 0.05f
        val y = h * (1.05f - age * 1.15f)
        val r = (1.5f + 3.5f * s[i + 320]) * (1f + 0.6f * b)
        val twinkle = 0.6f + 0.4f * sin(t * (4f + 6f * s[i + 400]) + i)
        val a = sin(age * PI.toFloat()) * twinkle * k
        val tone = if (i % 3 == 0) white(c, 0.6f) else c
        drawLine(tone.copy(alpha = a * 0.35f), Offset(x, y), Offset(x, y + r * 6f), strokeWidth = r * 0.8f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
        glow(Offset(x, y), r * 4f, tone, a * 0.7f)
        drawCircle(Color.White.copy(alpha = a * 0.9f), r * 0.6f, Offset(x, y), blendMode = BlendMode.Plus)
    }
}

// ------------------------------------------------------------------------------------------------ Aurora

private fun DrawScope.aurora(t: Float, c: Color, b: Float, k: Float) {
    val w = size.width
    val h = size.height
    // Curtains of light: tall soft bands that ripple sideways and shift in colour
    for (band in 0 until 3) {
        val tone = if (band == 1) white(c, 0.35f) else lerp(c, Color(0xFF2BFF88), 0.25f * band)
        val steps = 48
        for (i in 0 until steps) {
            val f = i / (steps - 1f)
            val x = w * f
            val y = h * (0.28f + 0.1f * band) + sin(f * 6f + t * (0.6f + band * 0.2f)) * h * 0.06f + sin(f * 13f - t) * h * 0.02f
            val tall = h * (0.22f + 0.12f * sin(f * 9f + t * 1.3f + band)) * (1f + 0.3f * b)
            val a = (0.10f + 0.08f * sin(f * 20f + t * 2f + band)) * k
            drawLine(
                Brush.verticalGradient(listOf(Color.Transparent, tone.copy(alpha = a.coerceAtLeast(0f)), tone.copy(alpha = a * 0.3f), Color.Transparent), y - tall, y + tall * 0.4f),
                Offset(x, y - tall),
                Offset(x, y + tall * 0.4f),
                strokeWidth = w / steps * 1.6f,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ Galaxy

private fun DrawScope.galaxy(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val center = Offset(size.width / 2, size.height / 2)
    val reach = min(size.width, size.height) * 0.48f
    glow(center, reach * 0.9f, c, 0.3f * k)
    // Two spiral arms of stars, turning slowly
    for (i in 0 until 260) {
        val arm = i % 2
        val d = s[i]
        val r = reach * d.pow(0.7f)
        val a = d * 7f + arm * PI.toFloat() + t * 0.25f + (s[i + 260] - 0.5f) * 0.6f
        val p = center + Offset(cos(a), sin(a) * 0.55f) * r
        val tone = if (d < 0.25f) white(c, 0.8f) else if (i % 5 == 0) white(c, 0.5f) else c
        val tw = 0.6f + 0.4f * sin(t * 3f + i)
        drawCircle(tone.copy(alpha = ((1f - d) * 0.8f * tw * k).coerceIn(0f, 1f)), 0.8f + 1.8f * s[i + 520] * (1f + 0.4f * b), p, blendMode = BlendMode.Plus)
    }
    glow(center, reach * 0.18f * (1f + 0.4f * b), Color.White, 0.8f * k)
}

// ------------------------------------------------------------------------------------------------ Sakura

private fun DrawScope.sakura(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    for (i in 0 until 46) {
        val life = 6f + 6f * s[i]
        val age = fract(t / life + s[i + 46])
        val x = w * fract(s[i + 92] + age * 0.35f) + sin(t * (1f + s[i + 138]) + i) * w * 0.06f
        val y = h * (-0.05f + age * 1.1f)
        val r = (5f + 7f * s[i + 184]) * (1f + 0.3f * b)
        val spin = t * (60f + 80f * s[i + 230]) + i * 40f
        val flip = abs(cos(t * (1.5f + s[i + 276]) + i))
        rotate(spin, Offset(x, y)) {
            val petal = Path().apply {
                moveTo(x, y - r)
                quadraticTo(x + r * flip, y - r * 0.2f, x, y + r)
                quadraticTo(x - r * flip, y - r * 0.2f, x, y - r)
                close()
            }
            drawPath(petal, Brush.verticalGradient(listOf(white(c, 0.75f).copy(alpha = 0.95f * k), c.copy(alpha = 0.85f * k)), y - r, y + r))
        }
    }
}

// ------------------------------------------------------------------------------------------------ Snow

private fun DrawScope.snow(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    for (i in 0 until 110) {
        val depth = s[i]
        val life = 5f + 9f * (1f - depth)
        val age = fract(t / life + s[i + 110])
        val x = w * s[i + 220] + sin(t * (0.6f + s[i + 330]) + i) * w * 0.04f * (0.5f + depth)
        val y = h * (-0.03f + age * 1.06f)
        val r = 1f + 3.2f * depth
        glow(Offset(x, y), r * 3f, white(c, 0.7f), (0.35f + 0.3f * depth) * k * (1f + 0.4f * b))
        drawCircle(Color.White.copy(alpha = (0.6f + 0.4f * depth) * k), r, Offset(x, y))
    }
}

// ------------------------------------------------------------------------------------------------ Rain

private fun DrawScope.rain(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    val slant = w * 0.04f
    for (i in 0 until 120) {
        val speed = 1.2f + s[i]
        val age = fract(t * speed * 0.8f + s[i + 120])
        val x = w * s[i + 240] - slant * age
        val y = h * (-0.1f + age * 1.2f)
        val len = h * (0.04f + 0.05f * s[i + 360]) * (1f + 0.5f * b)
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, white(c, 0.5f).copy(alpha = 0.6f * k)), y - len, y),
            Offset(x + slant * 0.05f, y - len),
            Offset(x, y),
            strokeWidth = 1.2f + 0.8f * s[i + 480],
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        // A splash ring where it lands
        if (age > 0.92f) {
            val g = (age - 0.92f) / 0.08f
            drawCircle(c.copy(alpha = (1f - g) * 0.4f * k), 3f + g * 10f, Offset(x, h * 0.98f), style = Stroke(1f), blendMode = BlendMode.Plus)
        }
    }
}

// ------------------------------------------------------------------------------------------------ Bokeh

private fun DrawScope.bokeh(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    for (i in 0 until 26) {
        val x = w * fract(s[i] + t * 0.01f * (s[i + 26] - 0.5f))
        val y = h * fract(s[i + 52] - t * 0.015f * (0.3f + s[i + 78]))
        val r = min(w, h) * (0.04f + 0.09f * s[i + 104]) * (1f + 0.15f * b)
        val tone = if (i % 3 == 0) white(c, 0.5f) else c
        val a = (0.12f + 0.12f * sin(t * (0.8f + s[i + 130]) + i)) * k
        drawCircle(tone.copy(alpha = a.coerceAtLeast(0f)), r, Offset(x, y), blendMode = BlendMode.Plus)
        drawCircle(tone.copy(alpha = (a * 1.6f).coerceIn(0f, 1f)), r, Offset(x, y), style = Stroke(1.4f), blendMode = BlendMode.Plus)
    }
}

// ------------------------------------------------------------------------------------------------ Shockwave

private fun DrawScope.shockwave(t: Float, c: Color, b: Float, k: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val far = maxOf(size.width, size.height) * 0.7f
    // Rings rolling out from the middle, a new one on each beat-like pulse
    for (i in 0 until 4) {
        val g = fract(t * 0.45f + i * 0.25f)
        val r = far * g
        val a = (1f - g) * k * (0.7f + 0.6f * b)
        drawCircle(c.copy(alpha = (0.25f * a).coerceIn(0f, 1f)), r, center, style = Stroke(18f * (1f - g) + 2f), blendMode = BlendMode.Plus)
        drawCircle(white(c, 0.6f).copy(alpha = (0.8f * a).coerceIn(0f, 1f)), r, center, style = Stroke(2.2f), blendMode = BlendMode.Plus)
    }
    glow(center, far * 0.2f * (1f + 0.6f * b), white(c, 0.5f), 0.6f * k)
}

// ------------------------------------------------------------------------------------------------ Black hole

private fun DrawScope.blackHole(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val center = Offset(size.width / 2, size.height / 2)
    val r = min(size.width, size.height) * 0.16f * (1f + 0.08f * b)
    glow(center, r * 4.5f, c, 0.35f * k)
    // Matter spiralling in
    for (i in 0 until 150) {
        val life = 2.5f + 2.5f * s[i]
        val age = fract(t / life + s[i + 150])
        val dist = r * (1.1f + 3.2f * (1f - age))
        val a = s[i + 300] * 2f * PI.toFloat() + age * 5f
        val p = center + Offset(cos(a), sin(a) * 0.45f) * dist
        val tone = if (age > 0.7f) white(c, 0.7f) else c
        drawCircle(tone.copy(alpha = (age * 0.9f * k).coerceIn(0f, 1f)), 1f + 1.6f * s[i + 450], p, blendMode = BlendMode.Plus)
    }
    // The bright ring and the dark heart
    rotate(t * 30f, center) {
        drawOval(
            Brush.sweepGradient(listOf(white(c, 0.8f), c, Color.Transparent, c, white(c, 0.8f)), center),
            topLeft = center - Offset(r * 1.9f, r * 0.55f),
            size = androidx.compose.ui.geometry.Size(r * 3.8f, r * 1.1f),
            style = Stroke(r * 0.18f),
            blendMode = BlendMode.Plus,
        )
    }
    drawCircle(Color.Black, r, center)
    drawCircle(white(c, 0.6f).copy(alpha = 0.9f * k), r * 1.04f, center, style = Stroke(r * 0.08f), blendMode = BlendMode.Plus)
}

// ------------------------------------------------------------------------------------------------ Fireflies

private fun DrawScope.fireflies(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    for (i in 0 until 34) {
        val x = w * (s[i] + 0.08f * sin(t * (0.3f + 0.4f * s[i + 34]) + i * 2f))
        val y = h * (s[i + 68] + 0.08f * cos(t * (0.25f + 0.4f * s[i + 102]) + i))
        val blink = (0.5f + 0.5f * sin(t * (1.2f + 2f * s[i + 136]) + i * 3f)).let { it * it }
        val tone = lerp(c, Color(0xFFFFF59D), 0.35f)
        glow(Offset(x, y), 14f + 10f * b, tone, blink * 0.7f * k)
        drawCircle(Color.White.copy(alpha = blink * 0.9f * k), 1.8f, Offset(x, y), blendMode = BlendMode.Plus)
    }
}

// ------------------------------------------------------------------------------------------------ Smoke

private fun DrawScope.smoke(t: Float, c: Color, b: Float, k: Float, s: FloatArray) {
    val w = size.width
    val h = size.height
    // Soft puffs rising and spreading, overlapping into drifting smoke
    for (i in 0 until 40) {
        val life = 5f + 4f * s[i]
        val age = fract(t / life + s[i + 40])
        val x = w * (0.2f + 0.6f * s[i + 80]) + sin(t * 0.7f + i) * w * 0.08f * age
        val y = h * (1.05f - age * 0.95f)
        val r = min(w, h) * (0.08f + 0.22f * age) * (1f + 0.2f * b)
        val a = sin(age * PI.toFloat()) * 0.22f * k
        glow(Offset(x, y), r, if (i % 4 == 0) white(c, 0.4f) else c, a)
    }
}

/**
 * MANHWA: what can be picked for a spot: one effect, one of the fifty mixes (two effects layered), or "every day"
 * (a different mix each day, chosen by itself). Stored as a key: the effect's name, `MIX_<n>`, or `DAILY`.
 */
object EffectChoice {
    const val DAILY = "DAILY"
    private val bases = WorkEffect.entries.filter { it != WorkEffect.NONE }

    /** The fifty mixes: pairs of different effects, the same ones in the same order on every phone. */
    val mixes: List<Pair<WorkEffect, WorkEffect>> by lazy {
        val pairs = bases.indices.flatMap { i -> (i + 1 until bases.size).map { j -> bases[i] to bases[j] } }
        pairs.shuffled(Random(2026)).take(MIXES)
    }

    const val MIXES = 50

    fun mixKey(i: Int) = "MIX_$i"

    /** Every key a picker offers: none, the effects one by one, every day, then the mixes. */
    val keys: List<String> by lazy {
        listOf(WorkEffect.NONE.name) + bases.map { it.name } + DAILY + (0 until MIXES).map(::mixKey)
    }

    /** The hour (local time) at which "every day" moves on to the next mix. */
    const val CHANGE_HOUR = 6L

    /** Today's number for "every day": it turns over at [CHANGE_HOUR], not at midnight. */
    fun today(): Long = java.time.LocalDateTime.now().minusHours(CHANGE_HOUR).toLocalDate().toEpochDay()

    /** The effects [key] stands for today, for the work [workId]. */
    fun resolve(key: String?, workId: Long, day: Long = today()): List<WorkEffect> {
        if (key == null) return emptyList()
        if (key == DAILY) {
            val i = ((day * 31 + workId) % MIXES + MIXES) % MIXES
            return mixes[i.toInt()].toList()
        }
        if (key.startsWith("MIX_")) {
            return key.removePrefix("MIX_").toIntOrNull()?.let { mixes.getOrNull(it) }?.toList().orEmpty()
        }
        return listOf(WorkEffect.of(key)).filter { it != WorkEffect.NONE }
    }
}

/** Several effects layered, each a little softer so together they stay clear. */
@Composable
fun EffectLayers(
    effects: List<WorkEffect>,
    color: Color,
    modifier: Modifier = Modifier,
    strength: Float = 1f,
    beat: () -> Float = { 0f },
    still: Boolean = false,
) {
    // Effects laid over each other are each lighter, so two together are not twice as heavy
    val each = if (effects.size > 1) strength * 0.6f else strength
    effects.forEach { EffectLayer(it, color, modifier, each, beat, still) }
}

/** A work's effects as picked: behind the cover, over the page, their colour, and the beat they follow. */
class WorkEffects(
    val coverKey: String?,
    val pageKey: String?,
    val cover: List<WorkEffect>,
    val page: List<WorkEffect>,
    val color: Color?,
    /** The song's beat if the effects are set to follow it, else 0. */
    val beat: () -> Float,
    /** The song's beat, always (for the music bars). */
    val rawBeat: () -> Float,
)

@Composable
fun rememberWorkEffects(workId: Long): WorkEffects {
    val context = LocalContext.current
    val store = remember { MusicStore.get(context) }
    val look by remember(workId) { store.lookFlow(workId) }.collectAsState(store.work(workId))
    val beat = rememberBeat(workId)
    val follow = look.effectBeat
    // "Every day" moves on by itself at the change hour, also while the page stays open
    val day by produceState(EffectChoice.today()) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            value = EffectChoice.today()
        }
    }
    // Until the reader picks, two effects sit behind every cover by themselves: a mix of its own, new each day
    val coverKey = look.coverEffect ?: EffectChoice.DAILY
    return WorkEffects(
        coverKey = coverKey,
        pageKey = look.pageEffect,
        cover = remember(coverKey, workId, day) { EffectChoice.resolve(coverKey, workId, day) },
        page = remember(look.pageEffect, workId, day) { EffectChoice.resolve(look.pageEffect, workId, day) },
        color = EffectColor.of(look.effectColor).color,
        beat = { if (follow) beat.value else 0f },
        rawBeat = { beat.value },
    )
}
