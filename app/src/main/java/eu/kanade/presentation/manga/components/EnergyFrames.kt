package eu.kanade.presentation.manga.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import eu.kanade.presentation.components.CoverPalette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * MANHWA: the hundred frames in the work's own colours. Ten families (a thorn crown, iron spikes, a winged crest,
 * antlers, tribal hooks, a flame crest, the hunter's system window, arcane seals, storm blades and a crown of stars),
 * ten of each, every one with its own plate shape, metal (gold, silver, iron or the cover's own), ornaments, and one
 * of ten motions laid over its family's own. Every colour comes from the cover: its strongest colour lights the
 * frame, its second colour the accents, its deep colour the face, and the metal is tinted by it.
 *
 * The still part of a frame is drawn once for its size and colours and kept (a list of fills and strokes replayed
 * each frame); only what moves is worked out again. Ported line for line from tools/frame_proto/energy.html.
 */
enum class EnergyFamily(val left: Float, val right: Float, val top: Float, val bottom: Float) {
    THORN(0.14f, 0.86f, 0.3f, 0.72f),
    SPIKE(0.1f, 0.9f, 0.3f, 0.7f),
    CREST(0.26f, 0.9f, 0.32f, 0.68f),
    ANTLER(0.14f, 0.86f, 0.36f, 0.76f),
    TRIBAL(0.14f, 0.86f, 0.3f, 0.7f),
    FLAMECREST(0.16f, 0.88f, 0.3f, 0.7f),
    SYSTEM(0.08f, 0.92f, 0.26f, 0.78f),
    ARCANE(0.2f, 0.8f, 0.32f, 0.68f),
    STORMBLADE(0.16f, 0.84f, 0.3f, 0.7f),
    CELESTIAL(0.15f, 0.85f, 0.38f, 0.76f),
}

/** One of the hundred: its family and how this one differs from the other nine of it. */
data class EnergyFrame(val family: EnergyFamily, val variant: Int) {
    val key: String get() = "E_${family.name}_$variant"
    val index: Int get() = family.ordinal * 10 + variant
    internal val motion: Int get() = (variant * 3 + family.ordinal) % 10
    internal val body: Int get() = (variant + family.ordinal * 2) % 5
    internal val metal: Int get() = (variant + family.ordinal) % 4
    internal val mirror: Boolean get() = variant % 3 == 1
    internal val dense: Int get() = variant % 3
    internal val curl: Float get() = (variant * 7 % 10) / 10f
    internal val flip: Boolean get() = variant % 2 == 1
    internal val seed: Int get() = index * 31 + 7

    companion object {
        val all: List<EnergyFrame> = EnergyFamily.entries.flatMap { f -> (0 until 10).map { EnergyFrame(f, it) } }
    }
}

/** The colours a frame is drawn in, all from the cover. */
class EnergyColors(val p: Color, val s: Color, val d: Color, metalKind: Int) {
    val metal: List<Color>
    val lac: Color
    val hi: Color = lerp(p, Color.White, 0.6f)

    init {
        val base = when (metalKind) {
            0 -> lerp(Color(0xFFD6AC54), p, 0.22f)
            1 -> lerp(Color(0xFFC4CCDA), p, 0.22f)
            2 -> lerp(Color(0xFF5C5460), p, 0.18f)
            else -> lerp(p, Color.White, 0.12f)
        }
        metal = listOf(lerp(base, Color.White, 0.85f), lerp(base, Color.White, 0.35f), base, lerp(base, Color.Black, 0.6f), lerp(base, Color.White, 0.45f))
        lac = lerp(lerp(Color(0xFF221C2C), d, 0.4f), p, 0.12f)
    }

    companion object {
        /** The cover's palette, or one made from [accent] when the cover is not read yet. */
        fun of(cover: CoverPalette?, accent: Color, metalKind: Int): EnergyColors =
            if (cover != null) {
                EnergyColors(cover.primary, cover.secondary, cover.deep, metalKind)
            } else {
                EnergyColors(accent, lerp(accent, Color.White, 0.35f), lerp(accent, Color.Black, 0.86f), metalKind)
            }
    }
}

// ------------------------------------------------------------------------------------------------ Recording

/** A still part, recorded once: fills, strokes and clipped groups, replayed every frame. */
private sealed interface EnergyOp {
    class Draw(val path: Path, val brush: Brush, val width: Float, val plus: Boolean) : EnergyOp
    class Clip(val path: Path, val ops: List<EnergyOp>) : EnergyOp
}

/** Where the moving part lands: the plate, its gems, the tips of its ornaments, and what each family keeps. */
private class EnergyAnchors {
    var body: Path = Path()
    var box: Rect = Rect.Zero
    val gems = ArrayList<FloatArray>()
    val pts = ArrayList<Offset>()
    var tab: FloatArray? = null
    var ends: FloatArray? = null
    var cy = 0f
    var radius = 0f
    var rail: FloatArray? = null
}

private class EnergyBuilt(val behind: Int, val ops: List<EnergyOp>, val a: EnergyAnchors)

private class EnergyRec {
    val ops = ArrayList<EnergyOp>()
    fun fill(path: Path, brush: Brush, plus: Boolean = false) {
        ops += EnergyOp.Draw(path, brush, 0f, plus)
    }
    fun fill(path: Path, color: Color, plus: Boolean = false) = fill(path, SolidColor(color), plus)
    fun stroke(path: Path, brush: Brush, width: Float, plus: Boolean = false) {
        ops += EnergyOp.Draw(path, brush, width, plus)
    }
    fun stroke(path: Path, color: Color, width: Float, plus: Boolean = false) = stroke(path, SolidColor(color), width, plus)
    fun clip(path: Path, block: EnergyRec.() -> Unit) {
        ops += EnergyOp.Clip(path, EnergyRec().apply(block).ops)
    }
    fun glow(x: Float, y: Float, r: Float, c: Color, a: Float) {
        if (r <= 0.5f || a <= 0.01f) return
        fill(circle(x, y, r), rad(x, y, r, 0f to c.a(a), 1f to c.a(0f)), true)
    }
}

private fun DrawScope.replay(ops: List<EnergyOp>) {
    for (op in ops) {
        when (op) {
            is EnergyOp.Draw -> drawPath(
                op.path,
                op.brush,
                style = if (op.width > 0f) Stroke(op.width, cap = StrokeCap.Round, join = StrokeJoin.Round) else Fill,
                blendMode = if (op.plus) BlendMode.Plus else BlendMode.SrcOver,
            )
            is EnergyOp.Clip -> clipPath(op.path) { replay(op.ops) }
        }
    }
}

/** The still parts of the frames on screen, kept per frame, size and colours. */
private object EnergyCache {
    private val map = object : LinkedHashMap<String, EnergyBuilt>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EnergyBuilt>?) = size > 48
    }

    @Synchronized
    fun get(key: String, make: () -> EnergyBuilt): EnergyBuilt = map[key] ?: make().also { map[key] = it }
}

/** Draws [frame] over the whole of this scope in [colors]; [t] is the time in seconds, for its motion. */
fun DrawScope.drawEnergyFrame(frame: EnergyFrame, colors: EnergyColors, t: Float) {
    val w = size.width
    val h = size.height
    if (w < 2f || h < 2f) return
    val built = EnergyCache.get("${frame.key}|${w.toInt()}|${h.toInt()}|${colors.p.value}|${colors.s.value}|${colors.d.value}") {
        build(frame, colors, w, h)
    }
    behind(built.behind, w, h, t, colors)
    replay(built.ops)
    val a = built.a
    a.gems.forEachIndexed { j, g -> gem(g[0], g[1], g[2], colors, t, j * 1.3f) }
    when (frame.family) {
        EnergyFamily.SYSTEM -> systemLive(w, h, t, colors, a)
        EnergyFamily.ARCANE -> arcaneLive(t, colors, a)
        else -> Unit
    }
    motion(frame.motion, w, h, t, colors, a)
}

private fun build(frame: EnergyFrame, k: EnergyColors, w: Float, h: Float): EnergyBuilt {
    val rec = EnergyRec()
    val a = when (frame.family) {
        EnergyFamily.THORN -> rec.thorn(w, h, k, frame)
        EnergyFamily.SPIKE -> rec.spike(w, h, k, frame)
        EnergyFamily.CREST -> rec.crest(w, h, k, frame)
        EnergyFamily.ANTLER -> rec.antler(w, h, k, frame)
        EnergyFamily.TRIBAL -> rec.tribal(w, h, k, frame)
        EnergyFamily.FLAMECREST -> rec.flameCrest(w, h, k, frame)
        EnergyFamily.SYSTEM -> rec.system(w, h, k)
        EnergyFamily.ARCANE -> rec.arcane(w, h, k, frame)
        EnergyFamily.STORMBLADE -> rec.stormBlade(w, h, k, frame)
        EnergyFamily.CELESTIAL -> rec.celestial(w, h, k, frame)
    }
    if (a.box == Rect.Zero) {
        val f = frame.family
        a.box = Rect(w * f.left - w * 0.02f, h * f.top - h * 0.02f, w * f.right + w * 0.02f, h * f.bottom + h * 0.02f)
    }
    return EnergyBuilt(frame.motion, rec.ops, a)
}

// ------------------------------------------------------------------------------------------------ Parts

private const val PIF = 3.1415927f

private fun Color.a(alpha: Float) = copy(alpha = alpha.coerceIn(0f, 1f))
private fun fract(x: Float) = x - floor(x)

private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg stops: Pair<Float, Color>) =
    Brush.linearGradient(*stops, start = Offset(x0, y0), end = Offset(x1, y1))

private fun rad(cx: Float, cy: Float, r: Float, vararg stops: Pair<Float, Color>) =
    Brush.radialGradient(*stops, center = Offset(cx, cy), radius = max(r, 0.01f))

private fun circle(x: Float, y: Float, r: Float) = Path().apply { addOval(Rect(x - r, y - r, x + r, y + r)) }

private fun metalBrush(k: EnergyColors, top: Float, bottom: Float) =
    lin(0f, top, 0f, bottom, 0f to k.metal[0], 0.3f to k.metal[1], 0.55f to k.metal[2], 0.8f to k.metal[3], 1f to k.metal[4])

private fun lacBrush(k: EnergyColors, top: Float, bottom: Float) =
    lin(0f, top, 0f, bottom, 0f to lerp(k.lac, k.p, 0.35f), 0.5f to k.lac, 1f to lerp(k.lac, Color.Black, 0.5f))

private fun roundBody(l: Float, t: Float, r: Float, b: Float, rr: Float) =
    Path().apply { addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(rr))) }

private fun chamferBody(l: Float, t: Float, r: Float, b: Float, c: Float) = Path().apply {
    moveTo(l + c, t); lineTo(r - c, t); lineTo(r, t + c); lineTo(r, b - c); lineTo(r - c, b); lineTo(l + c, b); lineTo(l, b - c); lineTo(l, t + c); close()
}

private fun pointedBody(l: Float, t: Float, r: Float, b: Float, pt: Float) = Path().apply {
    val cy = (t + b) / 2
    moveTo(l + pt, t); lineTo(r - pt, t); lineTo(r, cy); lineTo(r - pt, b); lineTo(l + pt, b); lineTo(l, cy); close()
}

private fun notchBody(l: Float, t: Float, r: Float, b: Float, c: Float) = Path().apply {
    val cy = (t + b) / 2
    moveTo(l, t); lineTo(r, t); lineTo(r, cy - c); lineTo(r - c, cy); lineTo(r, cy + c); lineTo(r, b); lineTo(l, b); lineTo(l, cy + c); lineTo(l + c, cy); lineTo(l, cy - c); close()
}

private fun shieldBody(l: Float, t: Float, r: Float, b: Float, c: Float) = Path().apply {
    moveTo(l, t + c); quadraticTo(l, t, l + c, t); lineTo(r - c, t); quadraticTo(r, t, r, t + c); lineTo(r, b - c); lineTo(r - c * 1.6f, b); lineTo(l + c * 1.6f, b); lineTo(l, b - c); close()
}

private fun bodyOf(kind: Int, l: Float, t: Float, r: Float, b: Float): Path {
    val h = b - t
    return when (kind) {
        0 -> roundBody(l, t, r, b, h * 0.18f)
        1 -> chamferBody(l, t, r, b, h * 0.22f)
        2 -> pointedBody(l, t, r, b, h * 0.42f)
        3 -> notchBody(l, t, r, b, h * 0.18f)
        else -> shieldBody(l, t, r, b, h * 0.2f)
    }
}

/** A tapered, curved blade from (x, y) along angle [a]: the leaf, thorn, flame tongue and feather of every frame. */
private fun blade(x: Float, y: Float, a: Float, len: Float, wd: Float, curve: Float): Path {
    val dx = cos(a)
    val dy = sin(a)
    val px = -dy
    val py = dx
    val tx = x + dx * len + px * curve * len * 0.35f
    val ty = y + dy * len + py * curve * len * 0.35f
    return Path().apply {
        moveTo(x + px * wd * 0.5f, y + py * wd * 0.5f)
        quadraticTo(x + dx * len * 0.5f + px * (curve * len * 0.42f + wd * 0.55f), y + dy * len * 0.5f + py * (curve * len * 0.42f + wd * 0.55f), tx, ty)
        quadraticTo(x + dx * len * 0.55f + px * (curve * len * 0.3f - wd * 0.25f), y + dy * len * 0.55f + py * (curve * len * 0.3f - wd * 0.25f), x - px * wd * 0.5f, y - py * wd * 0.5f)
        close()
    }
}

private fun diamond(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply {
    moveTo(cx, cy - ry); lineTo(cx + rx, cy); lineTo(cx, cy + ry); lineTo(cx - rx, cy); close()
}

private fun star(cx: Float, cy: Float, n: Int, ro: Float, ri: Float, rot: Float) = Path().apply {
    for (i in 0 until n * 2) {
        val a = rot + i * PIF / n
        val r = if (i % 2 == 1) ri else ro
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun spark(x: Float, y: Float, s: Float) = Path().apply {
    moveTo(x, y - s); quadraticTo(x, y, x + s, y); quadraticTo(x, y, x, y + s); quadraticTo(x, y, x - s, y); quadraticTo(x, y, x, y - s); close()
}

private fun EnergyRec.face(path: Path, k: EnergyColors, l: Float, t: Float, r: Float, b: Float, mirror: Boolean) {
    if (mirror) {
        fill(path, lin(l, 0f, r, 0f, 0f to lerp(k.d, Color.Black, 0.4f), 0.5f to lerp(k.d, k.p, 0.45f), 1f to lerp(k.d, Color.Black, 0.4f)))
    } else {
        fill(path, lin(0f, t, 0f, b, 0f to lerp(k.d, k.p, 0.3f), 1f to lerp(k.d, Color.Black, 0.55f)))
    }
    fill(path, rad((l + r) / 2, (t + b) / 2, (r - l) * 0.42f, 0f to lerp(k.p, Color.White, 0.3f).a(0.28f), 1f to k.p.a(0f)), true)
    fill(path, lin(0f, t, 0f, (t + b) / 2, 0f to Color.White.a(0.16f), 1f to Color.White.a(0f)))
}

private fun EnergyRec.rim(path: Path, k: EnergyColors, t: Float, b: Float, sw: Float) {
    stroke(path, Color.Black.a(0.6f), sw * 2)
    stroke(path, metalBrush(k, t - sw, b + sw), sw)
    stroke(path, k.metal[0].a(0.8f), sw * 0.22f)
}

private fun EnergyRec.ornament(path: Path, k: EnergyColors, t: Float, b: Float, sw: Float, lacquer: Boolean) {
    stroke(path, Color.Black.a(0.6f), sw * 0.7f)
    fill(path, if (lacquer) lacBrush(k, t, b) else metalBrush(k, t, b))
    if (lacquer) stroke(path, metalBrush(k, t, b), sw * 0.3f) else stroke(path, k.metal[0].a(0.55f), sw * 0.14f)
}

private fun DrawScope.glow(x: Float, y: Float, r: Float, c: Color, a: Float) {
    if (r <= 0.5f || a <= 0.01f) return
    drawCircle(rad(x, y, r, 0f to c.a(a), 1f to c.a(0f)), r, Offset(x, y), blendMode = BlendMode.Plus)
}

/** A cut gem that breathes light (drawn live: it pulses). */
private fun DrawScope.gem(cx: Float, cy: Float, r: Float, k: EnergyColors, t: Float, ph: Float) {
    val pulse = 0.6f + 0.4f * sin(t * 2.6f + ph)
    glow(cx, cy, r * 3.4f, k.p, 0.55f * pulse)
    val d = diamond(cx, cy, r, r * 1.3f)
    drawPath(d, Color.Black.a(0.5f), style = Stroke(r * 0.35f))
    drawPath(d, metalBrush(k, cy - r * 1.5f, cy + r * 1.5f), style = Stroke(r * 0.22f))
    drawPath(d, rad(cx - r * 0.3f, cy - r * 0.4f, r * 1.5f, 0f to Color.White, 0.25f to lerp(k.p, Color.White, 0.4f), 0.7f to k.p, 1f to lerp(k.p, Color.Black, 0.5f)))
    drawPath(spark(cx - r * 0.3f, cy - r * 0.45f, r * (0.5f + 0.5f * pulse)), Color.White.a(pulse), blendMode = BlendMode.Plus)
}

// ================================================================================================ Families

private fun EnergyRec.thorn(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val r0 = Random(v.seed)
    val l = w * 0.12f
    val r = w * 0.88f
    val t = h * 0.3f
    val b = h * 0.72f
    val cy = (t + b) / 2
    val bh = b - t
    val sw = h * 0.028f
    val a = EnergyAnchors()
    for (side in intArrayOf(-1, 1)) {
        val ex = if (side < 0) l else r
        val n = 5 + v.dense
        for (pass in 0 until 2) for (i in 0 until n) {
            val f = i / (n - 1f) - 0.5f
            val ang = (if (side < 0) PIF else 0f) + f * 2.3f * side * (if (pass == 1) 0.85f else 1f)
            val len = bh * (1.0f - abs(f) * 0.7f) * (if (pass == 1) 0.8f else 1f) * (0.9f + 0.2f * r0.nextFloat())
            val p = blade(ex + side * bh * 0.05f, cy + f * bh * 0.6f, ang, len, bh * (if (pass == 1) 0.2f else 0.27f), (if (f > 0) 1 else -1) * side * (0.6f + v.curl * 0.4f))
            ornament(p, k, t - bh, b + bh, sw, pass == 0)
            if (pass == 1) a.pts += Offset(ex + cos(ang) * len, cy + f * bh * 0.6f + sin(ang) * len)
        }
    }
    val body = bodyOf(v.body, l, t, r, b)
    face(body, k, l, t, r, b, v.mirror)
    rim(body, k, t, b, sw)
    stroke(bodyOf(v.body, l + sw * 2.2f, t + sw * 2.2f, r - sw * 2.2f, b - sw * 2.2f), k.p.a(0.6f), sw * 0.35f, true)
    for (dir in intArrayOf(-1, 1)) for (j in 0 until 3) {
        val x = w / 2 + dir * bh * (0.25f + j * 0.42f)
        val p = blade(x, t + sw * 0.3f, if (dir < 0) PIF + 0.16f + j * 0.08f else -0.16f - j * 0.08f, bh * (0.9f - j * 0.18f), bh * 0.13f, -dir * 0.9f)
        ornament(p, k, t - bh, t, sw, j % 2 == 0)
    }
    val sp = Path().apply { moveTo(w / 2 - bh * 0.12f, t); lineTo(w / 2, t - bh * 0.62f); lineTo(w / 2 + bh * 0.12f, t); close() }
    ornament(sp, k, t - bh, t, sw, false)
    a.gems += floatArrayOf(w / 2, t, bh * 0.13f)
    a.gems += floatArrayOf(w / 2, b, bh * 0.1f)
    ornament(diamond(w / 2, b + bh * 0.06f, bh * 0.2f, bh * 0.3f), k, b, b + bh, sw, false)
    a.body = body
    return a
}

private fun EnergyRec.spike(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val r0 = Random(v.seed * 3 + 1)
    val l = w * 0.08f
    val r = w * 0.92f
    val t = h * 0.3f
    val b = h * 0.7f
    val bh = b - t
    val sw = h * 0.024f
    val a = EnergyAnchors()
    val step = bh * (0.14f + 0.05f * v.dense)
    fun edge(x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float) {
        val n = (hypot(x1 - x0, y1 - y0) / step).toInt()
        for (i in 1 until n) {
            val f = i.toFloat() / n
            val x = x0 + (x1 - x0) * f
            val y = y0 + (y1 - y0) * f
            val len = bh * (0.08f + 0.22f * r0.nextFloat().pow(2))
            val ang = atan2(ny, nx) + (r0.nextFloat() - 0.5f) * 0.9f
            ornament(blade(x, y, ang, len, step * 0.55f, (r0.nextFloat() - 0.5f) * 1.2f), k, t - bh, b + bh, sw, r0.nextFloat() < 0.5f)
            if (len > bh * 0.2f) a.pts += Offset(x + cos(ang) * len, y + sin(ang) * len)
        }
    }
    edge(l, t, r, t, 0f, -1f)
    edge(l, b, r, b, 0f, 1f)
    edge(l, t, l, b, -1f, 0f)
    edge(r, t, r, b, 1f, 0f)
    for (c in listOf(floatArrayOf(l, t, -1f, -1f), floatArrayOf(r, t, 1f, -1f), floatArrayOf(l, b, -1f, 1f), floatArrayOf(r, b, 1f, 1f))) {
        val base = atan2(c[3], c[2])
        for (i in 0 until 5) {
            ornament(blade(c[0], c[1], base + (i - 2) * 0.32f, bh * (0.35f + 0.25f * r0.nextFloat()), bh * 0.12f, (r0.nextFloat() - 0.5f) * 1.6f), k, t - bh, b + bh, sw, i % 2 == 1)
        }
    }
    val body = bodyOf(v.body, l, t, r, b)
    face(body, k, l, t, r, b, v.mirror)
    clip(body) {
        repeat(14) {
            val x = l + (r - l) * r0.nextFloat()
            val y = t + bh * r0.nextFloat()
            stroke(Path().apply { moveTo(x, y); lineTo(x + bh * (0.8f + r0.nextFloat()), y + bh * (0.2f + 0.3f * r0.nextFloat())) }, Color.White.a(0.05f), 1.2f)
        }
        val tx = l + (r - l) * (if (v.flip) 0.15f else 0.82f)
        val tp = Path().apply { moveTo(tx, t); lineTo(tx + bh * 0.5f, t); lineTo(tx + bh * 0.3f, t + bh * 0.3f); lineTo(tx + bh * 0.45f, t + bh * 0.45f); lineTo(tx - bh * 0.05f, t + bh * 0.2f); close() }
        fill(tp, lin(tx, t, tx, t + bh * 0.45f, 0f to lerp(k.metal[0], k.p, 0.2f).a(0.9f), 1f to k.metal[2].a(0.5f)))
    }
    rim(body, k, t, b, sw)
    a.gems += floatArrayOf(w / 2, b, bh * 0.09f)
    a.body = body
    return a
}

private fun EnergyRec.crest(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val l = w * 0.2f
    val r = w * 0.92f
    val t = h * 0.32f
    val b = h * 0.68f
    val bh = b - t
    val cy = (t + b) / 2
    val sw = h * 0.022f
    val a = EnergyAnchors()
    val body = roundBody(l, t, r, b, bh * 0.5f)
    face(body, k, l, t, r, b, v.mirror)
    rim(body, k, t, b, sw)
    for (y in floatArrayOf(t + sw * 1.4f, b - sw * 1.4f)) stroke(Path().apply { moveTo(l + bh, y); lineTo(r - bh * 0.7f, y) }, k.hi.a(0.55f), sw * 0.35f, true)
    for (i in 0 until 3) {
        val ang = (i - 1) * 0.42f
        ornament(blade(r - bh * 0.25f, cy, ang, bh * (0.95f - abs(i - 1) * 0.25f), bh * 0.28f, (i - 1) * 0.4f), k, t - bh, b + bh, sw, i != 1)
        a.pts += Offset(r - bh * 0.25f + cos(ang) * bh * 0.9f, cy + sin(ang) * bh * 0.9f)
    }
    val cx = l + bh * 0.05f
    val s = bh * 1.15f
    for (side in intArrayOf(-1, 1)) for (i in 0 until 4 + v.dense) {
        val ang = (if (side < 0) PIF else 0f) + side * (-0.25f - i * 0.24f)
        val len = s * (1.5f - i * 0.17f)
        val x = cx + side * s * 0.18f
        val y = cy - s * 0.1f + i * s * 0.05f
        ornament(blade(x, y, ang, len, s * 0.26f, -side * 0.35f), k, cy - s * 1.5f, cy + s, sw, i % 2 == 1)
        if (i == 0) a.pts += Offset(x + cos(ang) * len, y + sin(ang) * len)
    }
    for (side in intArrayOf(-1, 1)) ornament(blade(cx + side * s * 0.18f, cy - s * 0.42f, -PIF / 2 + side * 0.5f, s * 0.85f, s * 0.2f, side * 0.9f), k, cy - s * 1.4f, cy, sw, false)
    val sh = Path().apply { moveTo(cx - s * 0.5f, cy - s * 0.55f); lineTo(cx + s * 0.5f, cy - s * 0.55f); lineTo(cx + s * 0.45f, cy + s * 0.1f); lineTo(cx, cy + s * 0.75f); lineTo(cx - s * 0.45f, cy + s * 0.1f); close() }
    ornament(sh, k, cy - s * 0.6f, cy + s * 0.8f, sw * 1.2f, false)
    val si = Path().apply { moveTo(cx - s * 0.33f, cy - s * 0.4f); lineTo(cx + s * 0.33f, cy - s * 0.4f); lineTo(cx + s * 0.3f, cy + s * 0.05f); lineTo(cx, cy + s * 0.55f); lineTo(cx - s * 0.3f, cy + s * 0.05f); close() }
    fill(si, lin(0f, cy - s * 0.4f, 0f, cy + s * 0.55f, 0f to lerp(k.d, k.p, 0.5f), 1f to lerp(k.d, Color.Black, 0.4f)))
    stroke(si, k.hi.a(0.8f), sw * 0.3f, true)
    a.gems += floatArrayOf(cx, cy - s * 0.05f, s * 0.2f)
    a.body = body
    return a
}

private fun EnergyRec.antler(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val r0 = Random(v.seed * 5 + 2)
    val l = w * 0.12f
    val r = w * 0.88f
    val t = h * 0.36f
    val b = h * 0.76f
    val bh = b - t
    val sw = h * 0.024f
    val a = EnergyAnchors()
    fun tine(x: Float, y: Float, ang: Float, len: Float, wd: Float, depth: Int, side: Int) {
        ornament(blade(x, y, ang, len, wd, side * 0.35f), k, t - bh * 2, t + bh, sw, true)
        val ex = x + cos(ang) * len * 0.98f
        val ey = y + sin(ang) * len * 0.98f
        if (depth == 0) {
            a.pts += Offset(ex, ey)
            return
        }
        val n = 2 + (r0.nextFloat() * 2).toInt()
        for (i in 1..n) {
            val f = i / (n + 1f)
            tine(x + cos(ang) * len * f, y + sin(ang) * len * f, ang - side * (0.5f + r0.nextFloat() * 0.4f), len * (0.45f + r0.nextFloat() * 0.15f), wd * 0.6f, depth - 1, side)
        }
        fill(diamond(ex, ey, wd * 0.25f, wd * 0.25f), k.metal[1])
    }
    val kind = if (v.body == 2) 1 else v.body
    for (side in intArrayOf(-1, 1)) {
        val x = if (side < 0) l + bh * 0.35f else r - bh * 0.35f
        tine(x, t, -PIF / 2 - side * (0.55f + 0.1f * v.curl), bh * 1.15f, bh * 0.38f, 1 + (if (v.dense > 1) 1 else 0), side)
        val sx = if (side < 0) l + bh * 0.1f else r - bh * 0.1f
        ornament(star(sx, b + bh * 0.05f, 4, bh * 0.42f, bh * 0.12f, PIF / 4), k, b - bh * 0.4f, b + bh * 0.5f, sw, false)
        ornament(star(sx, b + bh * 0.05f, 4, bh * 0.28f, bh * 0.08f, 0f), k, b - bh * 0.3f, b + bh * 0.4f, sw, false)
        a.gems += floatArrayOf(sx, b + bh * 0.05f, bh * 0.07f)
    }
    val band = chamferBody(l + bh * 0.9f, t - bh * 0.22f, r - bh * 0.9f, t + bh * 0.1f, bh * 0.12f)
    fill(band, lacBrush(k, t - bh * 0.22f, t + bh * 0.1f))
    stroke(band, metalBrush(k, t - bh * 0.3f, t + bh * 0.2f), sw * 0.6f)
    val body = bodyOf(kind, l, t, r, b)
    face(body, k, l, t, r, b, true)
    rim(body, k, t, b, sw)
    stroke(bodyOf(kind, l + sw * 2.4f, t + sw * 2.4f, r - sw * 2.4f, b - sw * 2.4f), metalBrush(k, t, b), sw * 0.3f)
    for (c in listOf(floatArrayOf(l + sw * 3, t + sw * 3, 1f, 1f), floatArrayOf(r - sw * 3, t + sw * 3, -1f, 1f), floatArrayOf(l + sw * 3, b - sw * 3, 1f, -1f), floatArrayOf(r - sw * 3, b - sw * 3, -1f, -1f))) {
        val (x, y, sx, sy) = c
        val p = Path().apply {
            moveTo(x, y + sy * bh * 0.3f); lineTo(x, y); lineTo(x + sx * bh * 0.3f, y)
            moveTo(x + sx * bh * 0.08f, y + sy * bh * 0.16f); lineTo(x + sx * bh * 0.16f, y + sy * bh * 0.08f)
        }
        stroke(p, k.metal[1].a(0.85f), sw * 0.3f)
    }
    ornament(diamond(w / 2, t - bh * 0.06f, bh * 0.14f, bh * 0.12f), k, t - bh * 0.3f, t + bh * 0.1f, sw, false)
    a.gems += floatArrayOf(w / 2, b + sw * 0.5f, bh * 0.08f)
    a.body = body
    return a
}

private fun EnergyRec.tribal(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val l = w * 0.12f
    val r = w * 0.88f
    val t = h * 0.3f
    val b = h * 0.7f
    val bh = b - t
    val cy = (t + b) / 2
    val sw = h * 0.024f
    val a = EnergyAnchors()
    fun hook(x: Float, y: Float, ang: Float, len: Float, wd: Float, dir: Float, lac: Boolean) {
        ornament(blade(x, y, ang, len * 1.15f, wd * 1.45f, dir * (1.2f + 0.3f * v.curl)), k, t - bh, b + bh, sw, lac)
        a.pts += Offset(x + cos(ang) * len, y + sin(ang) * len)
    }
    for (sy in intArrayOf(-1, 1)) {
        val y = if (sy < 0) t else b
        val sc = if (sy < 0) 1f else 0.75f
        for (sx in intArrayOf(-1, 1)) for (j in 0 until 2 + v.dense) {
            val x = w / 2 + sx * bh * (0.45f + j * 0.55f)
            hook(x, y, sy * PIF / 2 + sx * (0.7f + j * 0.15f) * sy, bh * (0.55f - j * 0.06f) * sc, bh * 0.17f * sc, (sx * sy).toFloat(), j % 2 == 0)
        }
        ornament(diamond(w / 2, y + sy * bh * 0.12f, bh * 0.13f, bh * 0.28f * sc), k, y - bh * 0.4f, y + bh * 0.4f, sw, false)
        a.gems += floatArrayOf(w / 2, y + sy * bh * 0.12f, bh * 0.08f * sc)
        ornament(blade(w / 2, y, sy * PIF / 2, bh * 0.5f * sc, bh * 0.08f, 0f), k, y - bh, y + bh, sw, false)
    }
    for (sx in intArrayOf(-1, 1)) {
        val x = if (sx < 0) l else r
        for (j in 0 until 3) {
            val yy = cy + (j - 1) * bh * 0.38f
            hook(x, yy, (if (sx < 0) PIF else 0f) + (j - 1) * 0.5f * sx, bh * (0.6f - abs(j - 1) * 0.12f), bh * 0.2f, (if (j - 1 != 0) (j - 1) else 1) * sx.toFloat(), j == 1)
        }
        for (sy in intArrayOf(-1, 1)) hook(x, if (sy < 0) t else b, atan2(sy.toFloat(), sx.toFloat()), bh * 0.75f, bh * 0.22f, (sx * sy).toFloat(), true)
    }
    val kind = if (v.body == 0) 1 else v.body
    val body = bodyOf(kind, l, t, r, b)
    face(body, k, l, t, r, b, v.mirror)
    rim(body, k, t, b, sw)
    stroke(bodyOf(kind, l + sw * 1.8f, t + sw * 1.8f, r - sw * 1.8f, b - sw * 1.8f), k.p.a(0.85f), sw * 0.45f, true)
    a.body = body
    return a
}

private fun EnergyRec.flameCrest(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val l = w * 0.08f
    val r = w * 0.92f
    val t = h * 0.3f
    val b = h * 0.7f
    val bh = b - t
    val sw = h * 0.022f
    val a = EnergyAnchors()
    val fl = if (v.flip) -1 else 1
    fun xx(x: Float) = if (fl > 0) x else w - x
    val body = chamferBody(l, t, r, b, bh * 0.12f)
    face(body, k, l, t, r, b, v.mirror)
    clip(body) { for (i in 0 until 5) glow(xx(l + bh * (0.4f + i * 0.9f)), b - bh * 0.25f + sin(i.toFloat()) * bh * 0.1f, bh * 0.55f, k.p, 0.22f) }
    rim(body, k, t, b, sw)
    // A swirl of dark flames round one corner, each with a burning core, curling the same way
    val fx = xx(l + bh * 0.35f)
    val fy = b - bh * 0.1f
    val n = 7 + v.dense
    for (i in 0 until n) {
        val f = i / (n - 1f)
        val around = if (fl > 0) PIF * 0.95f - f * 1.55f else PIF * 0.05f + f * 1.55f
        val ang = -abs(if (fl > 0) around - 0.25f else around + 0.25f)
        val x = fx + cos(around) * bh * 0.18f
        val y = fy + sin(around) * bh * 0.1f
        val len = bh * (0.85f + 0.75f * sin(f * PIF)) * (if (i % 2 == 1) 0.78f else 1f)
        val cv = fl * 1.1f
        ornament(blade(x, y, ang, len, bh * 0.3f, cv), k, b - bh * 1.8f, b + bh * 0.3f, sw, true)
        fill(
            blade(x, y, ang, len * 0.7f, bh * 0.12f, cv),
            lin(x, y, x + cos(ang) * len, y + sin(ang) * len, 0f to lerp(k.p, Color.White, 0.5f).a(0.95f), 0.6f to k.p.a(0.6f), 1f to k.p.a(0f)),
            true,
        )
        a.pts += Offset(x + cos(ang) * len, y + sin(ang) * len)
    }
    for (i in 0 until 3) ornament(blade(xx(r - bh * 0.1f), t + bh * 0.1f, if (fl > 0) -0.5f - i * 0.45f else PIF + 0.5f + i * 0.45f, bh * (0.5f - i * 0.1f), bh * 0.1f, -fl * 0.9f), k, t - bh, t + bh, sw, true)
    val drip = Path().apply { moveTo(xx(r - bh * 0.6f), b); lineTo(xx(r - bh * 0.52f), b + bh * 0.9f); lineTo(xx(r - bh * 0.44f), b); close() }
    ornament(drip, k, b, b + bh, sw, true)
    a.pts += Offset(xx(r - bh * 0.52f), b + bh * 0.9f)
    val cx = w / 2
    val spire = Path().apply { moveTo(cx - bh * 0.1f, t); lineTo(cx, t - bh * 0.95f); lineTo(cx + bh * 0.1f, t); close() }
    ornament(spire, k, t - bh, t, sw, true)
    for (s in intArrayOf(-1, 1)) for (j in 0 until 2) {
        ornament(blade(cx + s * bh * 0.05f, t - bh * (0.25f + j * 0.22f), -PIF / 2 + s * (1.1f - j * 0.2f), bh * (0.42f - j * 0.1f), bh * 0.12f, -s * 0.9f), k, t - bh, t, sw, true)
    }
    a.gems += floatArrayOf(cx, t - bh * 0.32f, bh * 0.12f)
    for (g in listOf(Offset(l + bh * 0.25f, t + bh * 0.22f), Offset(r - bh * 0.25f, t + bh * 0.22f), Offset(r - bh * 0.25f, b - bh * 0.22f), Offset(l + bh * 0.25f, b - bh * 0.22f))) {
        a.gems += floatArrayOf(g.x, g.y, bh * 0.06f)
    }
    a.body = body
    return a
}

private fun EnergyRec.system(w: Float, h: Float, k: EnergyColors): EnergyAnchors {
    val l = w * 0.05f
    val r = w * 0.95f
    val t = h * 0.26f
    val b = h * 0.78f
    val bh = b - t
    val sw = h * 0.016f
    val a = EnergyAnchors()
    val c = bh * 0.16f
    val body = Path().apply { moveTo(l + c, t); lineTo(r, t); lineTo(r, b - c); lineTo(r - c, b); lineTo(l, b); lineTo(l, t + c); close() }
    fill(body, lin(0f, t, 0f, b, 0f to lerp(k.d, k.p, 0.22f).a(0.95f), 1f to lerp(k.d, Color.Black, 0.6f).a(0.95f)))
    val hs = bh * 0.2f
    val grid = Path()
    for (row in 0 until 7) for (col in 0 until 36) {
        val x = l + col * hs * 1.5f
        val y = t + row * hs * 0.87f + (col % 2) * hs * 0.43f
        for (j in 0 until 6) {
            val ang = j * PIF / 3
            val px = x + cos(ang) * hs * 0.5f
            val py = y + sin(ang) * hs * 0.5f
            if (j == 0) grid.moveTo(px, py) else grid.lineTo(px, py)
        }
        grid.close()
    }
    clip(body) { stroke(grid, k.p.a(0.07f), 1f, true) }
    stroke(body, k.p.a(0.12f), sw * 8, true)
    stroke(body, k.p.a(0.5f), sw * 2.6f, true)
    stroke(body, k.hi.a(0.95f), sw * 0.9f)
    val hw = w * 0.17f
    val th = h * 0.12f
    val tab = Path().apply { moveTo(w / 2 - hw - th, t); lineTo(w / 2 - hw, t - th); lineTo(w / 2 + hw, t - th); lineTo(w / 2 + hw + th, t); close() }
    fill(tab, lerp(k.d, k.p, 0.35f).a(0.95f))
    stroke(tab, k.p.a(0.5f), sw * 2.2f, true)
    stroke(tab, k.hi.a(0.95f), sw * 0.8f)
    a.tab = floatArrayOf(w / 2, t - th * 0.5f, hw, th)
    a.body = body
    a.box = Rect(l, t, r, b)
    return a
}

private fun DrawScope.systemLive(w: Float, h: Float, t: Float, k: EnergyColors, a: EnergyAnchors) {
    val box = a.box
    val bh = box.height
    clipPath(a.body) {
        val sx = box.left + box.width * fract(t * 0.35f)
        drawRect(lin(sx - bh, 0f, sx, 0f, 0f to k.p.a(0f), 1f to k.p.a(0.35f)), Offset(sx - bh, box.top), Size(bh, bh), blendMode = BlendMode.Plus)
        drawRect(k.hi.a(0.8f), Offset(sx - 1.5f, box.top), Size(3f, bh), blendMode = BlendMode.Plus)
    }
    val tab = a.tab ?: return
    for (i in -1..1) {
        val x = tab[0] + i * tab[2] * 0.45f
        val on = (floor(t * 3).toInt() % 3) == i + 1
        drawPath(diamond(x, tab[1], tab[3] * 0.22f, tab[3] * 0.22f), (if (on) Color.White else k.p).a(if (on) 1f else 0.6f), blendMode = BlendMode.Plus)
        if (on) glow(x, tab[1], tab[3], k.p, 0.7f)
    }
    val pulse = 0.55f + 0.45f * sin(t * 3)
    val g2 = h * 0.045f
    val arm = bh * 0.5f
    for (c in listOf(floatArrayOf(box.left - g2, box.top - g2, 1f, 1f), floatArrayOf(box.right + g2, box.top - g2, -1f, 1f), floatArrayOf(box.left - g2, box.bottom + g2, 1f, -1f), floatArrayOf(box.right + g2, box.bottom + g2, -1f, -1f))) {
        val (x, y, sx, sy) = c
        val p = Path().apply { moveTo(x + sx * arm, y); lineTo(x, y); lineTo(x, y + sy * arm * 0.6f) }
        drawPath(p, k.p.a(0.3f * pulse), style = Stroke(h * 0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        drawPath(p, k.hi.a(pulse), style = Stroke(h * 0.012f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val cy = box.center.y
    for (side in intArrayOf(-1, 1)) {
        val x0 = if (side < 0) box.left + bh * 0.18f else box.right - bh * 0.18f
        for (j in 0 until 5) {
            val hh = bh * (0.12f + 0.25f * (0.5f + 0.5f * sin(t * 6 + j * 1.3f + side)))
            val x = x0 - side * j * bh * 0.09f
            drawRect(k.p.a(0.55f), Offset(x - bh * 0.025f, cy + bh * 0.3f - hh), Size(bh * 0.05f, hh), blendMode = BlendMode.Plus)
        }
    }
}

private fun EnergyRec.arcane(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val l = w * 0.15f
    val r = w * 0.85f
    val t = h * 0.32f
    val b = h * 0.68f
    val bh = b - t
    val cy = (t + b) / 2
    val sw = h * 0.02f
    val a = EnergyAnchors()
    val body = roundBody(l, t, r, b, bh * 0.5f)
    face(body, k, l, t, r, b, v.mirror)
    rim(body, k, t, b, sw)
    val big = h * 0.42f
    for (ex in floatArrayOf(l, r)) {
        fill(circle(ex, cy, big * 0.82f), rad(ex, cy, big * 0.82f, 0f to lerp(k.d, k.p, 0.4f), 1f to lerp(k.d, Color.Black, 0.6f)))
        rim(circle(ex, cy, big), k, cy - big, cy + big, sw * 0.8f)
    }
    a.ends = floatArrayOf(l, r)
    a.cy = cy
    a.radius = big
    a.rail = floatArrayOf(l + bh * 0.6f, r - bh * 0.6f, t, b)
    a.body = body
    return a
}

private fun DrawScope.arcaneLive(t: Float, k: EnergyColors, a: EnergyAnchors) {
    val big = a.radius
    val cy = a.cy
    val ends = a.ends ?: return
    ends.forEachIndexed { i, ex ->
        val dir = if (i == 1) -1 else 1
        translate(ex, cy) {
            rotateRad(t * 0.7f * dir, Offset.Zero) {
                val ticks = Path()
                for (j in 0 until 36) {
                    val ang = j / 36f * 2 * PIF
                    val outer = if (j % 3 != 0) 0.9f else 0.96f
                    ticks.moveTo(cos(ang) * big * 0.84f, sin(ang) * big * 0.84f)
                    ticks.lineTo(cos(ang) * big * outer, sin(ang) * big * outer)
                }
                drawPath(ticks, k.hi.a(0.9f), style = Stroke(1.3f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
                drawCircle(k.p.a(0.8f), big * 0.78f, Offset.Zero, style = Stroke(1.4f), blendMode = BlendMode.Plus)
            }
            rotateRad(-t * 1.1f * dir, Offset.Zero) {
                for (st in floatArrayOf(0f, PIF)) {
                    val tri = Path()
                    for (j in 0 until 4) {
                        val ang = st - PIF / 2 + j * 2 * PIF / 3
                        val x = cos(ang) * big * 0.66f
                        val y = sin(ang) * big * 0.66f
                        if (j == 0) tri.moveTo(x, y) else tri.lineTo(x, y)
                    }
                    drawPath(tri, k.s.a(0.4f), style = Stroke(3.5f, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                    drawPath(tri, lerp(k.s, Color.White, 0.6f).a(0.95f), style = Stroke(1.1f, join = StrokeJoin.Round))
                }
            }
        }
        glow(ex, cy, big * 0.38f * (0.8f + 0.3f * sin(t * 4)), Color.White, 0.85f)
        glow(ex, cy, big * 1.3f, k.p, 0.3f)
    }
    val rail = a.rail ?: return
    for (y in floatArrayOf(rail[2], rail[3])) {
        drawLine(
            k.hi.a(0.9f),
            Offset(rail[0], y),
            Offset(rail[1], y),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), -t * 80f % 28f),
            blendMode = BlendMode.Plus,
        )
    }
}

private fun EnergyRec.stormBlade(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val l = w * 0.14f
    val r = w * 0.86f
    val t = h * 0.3f
    val b = h * 0.7f
    val bh = b - t
    val cy = (t + b) / 2
    val sw = h * 0.024f
    val a = EnergyAnchors()
    for (side in intArrayOf(-1, 1)) {
        val ex = if (side < 0) l else r
        val n = 3 + v.dense
        for (i in 0 until n) {
            val f = i / (n - 1f) - 0.5f
            val ang = (if (side < 0) PIF else 0f) + f * 1.0f * side
            ornament(blade(ex, cy + f * bh * 0.5f, ang, bh * (1.4f - abs(f) * 1.1f), bh * 0.2f, 0f), k, t - bh, b + bh, sw, i % 2 == 1)
            val len = bh * (1.35f - abs(f) * 1.1f)
            a.pts += Offset(ex + cos(ang) * len, cy + f * bh * 0.5f + sin(ang) * len)
        }
        val g = Path().apply { moveTo(ex, t - bh * 0.3f); lineTo(ex + side * bh * 0.18f, cy); lineTo(ex, b + bh * 0.3f); lineTo(ex - side * bh * 0.1f, cy); close() }
        ornament(g, k, t - bh * 0.3f, b + bh * 0.3f, sw, false)
    }
    val body = chamferBody(l, t, r, b, bh * 0.3f)
    face(body, k, l, t, r, b, v.mirror)
    rim(body, k, t, b, sw)
    stroke(chamferBody(l + sw * 2, t + sw * 2, r - sw * 2, b - sw * 2, bh * 0.25f), k.p.a(0.6f), sw * 0.3f, true)
    a.gems += floatArrayOf(w / 2, t, bh * 0.1f)
    a.gems += floatArrayOf(w / 2, b, bh * 0.1f)
    a.body = body
    a.box = Rect(l, t, r, b)
    return a
}

private fun EnergyRec.celestial(w: Float, h: Float, k: EnergyColors, v: EnergyFrame): EnergyAnchors {
    val l = w * 0.1f
    val r = w * 0.9f
    val t = h * 0.38f
    val b = h * 0.76f
    val bh = b - t
    val cy = (t + b) / 2
    val sw = h * 0.022f
    val a = EnergyAnchors()
    val body = bodyOf(v.body, l, t, r, b)
    face(body, k, l, t, r, b, v.mirror)
    rim(body, k, t, b, sw)
    fun arcY(f: Float) = t - h * 0.04f - sin(f * PIF) * h * 0.24f
    fun arcX(f: Float) = l + bh * 0.8f + (r - l - bh * 1.6f) * f
    val arc = Path()
    for (i in 0..40) {
        val f = i / 40f
        if (i == 0) arc.moveTo(arcX(f), arcY(f)) else arc.lineTo(arcX(f), arcY(f))
    }
    stroke(arc, metalBrush(k, t - h * 0.3f, t), sw * 0.6f)
    for (i in 0 until 9) {
        val f = i / 8f
        val x = arcX(f)
        val y = arcY(f)
        val main = i == 4
        ornament(star(x, y, if (main) 6 else 4, bh * (if (main) 0.32f else 0.16f), bh * (if (main) 0.12f else 0.06f), -PIF / 2), k, y - bh * 0.3f, y + bh * 0.3f, sw, false)
        a.pts += Offset(x, y)
    }
    for (side in intArrayOf(-1, 1)) {
        val ex = if (side < 0) l else r
        // A crescent: the moon less a shifted disc
        val moon = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(ex - bh * 0.55f, cy - bh * 0.55f, ex + bh * 0.55f, cy + bh * 0.55f))
            val ox = ex - side * bh * 0.22f
            val oy = cy - bh * 0.08f
            addOval(Rect(ox - bh * 0.46f, oy - bh * 0.46f, ox + bh * 0.46f, oy + bh * 0.46f))
        }
        fill(moon, metalBrush(k, cy - bh * 0.6f, cy + bh * 0.6f))
        stroke(moon, k.metal[0].a(0.6f), sw * 0.2f)
        a.gems += floatArrayOf(ex + side * bh * 0.3f, cy, bh * 0.07f)
    }
    a.body = body
    return a
}

// ================================================================================================ Motion

private fun edgePoint(box: Rect, f: Float): Offset {
    val w = box.width
    val h = box.height
    var d = fract(f) * (2 * w + 2 * h)
    if (d < w) return Offset(box.left + d, box.top)
    d -= w
    if (d < h) return Offset(box.right, box.top + d)
    d -= h
    if (d < w) return Offset(box.right - d, box.bottom)
    d -= w
    return Offset(box.left, box.bottom - d)
}

/** What moves behind a frame, for the motions drawn under it (turning rays, aurora wisps). */
private fun DrawScope.behind(motion: Int, w: Float, h: Float, t: Float, k: EnergyColors) {
    when (motion) {
        7 -> {
            val cx = w / 2
            val cy = h / 2
            val len = w * 0.55f
            for (i in 0 until 24) {
                val ang = i / 24f * 2 * PIF + t * 0.15f
                val p = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx + cos(ang - 0.025f) * len, cy + sin(ang - 0.025f) * len * 0.45f)
                    lineTo(cx + cos(ang + 0.025f) * len, cy + sin(ang + 0.025f) * len * 0.45f)
                    close()
                }
                drawPath(p, rad(cx, cy, len, 0f to k.p.a(0.25f * (0.6f + 0.4f * sin(t * 2 + i))), 1f to k.p.a(0f)), blendMode = BlendMode.Plus)
            }
        }
        8 -> for (j in 0 until 3) {
            val p = Path()
            for (i in 0..60) {
                val x = w * i / 60f
                val y = h * (0.5f + 0.32f * sin(i * 0.18f + t * (0.8f + j * 0.3f) + j * 2))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, (if (j == 1) k.s else k.p).a(0.18f), style = Stroke(h * 0.12f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            drawPath(p, k.hi.a(0.35f), style = Stroke(h * 0.012f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        }
        else -> Unit
    }
}

/** The motion laid over a frame: one of ten, drawn from the anchors its still part left. */
private fun DrawScope.motion(motion: Int, w: Float, h: Float, t: Float, k: EnergyColors, a: EnergyAnchors) {
    val box = a.box
    when (motion) {
        // Comets running round the plate
        0 -> for (c in 0 until 2) {
            val head = fract(t * 0.16f + c / 2f)
            for (j in 0 until 26) {
                val pt = edgePoint(box, head - j * 0.003f)
                val al = 1 - j / 26f
                glow(pt.x, pt.y, (if (j == 0) h * 0.07f else h * 0.03f) * al + 2, if (j == 0) Color.White else k.p, al * (if (j == 0) 0.95f else 0.55f))
            }
        }
        // Glints twinkling on the ornaments' tips
        1 -> a.pts.forEachIndexed { i, pt ->
            val al = (0.5f + 0.5f * sin(t * 2.2f + i * 1.7f)).pow(5)
            if (al > 0.05f) {
                drawPath(spark(pt.x, pt.y, h * 0.05f * (0.6f + al)), Color.White.a(al), blendMode = BlendMode.Plus)
                glow(pt.x, pt.y, h * 0.07f, k.p, al * 0.6f)
            }
        }
        // Embers rising
        2 -> {
            val rd = Random(7)
            repeat(34) { i ->
                val age = fract(t * 0.4f + rd.nextFloat())
                val x = box.left + box.width * rd.nextFloat() + sin(t * 2 + i) * h * 0.04f
                val y = box.bottom - age * h * 0.9f
                glow(x, y, h * (0.012f + 0.02f * rd.nextFloat()), lerp(k.p, Color.White, 0.4f), (1 - age) * 0.9f)
            }
        }
        // Lightning crawling the edge
        3 -> {
            val tick = floor(t * 10).toInt()
            for (c in 0 until 2) {
                val rd = Random(tick * 13 + c + 1)
                val start = rd.nextFloat()
                val p = Path()
                var i = 0
                while (i <= 90) {
                    val q = edgePoint(box, start + i / 500f)
                    val jx = (rd.nextFloat() - 0.5f) * h * 0.1f
                    val jy = (rd.nextFloat() - 0.5f) * h * 0.1f
                    if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x + jx, q.y + jy)
                    i += 6
                }
                drawPath(p, k.p.a(0.35f), style = Stroke(h * 0.03f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                drawPath(p, k.hi.a(0.95f), style = Stroke(h * 0.007f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        // Rings pulsing out of the gems
        4 -> a.gems.forEachIndexed { i, g ->
            for (c in 0 until 2) {
                val f = fract(t * 0.6f + c * 0.5f + i * 0.13f)
                drawCircle(k.p.a((1 - f) * 0.8f), g[2] * (1 + f * 4), Offset(g[0], g[1]), style = Stroke(h * 0.008f), blendMode = BlendMode.Plus)
            }
        }
        // Motes orbiting the ends
        5 -> {
            val cy = box.center.y
            for ((ex, dir) in listOf(box.left to 1, box.right to -1)) for (i in 0 until 10) {
                val ang = t * 1.6f * dir + i / 10f * 2 * PIF
                glow(ex + cos(ang) * h * 0.3f, cy + sin(ang) * h * 0.14f, h * 0.025f, if (i % 3 != 0) k.p else k.s, 0.85f * (0.5f + 0.5f * sin(ang)))
            }
        }
        // Energy flowing along an inner line
        6 -> {
            val ins = h * 0.05f
            val dash = PathEffect.dashPathEffect(floatArrayOf(h * 0.12f, h * 0.08f), -(t * h * 0.8f) % (h * 0.2f))
            val tl = Offset(box.left + ins, box.top + ins)
            val sz = Size(box.width - ins * 2, box.height - ins * 2)
            drawRect(k.p.a(0.5f), tl, sz, style = Stroke(h * 0.018f, pathEffect = dash), blendMode = BlendMode.Plus)
            drawRect(k.hi.a(0.9f), tl, sz, style = Stroke(h * 0.006f, pathEffect = dash))
        }
        // A slow sweep of light over the plate, and its breathing glow
        9 -> {
            clipPath(a.body) {
                val x = box.left + box.width * fract(t * 0.22f) * 1.4f - box.width * 0.2f
                drawRect(
                    lin(x - h * 0.5f, box.top, x + h * 0.5f, box.bottom, 0f to Color.White.a(0f), 0.5f to Color.White.a(0.28f), 1f to Color.White.a(0f)),
                    Offset(x - h * 0.5f, box.top),
                    Size(h, box.height),
                    blendMode = BlendMode.Plus,
                )
            }
            glow(w / 2, box.center.y, w * 0.45f, k.p, 0.08f + 0.06f * sin(t * 2))
        }
        else -> Unit
    }
}

