package eu.kanade.presentation.manga.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import eu.kanade.presentation.components.CoverPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * MANHWA: the title frames, drawn in code as shapes rather than pictures, so they are sharp at any size (as fine on
 * a 4K screen as on a small one) and alive: gems breathe, light runs along the edges, wings beat, flames flicker,
 * stars twinkle and sigils turn. Ten shapes in ten finishes make the hundred frames, and each shape also comes in the cover's own colours.
 */
enum class FrameShape(
    /** The part of the frame the letters go in, as fractions of its width and height. */
    val left: Float,
    val right: Float,
    val top: Float = 0.3f,
    val bottom: Float = 0.7f,
) {
    ROYAL(0.12f, 0.88f),
    CRYSTAL(0.17f, 0.83f),
    THORN(0.11f, 0.89f),
    NEON(0.12f, 0.88f),
    GOTHIC(0.15f, 0.85f, 0.38f, 0.76f),
    RUNE(0.17f, 0.83f),
    WINGS(0.24f, 0.76f),
    FLAME(0.11f, 0.89f, 0.34f, 0.72f),
    CELESTIAL(0.16f, 0.84f),
    BLADE(0.2f, 0.8f),
}

/** A frame's finish: its metal (light to dark), its face (top to bottom), its gems and its light. */
enum class FramePalette(
    val metal: List<Color>,
    val face: List<Color>,
    val gem: Color,
    val glow: Color,
    val lightFace: Boolean = false,
) {
    GOLD(listOf(Color(0xFFFFF6C8), Color(0xFFFFD36A), Color(0xFFB7832B), Color(0xFF6B4A16)), listOf(Color(0xFF2A1B4A), Color(0xFF120A24)), Color(0xFF9B5CFF), Color(0xFFFFC857)),
    SILVER(listOf(Color(0xFFFFFFFF), Color(0xFFD8DEE9), Color(0xFF8A94A6), Color(0xFF3B4252)), listOf(Color(0xFF1B2638), Color(0xFF0B1019)), Color(0xFF59C3FF), Color(0xFF9FD8FF)),
    CRIMSON(listOf(Color(0xFFFFD6D6), Color(0xFFE84545), Color(0xFF8E1B1B), Color(0xFF3A0A0A)), listOf(Color(0xFF2E0B12), Color(0xFF120407)), Color(0xFFFF2E4D), Color(0xFFFF4D6D)),
    EMERALD(listOf(Color(0xFFE7FFE9), Color(0xFF5BE38A), Color(0xFF1E8A4B), Color(0xFF0B3B20)), listOf(Color(0xFF0D2418), Color(0xFF05110B)), Color(0xFF2BFF88), Color(0xFF55FFA6)),
    ICE(listOf(Color(0xFFFFFFFF), Color(0xFFBFF4FF), Color(0xFF4FC3E8), Color(0xFF15506A)), listOf(Color(0xFF0B2A3A), Color(0xFF05131C)), Color(0xFF7DF9FF), Color(0xFF9AF6FF)),
    ROSE(listOf(Color(0xFFFFE4F1), Color(0xFFFF8CC6), Color(0xFFC2407F), Color(0xFF5A1238)), listOf(Color(0xFF2E0F24), Color(0xFF150610)), Color(0xFFFF4FA3), Color(0xFFFF8AC4)),
    VIOLET(listOf(Color(0xFFEFE2FF), Color(0xFFB48CFF), Color(0xFF6A3FC2), Color(0xFF26124F)), listOf(Color(0xFF1C1136), Color(0xFF0B0718)), Color(0xFFC77DFF), Color(0xFFB388FF)),
    SUN(listOf(Color(0xFFFFF3D6), Color(0xFFFFB547), Color(0xFFD9631E), Color(0xFF6E2508)), listOf(Color(0xFF2E1406), Color(0xFF140803)), Color(0xFFFF8A00), Color(0xFFFFB13B)),
    SHADOW(listOf(Color(0xFFB4B9CC), Color(0xFF5A5F75), Color(0xFF23263A), Color(0xFF0A0B10)), listOf(Color(0xFF101119), Color(0xFF050508)), Color(0xFF8A5CFF), Color(0xFF7C66FF)),
    PEARL(listOf(Color(0xFFFFFFFF), Color(0xFFF1E6FF), Color(0xFFC9B6E4), Color(0xFF8C79A8)), listOf(Color(0xFFFBF8FF), Color(0xFFE2D8F4)), Color(0xFFA47CFF), Color(0xFFFFFFFF), lightFace = true),

    /** In the work's own colours, read from its cover (these are only what it shows before the cover is read). */
    AUTO(listOf(Color(0xFFFFF6C8), Color(0xFFFFD36A), Color(0xFFB7832B), Color(0xFF6B4A16)), listOf(Color(0xFF2A1B4A), Color(0xFF120A24)), Color(0xFF9B5CFF), Color(0xFFFFC857)),
    ;

    val colors = FrameColors(metal, face, gem, glow, lightFace)
}

/** The colours a frame is drawn in: a finish's, or the cover's for [FramePalette.AUTO]. */
class FrameColors(
    val metal: List<Color>,
    val face: List<Color>,
    val gem: Color,
    val glow: Color,
    val lightFace: Boolean = false,
) {
    companion object {
        /** A finish made from the cover: its strongest colour as the metal and the light, its second as the gems. */
        fun of(cover: CoverPalette?, accent: Color): FrameColors {
            val p = cover?.primary ?: accent
            val s = cover?.secondary ?: lerp(accent, Color.White, 0.35f)
            val d = cover?.deep ?: lerp(accent, Color.Black, 0.86f)
            return FrameColors(
                listOf(lerp(p, Color.White, 0.85f), lerp(p, Color.White, 0.35f), p, lerp(p, Color.Black, 0.6f)),
                listOf(lerp(d, p, 0.3f), lerp(d, Color.Black, 0.5f)),
                s,
                p,
            )
        }
    }
}

/** One of the hundred: a [shape] in a [palette]. */
data class DrawnFrame(val shape: FrameShape, val palette: FramePalette) {
    val key: String get() = "F_${shape.name}_${palette.name}"
}

/**
 * Draws [frame] over the whole of this scope; [t] is the time in seconds, for its motion. [auto] are the colours of
 * an [FramePalette.AUTO] frame (the cover's).
 */
fun DrawScope.drawFrame(frame: DrawnFrame, t: Float, auto: FrameColors? = null) {
    val p = if (frame.palette == FramePalette.AUTO && auto != null) auto else frame.palette.colors
    when (frame.shape) {
        FrameShape.ROYAL -> royal(p, t)
        FrameShape.CRYSTAL -> crystal(p, t)
        FrameShape.THORN -> thorn(p, t)
        FrameShape.NEON -> neon(p, t)
        FrameShape.GOTHIC -> gothic(p, t)
        FrameShape.RUNE -> rune(p, t)
        FrameShape.WINGS -> wings(p, t)
        FrameShape.FLAME -> flame(p, t)
        FrameShape.CELESTIAL -> celestial(p, t)
        FrameShape.BLADE -> blade(p, t)
    }
}

// ------------------------------------------------------------------------------------------------ Parts

private fun metalBrush(p: FrameColors, top: Float, bottom: Float) = Brush.verticalGradient(
    0f to p.metal[0],
    0.3f to p.metal[1],
    0.55f to p.metal[2],
    0.8f to p.metal[3],
    1f to p.metal[1],
    startY = top,
    endY = bottom,
)

private fun roundBody(l: Float, t: Float, r: Float, b: Float, radius: Float) =
    Path().apply { addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(radius))) }

private fun chamferBody(l: Float, t: Float, r: Float, b: Float, c: Float) = Path().apply {
    moveTo(l + c, t)
    lineTo(r - c, t)
    lineTo(r, t + c)
    lineTo(r, b - c)
    lineTo(r - c, b)
    lineTo(l + c, b)
    lineTo(l, b - c)
    lineTo(l, t + c)
    close()
}

private fun pointedBody(l: Float, t: Float, r: Float, b: Float, point: Float) = Path().apply {
    val cy = (t + b) / 2
    moveTo(l + point, t)
    lineTo(r - point, t)
    lineTo(r, cy)
    lineTo(r - point, b)
    lineTo(l + point, b)
    lineTo(l, cy)
    close()
}

/** The plate itself: its face, a soft sheen, a dark rim for depth, the metal edge and its bright core. */
private fun DrawScope.body(path: Path, inner: Path?, p: FrameColors, top: Float, bottom: Float, sw: Float) {
    drawPath(path, Brush.verticalGradient(p.face, top, bottom))
    drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (p.lightFace) 0.5f else 0.12f), Color.Transparent), top, (top + bottom) / 2))
    // The gem colour glowing up from the bottom of the face
    drawPath(path, Brush.verticalGradient(listOf(Color.Transparent, p.gem.copy(alpha = 0.22f)), (top + bottom) / 2, bottom))
    drawPath(path, Color.Black.copy(alpha = 0.55f), style = Stroke(sw * 1.9f, join = StrokeJoin.Round))
    drawPath(path, metalBrush(p, top - sw, bottom + sw), style = Stroke(sw, join = StrokeJoin.Round))
    drawPath(path, p.metal[0].copy(alpha = 0.75f), style = Stroke(sw * 0.22f, join = StrokeJoin.Round))
    if (inner != null) {
        drawPath(inner, metalBrush(p, top, bottom), style = Stroke(sw * 0.32f, join = StrokeJoin.Round))
    }
}

/** A filled ornament in the metal, outlined dark so it stands off the page. */
private fun DrawScope.ornament(path: Path, p: FrameColors, top: Float, bottom: Float, sw: Float) {
    drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(sw * 0.7f, join = StrokeJoin.Round))
    drawPath(path, metalBrush(p, top, bottom))
    drawPath(path, p.metal[0].copy(alpha = 0.5f), style = Stroke(sw * 0.18f, join = StrokeJoin.Round))
}

private fun DrawScope.softGlow(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (radius <= 0.5f) return
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent), center, radius), radius, center, blendMode = BlendMode.Plus)
}

/** A cut gem that breathes light. */
private fun DrawScope.gem(center: Offset, r: Float, p: FrameColors, t: Float, phase: Float = 0f) {
    val pulse = 0.6f + 0.4f * sin(t * 2.6f + phase)
    softGlow(center, r * 3.2f, p.glow, 0.55f * pulse)
    val d = Path().apply {
        moveTo(center.x, center.y - r * 1.25f)
        lineTo(center.x + r, center.y)
        lineTo(center.x, center.y + r * 1.25f)
        lineTo(center.x - r, center.y)
        close()
    }
    drawPath(d, Color.Black.copy(alpha = 0.5f), style = Stroke(r * 0.35f))
    drawPath(d, metalBrush(p, center.y - r * 1.4f, center.y + r * 1.4f), style = Stroke(r * 0.22f))
    drawPath(
        d,
        Brush.radialGradient(
            listOf(Color.White, lerp(p.gem, Color.White, 0.35f), p.gem, lerp(p.gem, Color.Black, 0.45f)),
            center - Offset(r * 0.3f, r * 0.4f),
            r * 1.4f,
        ),
    )
    drawLine(Color.White.copy(alpha = 0.6f), Offset(center.x, center.y - r * 1.1f), Offset(center.x, center.y + r * 1.1f), strokeWidth = r * 0.08f)
    sparkle(center - Offset(r * 0.35f, r * 0.45f), r * (0.5f + 0.5f * pulse), Color.White, pulse)
}

/** A four-pointed glint. */
private fun DrawScope.sparkle(c: Offset, s: Float, color: Color, alpha: Float) {
    if (alpha <= 0.02f) return
    val star = Path().apply {
        moveTo(c.x, c.y - s)
        quadraticTo(c.x, c.y, c.x + s, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + s)
        quadraticTo(c.x, c.y, c.x - s, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - s)
        close()
    }
    drawPath(star, color.copy(alpha = alpha.coerceIn(0f, 1f)), blendMode = BlendMode.Plus)
    softGlow(c, s * 1.4f, color, alpha * 0.5f)
}

/** Light running round [path]: two bright beads chasing each other. */
private fun DrawScope.edgeRunner(path: Path, color: Color, t: Float, speed: Float, size: Float) {
    val m = PathMeasure()
    m.setPath(path, false)
    val len = m.length
    if (len <= 0f) return
    for (i in 0 until 2) {
        val d = ((t * speed + i * 0.5f) % 1f) * len
        val pos = m.getPosition(d)
        softGlow(pos, size * 4f, color, 0.8f)
        drawCircle(Color.White.copy(alpha = 0.9f), size * 0.6f, pos, blendMode = BlendMode.Plus)
    }
}

// ------------------------------------------------------------------------------------------------ Royal

private fun DrawScope.royal(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.07f
    val r = w * 0.93f
    val top = h * 0.24f
    val bot = h * 0.78f
    val bh = bot - top
    val sw = h * 0.045f
    val cx = w / 2
    val outer = roundBody(l, top, r, bot, bh * 0.18f)
    val inner = roundBody(l + sw * 1.8f, top + sw * 1.8f, r - sw * 1.8f, bot - sw * 1.8f, bh * 0.12f)
    body(outer, inner, p, top, bot, sw)
    // Scrolls curling off each corner
    val s = bh * 0.36f
    for ((x, y, sx, sy) in listOf(
        Quad(l + s * 0.7f, top, -1f, -1f),
        Quad(r - s * 0.7f, top, 1f, -1f),
        Quad(l + s * 0.7f, bot, -1f, 1f),
        Quad(r - s * 0.7f, bot, 1f, 1f),
    )) {
        val curl = Path().apply {
            moveTo(x, y)
            cubicTo(x + sx * s * 0.9f, y, x + sx * s * 1.15f, y + sy * s * 0.75f, x + sx * s * 0.6f, y + sy * s * 0.9f)
            cubicTo(x + sx * s * 0.2f, y + sy * s * 0.98f, x + sx * s * 0.15f, y + sy * s * 0.5f, x + sx * s * 0.48f, y + sy * s * 0.5f)
        }
        drawPath(curl, Color.Black.copy(alpha = 0.5f), style = Stroke(sw * 1.4f, cap = StrokeCap.Round))
        drawPath(curl, metalBrush(p, top - s, bot + s), style = Stroke(sw * 0.8f, cap = StrokeCap.Round))
        drawCircle(p.metal[1], sw * 0.6f, Offset(x + sx * s * 0.48f, y + sy * s * 0.5f))
    }
    // A crown on top with its gem, a jewel below
    val c = bh
    val crown = Path().apply {
        moveTo(cx - c * 0.5f, top + sw * 0.5f)
        lineTo(cx - c * 0.42f, top - c * 0.3f)
        lineTo(cx - c * 0.2f, top - c * 0.1f)
        lineTo(cx, top - c * 0.36f)
        lineTo(cx + c * 0.2f, top - c * 0.1f)
        lineTo(cx + c * 0.42f, top - c * 0.3f)
        lineTo(cx + c * 0.5f, top + sw * 0.5f)
        close()
    }
    ornament(crown, p, top - c * 0.4f, top, sw)
    drawCircle(p.metal[0], sw * 0.55f, Offset(cx - c * 0.42f, top - c * 0.3f))
    drawCircle(p.metal[0], sw * 0.55f, Offset(cx + c * 0.42f, top - c * 0.3f))
    gem(Offset(cx, top - c * 0.06f), bh * 0.11f, p, t)
    // Bars beside the jewels
    for (sx in listOf(-1f, 1f)) {
        drawLine(metalBrush(p, bot - sw, bot + sw), Offset(cx + sx * bh * 0.25f, bot), Offset(cx + sx * bh * 1.4f, bot), strokeWidth = sw * 0.9f, cap = StrokeCap.Round)
    }
    gem(Offset(cx, bot), bh * 0.09f, p, t, 1.7f)
    gem(Offset(l, (top + bot) / 2), bh * 0.07f, p, t, 3.1f)
    gem(Offset(r, (top + bot) / 2), bh * 0.07f, p, t, 4.4f)
}

private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

// ------------------------------------------------------------------------------------------------ Crystal

private fun DrawScope.crystal(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.12f
    val r = w * 0.88f
    val top = h * 0.26f
    val bot = h * 0.76f
    val bh = bot - top
    val sw = h * 0.04f
    val cy = (top + bot) / 2
    body(chamferBody(l, top, r, bot, bh * 0.28f), chamferBody(l + sw * 1.8f, top + sw * 1.8f, r - sw * 1.8f, bot - sw * 1.8f, bh * 0.22f), p, top, bot, sw)
    val rnd = Random(7)
    // Clusters of shards at both ends, glinting in turn
    for (side in listOf(-1f, 1f)) {
        val base = Offset(if (side < 0) l + bh * 0.12f else r - bh * 0.12f, cy)
        for (i in 0 until 8) {
            val spread = -70f + i * 20f + rnd.nextFloat() * 8f
            val angle = ((if (side < 0) 180f else 0f) + spread * (if (side < 0) -1f else 1f)) * PI.toFloat() / 180f
            val len = bh * (0.42f + 0.38f * rnd.nextFloat()) * (if (abs(spread) < 25f) 1.15f else 0.85f)
            shard(base, angle, len, p, t, i * 0.9f + side)
        }
    }
    // Small shards along the top
    for (i in 0 until 5) {
        val x = l + (r - l) * (0.25f + i * 0.125f)
        shard(Offset(x, top + sw), -PI.toFloat() / 2 + (i - 2) * 0.18f, bh * (if (i == 2) 0.5f else 0.3f), p, t, i * 1.3f)
    }
    gem(Offset(w / 2, bot), bh * 0.09f, p, t)
}

private fun DrawScope.shard(base: Offset, angle: Float, len: Float, p: FrameColors, t: Float, phase: Float) {
    val dir = Offset(cos(angle), sin(angle))
    val perp = Offset(-dir.y, dir.x)
    val tip = base + dir * len
    val mid = base + dir * (len * 0.38f)
    val path = Path().apply {
        moveTo(base.x, base.y)
        lineTo(mid.x + perp.x * len * 0.13f, mid.y + perp.y * len * 0.13f)
        lineTo(tip.x, tip.y)
        lineTo(mid.x - perp.x * len * 0.13f, mid.y - perp.y * len * 0.13f)
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(len * 0.06f, join = StrokeJoin.Round))
    drawPath(path, Brush.linearGradient(listOf(lerp(p.gem, Color.White, 0.55f), p.gem, lerp(p.gem, Color.Black, 0.4f)), base, tip))
    // One face lighter than the other
    val face = Path().apply {
        moveTo(base.x, base.y)
        lineTo(mid.x + perp.x * len * 0.13f, mid.y + perp.y * len * 0.13f)
        lineTo(tip.x, tip.y)
        close()
    }
    drawPath(face, Color.White.copy(alpha = 0.22f))
    drawLine(Color.White.copy(alpha = 0.7f), base, tip, strokeWidth = len * 0.025f)
    val glint = (sin(t * 1.8f + phase) * 0.5f + 0.5f).let { it * it * it }
    sparkle(base + dir * (len * 0.7f), len * 0.16f, Color.White, glint)
}

// ------------------------------------------------------------------------------------------------ Thorn

private fun DrawScope.thorn(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.06f
    val r = w * 0.94f
    val top = h * 0.27f
    val bot = h * 0.74f
    val bh = bot - top
    val sw = h * 0.045f
    val cy = (top + bot) / 2
    body(roundBody(l, top, r, bot, bh * 0.3f), roundBody(l + sw * 1.7f, top + sw * 1.7f, r - sw * 1.7f, bot - sw * 1.7f, bh * 0.22f), p, top, bot, sw)
    val rnd = Random(11)
    var x = l + bh * 0.45f
    var i = 0
    while (x < r - bh * 0.45f) {
        val len = bh * (0.2f + 0.24f * rnd.nextFloat())
        val lean = bh * 0.14f * (if (i % 2 == 0) 1f else -1f)
        spike(Offset(x, top + sw * 0.3f), -1f, len, lean, bh * 0.16f, p, top - len, top)
        if (i % 2 == 1) spike(Offset(x, bot - sw * 0.3f), 1f, len * 0.7f, -lean, bh * 0.14f, p, bot, bot + len)
        x += bh * (0.42f + 0.2f * rnd.nextFloat())
        i++
    }
    // Great horns at both ends
    for (side in listOf(-1f, 1f)) {
        val ex = if (side < 0) l else r
        val horn = Path().apply {
            moveTo(ex, cy - bh * 0.28f)
            quadraticTo(ex + side * bh * 0.55f, cy - bh * 0.35f, ex + side * bh * 0.42f, cy - bh * 0.85f)
            quadraticTo(ex + side * bh * 0.32f, cy - bh * 0.25f, ex - side * bh * 0.05f, cy)
            quadraticTo(ex + side * bh * 0.32f, cy + bh * 0.25f, ex + side * bh * 0.42f, cy + bh * 0.85f)
            quadraticTo(ex + side * bh * 0.55f, cy + bh * 0.35f, ex, cy + bh * 0.28f)
            close()
        }
        ornament(horn, p, cy - bh, cy + bh, sw)
    }
    // A red-hot vein of light runs along the edge
    edgeRunner(roundBody(l, top, r, bot, bh * 0.3f), p.glow, t, 0.08f, sw)
}

private fun DrawScope.spike(base: Offset, dir: Float, len: Float, lean: Float, width: Float, p: FrameColors, a: Float, b: Float) {
    val path = Path().apply {
        moveTo(base.x - width / 2, base.y)
        quadraticTo(base.x - width * 0.1f, base.y + dir * len * 0.45f, base.x + lean, base.y + dir * len)
        quadraticTo(base.x + width * 0.15f, base.y + dir * len * 0.4f, base.x + width / 2, base.y)
        close()
    }
    ornament(path, p, minOf(a, b), maxOf(a, b), width * 0.25f)
}

// ------------------------------------------------------------------------------------------------ Neon

private fun DrawScope.neon(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.07f
    val r = w * 0.93f
    val top = h * 0.24f
    val bot = h * 0.76f
    val bh = bot - top
    val sw = h * 0.028f
    val path = chamferBody(l, top, r, bot, bh * 0.32f)
    drawPath(path, Brush.verticalGradient(listOf(lerp(p.face[0], Color.Black, 0.3f), Color.Black.copy(alpha = 0.92f)), top, bot))
    // A faint grid behind the letters
    var gx = l + bh * 0.3f
    while (gx < r) {
        drawLine(p.glow.copy(alpha = 0.06f), Offset(gx, top), Offset(gx, bot), strokeWidth = 1f)
        gx += bh * 0.25f
    }
    // The tube: a wide soft halo, a coloured glow and a white-hot core, flickering now and then
    val flicker = if (sin(t * 13f) > 0.97f) 0.55f else 1f
    drawPath(path, p.glow.copy(alpha = 0.12f * flicker), style = Stroke(sw * 7f, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    drawPath(path, p.glow.copy(alpha = 0.55f * flicker), style = Stroke(sw * 2.2f, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    drawPath(path, Color.White.copy(alpha = 0.95f * flicker), style = Stroke(sw * 0.7f, join = StrokeJoin.Round))
    // Brackets off the corners
    val g = sw * 3f
    val arm = bh * 0.45f
    for ((x, y, sx, sy) in listOf(Quad(l - g, top - g, 1f, 1f), Quad(r + g, top - g, -1f, 1f), Quad(l - g, bot + g, 1f, -1f), Quad(r + g, bot + g, -1f, -1f))) {
        val bracket = Path().apply {
            moveTo(x + sx * arm, y)
            lineTo(x, y)
            lineTo(x, y + sy * arm * 0.6f)
        }
        drawPath(bracket, p.glow.copy(alpha = 0.5f), style = Stroke(sw * 2f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        drawPath(bracket, Color.White, style = Stroke(sw * 0.6f, cap = StrokeCap.Round))
    }
    // Circuit traces off both ends, with a pulse running out along them
    for (side in listOf(-1f, 1f)) {
        val ex = if (side < 0) l else r
        val cy = (top + bot) / 2
        val trace = Path().apply {
            moveTo(ex, cy)
            lineTo(ex + side * bh * 0.18f, cy)
            lineTo(ex + side * bh * 0.28f, cy - bh * 0.12f)
        }
        drawPath(trace, p.glow.copy(alpha = 0.8f), style = Stroke(sw * 0.8f))
        drawCircle(p.glow, sw * 1.2f, Offset(ex + side * bh * 0.28f, cy - bh * 0.12f), blendMode = BlendMode.Plus)
    }
    // Tick marks along the bottom, like a gauge
    for (i in 0 until 9) {
        val x = l + (r - l) * (0.3f + i * 0.05f)
        drawLine(p.glow.copy(alpha = if (((t * 4).toInt() % 9) == i) 1f else 0.35f), Offset(x, bot - sw * 3f), Offset(x, bot - sw * 4.6f), strokeWidth = sw * 0.7f, blendMode = BlendMode.Plus)
    }
    edgeRunner(path, p.glow, t, 0.22f, sw * 1.5f)
}

// ------------------------------------------------------------------------------------------------ Gothic

private fun DrawScope.gothic(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.06f
    val r = w * 0.94f
    val top = h * 0.32f
    val bot = h * 0.82f
    val bh = bot - top
    val sw = h * 0.04f
    val cx = w / 2
    body(pointedBody(l, top, r, bot, bh * 0.5f), pointedBody(l + sw * 2.4f, top + sw * 1.6f, r - sw * 2.4f, bot - sw * 1.6f, bh * 0.42f), p, top, bot, sw)
    // Spires along the top, tallest in the middle
    val n = 13
    for (i in 0 until n) {
        val f = i / (n - 1f)
        val x = l + bh * 0.6f + (r - l - bh * 1.2f) * f
        val tall = (1f - abs(f - 0.5f) * 2f).let { 0.18f + 0.36f * it * it } * (if (i % 2 == 0) 1f else 0.6f)
        val sp = Path().apply {
            moveTo(x - bh * 0.07f, top + sw * 0.5f)
            lineTo(x, top - bh * tall)
            lineTo(x + bh * 0.07f, top + sw * 0.5f)
            close()
        }
        ornament(sp, p, top - bh * 0.6f, top, sw * 0.6f)
    }
    // The rose window over the middle
    val rose = Offset(cx, top - bh * 0.02f)
    val rr = bh * 0.2f
    drawCircle(Color.Black.copy(alpha = 0.5f), rr * 1.15f, rose)
    drawCircle(metalBrush(p, rose.y - rr, rose.y + rr), rr, rose)
    drawCircle(Brush.radialGradient(listOf(lerp(p.gem, Color.White, 0.4f), p.gem, lerp(p.gem, Color.Black, 0.5f)), rose, rr * 0.8f), rr * 0.78f, rose)
    rotate(t * 20f, rose) {
        for (k in 0 until 8) {
            val a = k / 8f * 2f * PI.toFloat()
            drawLine(p.metal[1].copy(alpha = 0.9f), rose, rose + Offset(cos(a), sin(a)) * (rr * 0.78f), strokeWidth = sw * 0.3f)
        }
    }
    softGlow(rose, rr * 2.6f, p.glow, 0.35f + 0.2f * sin(t * 2f))
    // Teeth under the plate
    for (i in 0 until 7) {
        val x = cx + (i - 3) * bh * 0.32f
        val tooth = Path().apply {
            moveTo(x - bh * 0.06f, bot - sw * 0.5f)
            lineTo(x, bot + bh * (if (i == 3) 0.3f else 0.16f))
            lineTo(x + bh * 0.06f, bot - sw * 0.5f)
            close()
        }
        ornament(tooth, p, bot, bot + bh * 0.3f, sw * 0.6f)
    }
    gem(Offset(l + bh * 0.05f, (top + bot) / 2), bh * 0.07f, p, t, 1f)
    gem(Offset(r - bh * 0.05f, (top + bot) / 2), bh * 0.07f, p, t, 2f)
}

// ------------------------------------------------------------------------------------------------ Rune

private fun DrawScope.rune(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.1f
    val r = w * 0.9f
    val top = h * 0.28f
    val bot = h * 0.72f
    val bh = bot - top
    val sw = h * 0.035f
    val cy = (top + bot) / 2
    body(roundBody(l, top, r, bot, bh / 2), roundBody(l + sw * 1.8f, top + sw * 1.8f, r - sw * 1.8f, bot - sw * 1.8f, bh / 2), p, top, bot, sw)
    // Runes along the edges, lighting up one after another
    val marks = 22
    for (i in 0 until marks) {
        val x = l + bh * 0.8f + (r - l - bh * 1.6f) * i / (marks - 1f)
        val lit = (0.5f + 0.5f * sin(t * 3f - i * 0.6f)).let { it * it }
        val c = lerp(p.metal[2], p.glow, lit)
        val y1 = top + sw * 0.9f
        when (i % 3) {
            0 -> drawLine(c, Offset(x, y1), Offset(x, y1 + sw * 1.4f), strokeWidth = sw * 0.35f)
            1 -> drawCircle(c, sw * 0.4f, Offset(x, y1 + sw * 0.7f))
            else -> drawLine(c, Offset(x - sw * 0.5f, y1 + sw * 1.4f), Offset(x + sw * 0.5f, y1), strokeWidth = sw * 0.35f)
        }
        drawCircle(c, sw * 0.3f, Offset(x, bot - sw * 1.3f))
        if (lit > 0.6f) softGlow(Offset(x, y1 + sw * 0.7f), sw * 2.5f, p.glow, lit * 0.4f)
    }
    // A turning sigil over each end
    for ((k, ex) in listOf(l + bh / 2, r - bh / 2).withIndex()) {
        val c = Offset(ex, cy)
        val dir = if (k == 0) 1f else -1f
        val ring = bh * 0.64f
        softGlow(c, ring * 1.3f, p.glow, 0.3f)
        drawCircle(Color.Black.copy(alpha = 0.55f), ring * 0.82f, c)
        drawCircle(metalBrush(p, c.y - ring, c.y + ring), ring, c, style = Stroke(sw * 0.9f))
        drawCircle(p.metal[1], ring * 0.82f, c, style = Stroke(sw * 0.4f))
        rotate(t * 40f * dir, c) {
            for (i in 0 until 16) {
                val a = i / 16f * 2f * PI.toFloat()
                val d = Offset(cos(a), sin(a))
                drawLine(p.metal[0], c + d * (ring * 0.84f), c + d * (ring * (if (i % 4 == 0) 0.98f else 0.92f)), strokeWidth = sw * 0.35f)
            }
        }
        rotate(-t * 60f * dir, c) {
            val tri = Path()
            for (i in 0..3) {
                val a = (-90f + i * 120f) * PI.toFloat() / 180f
                val q = c + Offset(cos(a), sin(a)) * (ring * 0.62f)
                if (i == 0) tri.moveTo(q.x, q.y) else tri.lineTo(q.x, q.y)
            }
            drawPath(tri, p.glow.copy(alpha = 0.9f), style = Stroke(sw * 0.45f, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        }
        gem(c, ring * 0.22f, p, t, k * 2f)
    }
}

// ------------------------------------------------------------------------------------------------ Wings

private fun DrawScope.wings(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.2f
    val r = w * 0.8f
    val top = h * 0.28f
    val bot = h * 0.74f
    val bh = bot - top
    val sw = h * 0.04f
    val cy = (top + bot) / 2
    // The wings first, so the plate sits over their roots
    val beat = sin(t * 2.2f) * 5f
    for (side in listOf(-1f, 1f)) {
        val anchor = Offset(if (side < 0) l + bh * 0.25f else r - bh * 0.25f, cy - bh * 0.05f)
        val lengths = listOf(1.0f, 0.95f, 0.85f, 0.72f, 0.58f, 0.46f)
        lengths.forEachIndexed { i, f ->
            val deg = -42f + i * 15f + beat * (1f - i / 6f)
            val a = (if (side < 0) 180f - deg else deg) * PI.toFloat() / 180f
            feather(anchor, a, bh * 1.25f * f, p, i)
        }
    }
    body(roundBody(l, top, r, bot, bh * 0.25f), roundBody(l + sw * 1.7f, top + sw * 1.7f, r - sw * 1.7f, bot - sw * 1.7f, bh * 0.18f), p, top, bot, sw)
    gem(Offset(w / 2, top), bh * 0.1f, p, t)
    gem(Offset(w / 2, bot), bh * 0.07f, p, t, 2f)
}

private fun DrawScope.feather(anchor: Offset, angle: Float, len: Float, p: FrameColors, i: Int) {
    val dir = Offset(cos(angle), sin(angle))
    val perp = Offset(-dir.y, dir.x)
    val tip = anchor + dir * len
    val path = Path().apply {
        moveTo(anchor.x, anchor.y)
        val c1 = anchor + dir * (len * 0.45f) + perp * (len * 0.2f)
        quadraticTo(c1.x, c1.y, tip.x, tip.y)
        val c2 = anchor + dir * (len * 0.55f) - perp * (len * 0.12f)
        quadraticTo(c2.x, c2.y, anchor.x, anchor.y)
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(len * 0.04f, join = StrokeJoin.Round))
    drawPath(path, Brush.linearGradient(listOf(p.metal[if (i % 2 == 0) 0 else 1], p.metal[2], p.metal[3]), anchor, tip))
    drawLine(p.metal[0].copy(alpha = 0.7f), anchor, anchor + dir * (len * 0.9f), strokeWidth = len * 0.018f)
}

// ------------------------------------------------------------------------------------------------ Flame

private fun DrawScope.flame(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.06f
    val r = w * 0.94f
    val top = h * 0.32f
    val bot = h * 0.76f
    val bh = bot - top
    val sw = h * 0.04f
    // Tongues of fire along the top, never still
    val n = 15
    for (i in 0 until n) {
        val f = i / (n - 1f)
        val x = l + bh * 0.4f + (r - l - bh * 0.8f) * f
        val hgt = bh * (0.32f + 0.22f * (1f - abs(f - 0.5f) * 2f)) * (0.75f + 0.25f * sin(t * (5f + i % 3) + i * 1.7f))
        val sway = sin(t * 3f + i) * bh * 0.08f
        val wide = bh * 0.22f
        val tongue = Path().apply {
            moveTo(x - wide, top + sw)
            quadraticTo(x - wide * 0.6f, top - hgt * 0.5f, x + sway, top - hgt)
            quadraticTo(x + wide * 0.5f, top - hgt * 0.4f, x + wide, top + sw)
            close()
        }
        drawPath(tongue, Brush.verticalGradient(listOf(Color.Transparent, p.glow.copy(alpha = 0.85f), lerp(p.glow, Color.White, 0.6f)), top - hgt, top + sw), blendMode = BlendMode.Plus)
    }
    // Side flames
    for (side in listOf(-1f, 1f)) {
        val ex = if (side < 0) l else r
        val cy = (top + bot) / 2
        val len = bh * (0.42f + 0.08f * sin(t * 6f + side))
        val f = Path().apply {
            moveTo(ex, cy - bh * 0.3f)
            quadraticTo(ex + side * len * 0.6f, cy - bh * 0.25f, ex + side * len, cy + sin(t * 4f) * bh * 0.05f)
            quadraticTo(ex + side * len * 0.6f, cy + bh * 0.25f, ex, cy + bh * 0.3f)
            close()
        }
        drawPath(f, Brush.horizontalGradient(listOf(lerp(p.glow, Color.White, 0.5f), p.glow.copy(alpha = 0.7f), Color.Transparent), ex, ex + side * len), blendMode = BlendMode.Plus)
    }
    body(roundBody(l, top, r, bot, bh * 0.22f), roundBody(l + sw * 1.7f, top + sw * 1.7f, r - sw * 1.7f, bot - sw * 1.7f, bh * 0.16f), p, top, bot, sw)
    // Embers rising off it
    for (i in 0 until 14) {
        val age = ((t * 0.5f + i * 0.137f) % 1f)
        val x = l + (r - l) * ((i * 0.618f) % 1f) + sin(t * 2f + i) * bh * 0.1f
        val y = top - age * top
        drawCircle(lerp(p.glow, Color.White, 0.5f).copy(alpha = (1f - age) * 0.9f), sw * 0.25f * (1.2f - age), Offset(x, y), blendMode = BlendMode.Plus)
    }
    gem(Offset(w / 2, bot), bh * 0.09f, p, t)
}

// ------------------------------------------------------------------------------------------------ Celestial

private fun DrawScope.celestial(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.1f
    val r = w * 0.9f
    val top = h * 0.27f
    val bot = h * 0.75f
    val bh = bot - top
    val sw = h * 0.038f
    val cy = (top + bot) / 2
    val outer = roundBody(l, top, r, bot, bh * 0.3f)
    body(outer, roundBody(l + sw * 1.7f, top + sw * 1.7f, r - sw * 1.7f, bot - sw * 1.7f, bh * 0.22f), p, top, bot, sw)
    // The night inside: stars twinkling and a constellation
    clipPath(outer) {
        val rnd = Random(5)
        repeat(46) { i ->
            val s = Offset(l + (r - l) * rnd.nextFloat(), top + bh * rnd.nextFloat())
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (1.5f + rnd.nextFloat() * 3f) + i))
            drawCircle(Color.White.copy(alpha = 0.55f * tw * (if (p.lightFace) 0.5f else 1f)), sw * (0.12f + 0.2f * rnd.nextFloat()), s, blendMode = if (p.lightFace) BlendMode.SrcOver else BlendMode.Plus)
        }
        val pts = listOf(Offset(l + (r - l) * 0.62f, top + bh * 0.25f), Offset(l + (r - l) * 0.7f, top + bh * 0.4f), Offset(l + (r - l) * 0.78f, top + bh * 0.3f), Offset(l + (r - l) * 0.85f, top + bh * 0.55f))
        for (k in 0 until pts.size - 1) drawLine(p.glow.copy(alpha = 0.35f), pts[k], pts[k + 1], strokeWidth = sw * 0.12f)
        pts.forEach { sparkle(it, sw * 0.6f, p.glow, 0.8f) }
    }
    // A crescent moon over the left end
    val mc = Offset(l, cy)
    val mr = bh * 0.5f
    val moon = Path().apply { addOval(Rect(mc, mr)) }
    val bite = Path().apply { addOval(Rect(mc + Offset(mr * 0.45f, -mr * 0.2f), mr * 0.85f)) }
    val crescent = Path().apply { op(moon, bite, PathOperation.Difference) }
    softGlow(mc, mr * 1.8f, p.glow, 0.35f + 0.15f * sin(t * 1.5f))
    ornament(crescent, p, mc.y - mr, mc.y + mr, sw)
    // A star over the right end, slowly turning
    val sc = Offset(r, cy)
    rotate(t * 25f, sc) {
        val star = Path()
        for (i in 0 until 8) {
            val a = (i * 45f - 90f) * PI.toFloat() / 180f
            val rad = if (i % 2 == 0) bh * 0.55f else bh * 0.18f
            val q = sc + Offset(cos(a), sin(a)) * rad
            if (i == 0) star.moveTo(q.x, q.y) else star.lineTo(q.x, q.y)
        }
        star.close()
        softGlow(sc, bh * 0.9f, p.glow, 0.45f)
        ornament(star, p, sc.y - bh * 0.55f, sc.y + bh * 0.55f, sw)
    }
    sparkle(Offset(w / 2, top), bh * 0.22f, p.glow, 0.7f + 0.3f * sin(t * 2.4f))
    sparkle(Offset(w / 2 - bh * 0.6f, top - bh * 0.12f), bh * 0.1f, Color.White, 0.5f + 0.5f * sin(t * 3.1f))
    sparkle(Offset(w / 2 + bh * 0.7f, top - bh * 0.08f), bh * 0.08f, Color.White, 0.5f + 0.5f * sin(t * 2.7f + 1f))
}

// ------------------------------------------------------------------------------------------------ Blade

private fun DrawScope.blade(p: FrameColors, t: Float) {
    val w = size.width
    val h = size.height
    val l = w * 0.16f
    val r = w * 0.84f
    val top = h * 0.3f
    val bot = h * 0.7f
    val bh = bot - top
    val sw = h * 0.035f
    val cy = (top + bot) / 2
    val blade = pointedBody(l, top, r, bot, bh * 0.4f)
    body(blade, null, p, top, bot, sw)
    // The fullers: two engraved lines down the blade
    drawLine(p.metal[2].copy(alpha = 0.7f), Offset(l + bh * 0.55f, top + sw * 1.6f), Offset(r - bh * 0.55f, top + sw * 1.6f), strokeWidth = sw * 0.3f)
    drawLine(p.metal[2].copy(alpha = 0.7f), Offset(l + bh * 0.55f, bot - sw * 1.6f), Offset(r - bh * 0.55f, bot - sw * 1.6f), strokeWidth = sw * 0.3f)
    // A hilt at each end: guard, wrapped grip and pommel
    for (side in listOf(-1f, 1f)) {
        val ex = if (side < 0) l else r
        val guard = Path().apply {
            addRoundRect(RoundRect(Rect(ex - sw * 0.9f, cy - bh * 0.78f, ex + sw * 0.9f, cy + bh * 0.78f), CornerRadius(sw)))
        }
        ornament(guard, p, cy - bh, cy + bh, sw)
        drawCircle(p.metal[0], sw * 1.1f, Offset(ex, cy - bh * 0.78f))
        drawCircle(p.metal[0], sw * 1.1f, Offset(ex, cy + bh * 0.78f))
        val gl = bh * 0.55f
        val g0 = ex + side * sw * 0.9f
        val grip = Path().apply {
            addRect(Rect(minOf(g0, g0 + side * gl), cy - bh * 0.13f, maxOf(g0, g0 + side * gl), cy + bh * 0.13f))
        }
        drawPath(grip, Brush.verticalGradient(listOf(lerp(p.face[0], Color.White, 0.15f), p.face[1]), cy - bh * 0.13f, cy + bh * 0.13f))
        for (k in 1..5) {
            val gx = g0 + side * gl * k / 6f
            drawLine(p.metal[1], Offset(gx - side * sw * 0.4f, cy - bh * 0.13f), Offset(gx + side * sw * 0.4f, cy + bh * 0.13f), strokeWidth = sw * 0.35f)
        }
        val pom = Offset(g0 + side * (gl + bh * 0.12f), cy)
        drawCircle(Color.Black.copy(alpha = 0.5f), bh * 0.16f, pom)
        drawCircle(metalBrush(p, pom.y - bh * 0.15f, pom.y + bh * 0.15f), bh * 0.14f, pom)
        gem(pom, bh * 0.07f, p, t, side)
    }
    // Light slides along the edge of the blade
    edgeRunner(blade, Color.White, t, 0.15f, sw)
    gem(Offset(w / 2, top), bh * 0.08f, p, t, 2.5f)
}
