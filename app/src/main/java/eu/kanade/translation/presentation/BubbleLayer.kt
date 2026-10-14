package eu.kanade.translation.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.style.BubbleKind
import eu.kanade.translation.style.TranslationTypeface
import eu.kanade.translation.translator.TextTranslatorLanguage
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANHWA typesetter for one page.
 *
 * Each translation is laid out inside the real speech bubble found from the page pixels (not the tight OCR
 * box): the largest size whose lines fit both width and height without cutting a word, with balanced
 * lines, evened out against the page median. Nothing is ever clipped: when the text does not fit, the area
 * grows to the whole bubble and the text gets smaller. A closed bubble is cleaned entirely in its own
 * colour, text over art gets a soft box, and sound effects are only outlined. Layouts are computed once in
 * page pixels and drawn at the current zoom. In edit mode the reader can move, resize and re-size the text.
 */
@Composable
fun BubbleLayer(translation: PageTranslation, zoom: Float) {
    val context = LocalContext.current
    val graph = remember { context.appGraph }
    val prefs = remember { graph.translationPreferences }
    val editStore = remember { graph.translationEditStore }
    val typefaceIndex by prefs.translationTypeface().collectAsState()
    val sizeScale by prefs.translationSizeScale().collectAsState()
    val minSize by prefs.translationMinSize().collectAsState()
    val outline by prefs.translationOutline().collectAsState()
    val lineSpacing by prefs.translationLineSpacing().collectAsState()
    val skipSfx by prefs.translationSkipSfx().collectAsState()
    val target by prefs.translateToLanguage().collectAsState()
    val editing by editStore.editing.collectAsState()
    val typeface = TranslationTypeface.entries.getOrNull(typefaceIndex) ?: TranslationTypeface.SMART
    val latinOnly = remember(target) {
        val lang = TextTranslatorLanguage.entries.firstOrNull { it.name.equals(target, true) }
        lang != null && lang.code !in RTL_SCRIPTS && lang.code !in WIDE_SCRIPTS
    }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    var revision by remember { mutableIntStateOf(0) }

    val placed = remember(translation, typeface, sizeScale, minSize, lineSpacing, latinOnly, outline, skipSfx, revision) {
        Typesetter(measurer, typeface, latinOnly, lineSpacing, sizeScale, minSize, outline, skipSfx).layout(translation)
    }

    // Edit mode: the bubble being moved and its live gesture, applied to the block on release
    var selected by remember(translation) { mutableIntStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var dragScale by remember { mutableFloatStateOf(1f) }
    val view = LocalView.current
    val density = LocalDensity.current
    val widthDp = with(density) { (translation.imgWidth * zoom).toDp() }
    val heightDp = with(density) { (translation.imgHeight * zoom).toDp() }

    fun save() {
        editStore.save(translation)
        revision++
    }

    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(widthDp, heightDp)) {
        Canvas(
            modifier = Modifier
                .requiredSize(widthDp, heightDp)
                .then(
                    if (!editing) {
                        Modifier
                    } else {
                        Modifier.pointerInput(placed, zoom) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val at = down.position / zoom
                                val hit = placed.indexOfLast { it != null && it.area.inflate(it.area.minDimension * 0.1f).contains(at) }
                                if (hit < 0) {
                                    selected = -1
                                    return@awaitEachGesture
                                }
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                down.consume()
                                selected = hit
                                dragX = 0f
                                dragY = 0f
                                dragScale = 1f
                                do {
                                    val event = awaitPointerEvent()
                                    val pan = event.calculatePan()
                                    dragX += pan.x / zoom
                                    dragY += pan.y / zoom
                                    dragScale = (dragScale * event.calculateZoom()).coerceIn(0.3f, 4f)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                val block = placed[hit]?.block
                                if (block != null && (dragX != 0f || dragY != 0f || dragScale != 1f)) {
                                    block.userDx += dragX
                                    block.userDy += dragY
                                    block.userScale = (block.userScale * dragScale).coerceIn(0.3f, 4f)
                                    dragX = 0f
                                    dragY = 0f
                                    dragScale = 1f
                                    save()
                                }
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    },
                ),
        ) {
            scale(zoom, pivot = Offset.Zero) {
                placed.forEachIndexed { index, item ->
                    item ?: return@forEachIndexed
                    val live = editing && index == selected
                    val dx = if (live) dragX else 0f
                    val dy = if (live) dragY else 0f
                    val ds = if (live) dragScale else 1f
                    drawPlaced(item, dx, dy, ds, editing, live, zoom)
                }
            }
        }

        // Text size and reset for the selected bubble
        val chosen = placed.getOrNull(selected)
        if (editing && chosen != null) {
            val x = ((chosen.area.left + dragX) * zoom).roundToInt()
            val y = ((chosen.area.bottom + dragY) * zoom).roundToInt() + with(density) { 6.dp.roundToPx() }
            Row(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .background(Color(0xE6140508), RoundedCornerShape(14.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                EditChip("A−") { chosen.block.userFont = (chosen.block.userFont / 1.12f).coerceAtLeast(0.3f); save() }
                EditChip("A+") { chosen.block.userFont = (chosen.block.userFont * 1.12f).coerceAtMost(4f); save() }
                EditChip("↺") {
                    chosen.block.userDx = 0f
                    chosen.block.userDy = 0f
                    chosen.block.userScale = 1f
                    chosen.block.userFont = 1f
                    save()
                }
            }
        }
    }
}

@Composable
private fun EditChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private enum class Erase { BUBBLE, BOX, NONE }

/** One bubble ready to draw, in page pixels. */
private class Placed(
    val block: TranslationBlock,
    val area: Rect,
    val bubble: Rect?,
    val round: Boolean,
    val cover: Rect,
    val erase: Erase,
    val layout: TextLayoutResult,
    val bg: Color,
    val fg: Color,
    val outlineColor: Color,
    val stroke: Float,
    val angle: Float,
)

private fun DrawScope.drawPlaced(p: Placed, dx: Float, dy: Float, ds: Float, editing: Boolean, selected: Boolean, zoom: Float) {
    // Cleaning stays on the original bubble; only the text follows the reader's move
    when (p.erase) {
        Erase.BUBBLE -> {
            val b = p.bubble!!
            val inset = b.deflate(min(b.width, b.height) * 0.05f)
            if (p.round) drawOval(p.bg, inset.topLeft, inset.size) else drawRoundRect(p.bg, inset.topLeft, inset.size, CornerRadius(inset.minDimension * 0.08f))
            drawRoundRect(p.bg, p.cover.topLeft, p.cover.size, CornerRadius(p.cover.minDimension * 0.3f))
        }
        Erase.BOX -> {
            val c = p.cover
            val feather = c.minDimension * 0.08f
            val r = CornerRadius(c.minDimension * 0.3f)
            drawRoundRect(p.bg.copy(alpha = p.bg.alpha * 0.5f), Offset(c.left - feather, c.top - feather), Size(c.width + feather * 2, c.height + feather * 2), r)
            drawRoundRect(p.bg, c.topLeft, c.size, r)
        }
        Erase.NONE -> Unit
    }
    val area = p.area
    val center = area.center + Offset(dx, dy)
    translate(center.x, center.y) {
        scale(ds, pivot = Offset.Zero) {
            rotate(p.angle, pivot = Offset.Zero) {
                val topLeft = Offset(-p.layout.size.width / 2f, -p.layout.size.height / 2f)
                if (p.stroke > 0f) {
                    drawText(p.layout, color = p.outlineColor, topLeft = topLeft, drawStyle = Stroke(width = p.stroke, join = StrokeJoin.Round))
                }
                drawText(p.layout, color = p.fg, topLeft = topLeft)
            }
            if (editing) {
                val half = Size(area.width, area.height)
                val frame = if (selected) Color(0xFFFF2E63) else Color(0xCCFF6B8B)
                drawRoundRect(
                    color = frame,
                    topLeft = Offset(-half.width / 2f, -half.height / 2f),
                    size = half,
                    cornerRadius = CornerRadius(12f / zoom),
                    style = Stroke(width = (if (selected) 3f else 2f) / zoom, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / zoom, 8f / zoom))),
                )
            }
        }
    }
}

private class Typesetter(
    private val measurer: TextMeasurer,
    private val typeface: TranslationTypeface,
    private val latinOnly: Boolean,
    private val lineSpacing: Float,
    private val sizeScale: Float,
    private val minSize: Int,
    private val outline: Boolean,
    private val skipSfx: Boolean,
) {
    // Page pixels: 1 sp = 1 px
    private val unit = Density(1f, 1f)

    private class Plan(val block: TranslationBlock, val kind: BubbleKind, val area: Rect, val full: Rect, val style: TextStyle, val fit: Float)

    fun layout(page: PageTranslation): List<Placed?> {
        val minPx = page.imgWidth * minSize / 400f
        val plans = page.blocks.map { block ->
            if (block.translation.isBlank()) return@map null
            val kind = BubbleKind.parse(block.kind) ?: BubbleKind.guess(block.text, block.translation)
            if (kind == BubbleKind.SFX && skipSfx) return@map null
            val style = styleFor(kind)
            var area = edited(block, baseArea(block, kind))
            val full = edited(block, fullArea(block, kind))
            var fit = fit(block.translation, style, area)
            // Too small for this bubble's middle: use the whole bubble before going smaller
            if (fit < minPx && full.width * full.height > area.width * area.height) {
                area = full
                fit = fit(block.translation, style, area)
            }
            Plan(block, kind, area, full, style, fit)
        }
        val fits = plans.mapNotNull { it?.fit }.filter { it > 0f }.sorted()
        val median = fits.getOrNull(fits.size / 2) ?: 0f
        return plans.map { plan ->
            plan ?: return@map null
            val block = plan.block
            // Bubbles of a page look alike: big ones come down towards the median (never above their fit)
            var size = if (median > 0f) min(plan.fit, median * 1.35f) else plan.fit
            size *= sizeScale
            if (sizeScale > 1f) size = min(size, fit(block.translation, plan.style, plan.full))
            size *= block.userFont
            size = size.coerceAtLeast(4f)
            val layout = balanced(block.translation, plan.style.copy(fontSize = size.sp), plan.area)
            val bg = Color(block.bgColor.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt()).copy(alpha = 1f)
            val fg = Color(block.textColor)
            val bubble = if (block.hasBubble) Rect(block.bubbleX, block.bubbleY, block.bubbleX + block.bubbleW, block.bubbleY + block.bubbleH) else null
            val erase = when {
                plan.kind == BubbleKind.SFX && bubble == null -> Erase.NONE
                bubble != null -> Erase.BUBBLE
                else -> Erase.BOX
            }
            val cover = textCover(block).let { c -> bubble?.let { c.intersect(it.deflate(min(it.width, it.height) * 0.05f)) } ?: c }
            val strong = erase != Erase.BUBBLE
            val stroke = when {
                erase == Erase.NONE -> size * 0.18f
                outline && strong -> size * 0.12f
                outline -> size * 0.06f
                else -> 0f
            }
            val outlineColor = if (fg.luminance() > 0.5f) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.92f)
            val angle = if (block.angle > 85 || block.angle < -85 || kotlin.math.abs(block.angle) < 3) 0f else block.angle
            Placed(block, plan.area, bubble, block.bubbleRound, cover, erase, layout, bg, fg, outlineColor, stroke, angle)
        }
    }

    private fun styleFor(kind: BubbleKind) = TextStyle(
        fontFamily = typeface.family(kind, latinOnly),
        textAlign = TextAlign.Center,
        textDirection = TextDirection.Content,
        lineHeight = lineSpacing.em,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

    /** The middle of the bubble where text reads well, or the padded text box when there is no bubble. */
    private fun baseArea(block: TranslationBlock, kind: BubbleKind): Rect {
        if (block.hasBubble) {
            val (fw, fh) = if (block.bubbleRound) 0.76f to 0.68f else 0.9f to 0.84f
            val cx = block.bubbleX + block.bubbleW / 2
            val cy = block.bubbleY + block.bubbleH / 2
            val area = Rect(cx - block.bubbleW * fw / 2, cy - block.bubbleH * fh / 2, cx + block.bubbleW * fw / 2, cy + block.bubbleH * fh / 2)
            // Never smaller than the original text
            return Rect(min(area.left, block.x), min(area.top, block.y), max(area.right, block.x + block.width), max(area.bottom, block.y + block.height))
                .intersect(Rect(block.bubbleX, block.bubbleY, block.bubbleX + block.bubbleW, block.bubbleY + block.bubbleH))
        }
        val cover = textCover(block)
        return if (kind == BubbleKind.SFX) cover.inflate(cover.minDimension * 0.15f) else cover
    }

    private fun fullArea(block: TranslationBlock, kind: BubbleKind): Rect {
        if (!block.hasBubble) return baseArea(block, kind).let { it.inflate(it.minDimension * 0.15f) }
        val (fw, fh) = if (block.bubbleRound) 0.86f to 0.8f else 0.96f to 0.92f
        val cx = block.bubbleX + block.bubbleW / 2
        val cy = block.bubbleY + block.bubbleH / 2
        return Rect(cx - block.bubbleW * fw / 2, cy - block.bubbleH * fh / 2, cx + block.bubbleW * fw / 2, cy + block.bubbleH * fh / 2)
    }

    /** The reader's move and resize of this bubble. */
    private fun edited(block: TranslationBlock, area: Rect): Rect {
        if (!block.edited) return area
        val c = area.center + Offset(block.userDx, block.userDy)
        val w = area.width * block.userScale
        val h = area.height * block.userScale
        return Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    }

    /** The original lines with a margin, where every letter must be hidden. */
    private fun textCover(block: TranslationBlock): Rect {
        val padX = max(block.symWidth * 0.8f, 5f)
        val padY = max(block.symHeight * 0.45f, 4f)
        return Rect(block.x - padX, block.y - padY, block.x + block.width + padX, block.y + block.height + padY)
    }

    /** Largest size (page px) whose lines fit [area] without breaking a word. */
    private fun fit(text: String, style: TextStyle, area: Rect): Float {
        val maxW = area.width.toInt().coerceAtLeast(1)
        val maxH = area.height
        // Widest word at 100 px: no size may make it wider than the area
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.sortedByDescending { it.length }.take(3)
        val word100 = words.maxOfOrNull { measure(it, style.copy(fontSize = 100.sp), Constraints()).size.width.toFloat() } ?: 0f
        var high = min(area.height, if (word100 > 0f) maxW * 100f / word100 else area.height).coerceAtLeast(4f)
        var low = 3f
        var best = low
        repeat(12) {
            val mid = (low + high) / 2f
            val layout = measure(text, style.copy(fontSize = mid.sp), Constraints(maxWidth = maxW))
            if (layout.size.height <= maxH && !layout.hasVisualOverflow) {
                best = mid
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    /** Same number of lines in the narrowest width, so lines have similar lengths. */
    private fun balanced(text: String, style: TextStyle, area: Rect): TextLayoutResult {
        val maxW = area.width.toInt().coerceAtLeast(1)
        val full = measure(text, style, Constraints(maxWidth = maxW))
        if (full.lineCount <= 1) return full
        var low = (maxW * 0.45f).toInt()
        var high = maxW
        var best = full
        repeat(7) {
            val mid = (low + high) / 2
            val layout = measure(text, style, Constraints(maxWidth = mid.coerceAtLeast(1)))
            if (layout.lineCount <= full.lineCount && layout.size.height <= max(area.height, full.size.height.toFloat()) && !layout.hasVisualOverflow) {
                best = layout
                high = mid
            } else {
                low = mid + 1
            }
        }
        return best
    }

    private fun measure(text: String, style: TextStyle, constraints: Constraints) =
        measurer.measure(text = text, style = style, constraints = constraints, density = unit)
}

private val RTL_SCRIPTS = setOf("ar", "fa", "ur", "he", "iw")
private val WIDE_SCRIPTS = setOf("ja", "zh", "ko", "th", "hi", "ru", "uk", "el", "bg")
