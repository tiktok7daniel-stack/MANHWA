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
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import eu.kanade.translation.data.PageImageProcessor
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.style.BubbleKind
import eu.kanade.translation.style.TranslationTypeface
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.edit.BubbleTextDialog
import eu.kanade.translation.edit.BubbleTextEditor
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANHWA typesetter for one page.
 *
 * Every original line is cleaned on its own (its box +10 px left/right, +6 px top/bottom) in the colour right
 * behind its letters, so no letter is left and the drawing around is never covered. The translation is drawn
 * horizontally at the centre of the cleaned lines, at the largest size whose lines fit without cutting a word,
 * and it may only grow a little into free space that no other bubble uses. Layouts are computed once in page
 * pixels and drawn at the current zoom. In edit mode the reader can move, resize and re-size the text.
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
    val dramatic by prefs.translationDramaticStyle().collectAsState()
    val target by prefs.translateToLanguage().collectAsState()
    val editing by editStore.editing.collectFlowAsState()
    val typeface = TranslationTypeface.entries.getOrNull(typefaceIndex) ?: TranslationTypeface.SMART
    val latinOnly = remember(target) {
        val lang = TextTranslatorLanguage.entries.firstOrNull { it.name.equals(target, true) }
        lang != null && lang.code !in RTL_SCRIPTS && lang.code !in WIDE_SCRIPTS
    }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    var revision by remember { mutableIntStateOf(0) }

    val placed = remember(translation, typeface, sizeScale, minSize, lineSpacing, latinOnly, outline, skipSfx, dramatic, revision) {
        Typesetter(measurer, typeface, latinOnly, lineSpacing, sizeScale, minSize, outline, skipSfx, dramatic).layout(translation)
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

    var rewriting by remember(translation) { mutableStateOf<TranslationBlock?>(null) }
    val scope = rememberCoroutineScope()

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
                // MANHWA: rewrite the bubble (by hand or with the team); the team learns the new wording
                EditIcon(MaterialSymbols.Rounded.Edit) { rewriting = chosen.block }
                EditIcon(MaterialSymbols.Rounded.Refresh) {
                    chosen.block.userDx = 0f
                    chosen.block.userDy = 0f
                    chosen.block.userScale = 1f
                    chosen.block.userFont = 1f
                    save()
                }
            }
        }
    }

    rewriting?.let { block ->
        BubbleTextDialog(
            block = block,
            onDismiss = { rewriting = null },
            onSave = { wording ->
                val old = block.translation
                block.userText = wording
                block.translation = wording
                save()
                rewriting = null
                scope.launch { BubbleTextEditor.learn(context, block, old, wording) }
            },
        )
    }
}

@Composable
private fun EditIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.Icon(
        icon,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .requiredSize(20.dp),
    )
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

/** One patch of the page to clean, in page pixels, with the colour behind the letters there. */
private class Patch(val rect: Rect, val color: Color, val radius: Float)

/** One bubble ready to draw, in page pixels. */
private class Placed(
    val block: TranslationBlock,
    val area: Rect,
    val patches: List<Patch>,
    val layout: TextLayoutResult,
    val fg: Color,
    val outlineColor: Color,
    val stroke: Float,
    /** MANHWA: a system window, drawn with glowing HUD corners around its text. */
    val hud: Boolean = false,
    val size: Float = 0f,
)

private fun DrawScope.drawPlaced(p: Placed, dx: Float, dy: Float, ds: Float, editing: Boolean, selected: Boolean, zoom: Float) {
    // Every original line is cleaned on its own, in the colour right behind its letters; the drawing around
    // the text is never touched. Cleaning stays on the original lines; only the text follows the reader's move.
    p.patches.forEach { patch ->
        drawRoundRect(patch.color, patch.rect.topLeft, patch.rect.size, CornerRadius(patch.radius))
    }
    val area = p.area
    val center = area.center + Offset(dx, dy)
    translate(center.x, center.y) {
        scale(ds, pivot = Offset.Zero) {
            // Always horizontal: tilted text runs into the next bubble
            val topLeft = Offset(-p.layout.size.width / 2f, -p.layout.size.height / 2f)
            if (p.stroke > 0f) {
                drawText(p.layout, color = p.outlineColor, topLeft = topLeft, drawStyle = Stroke(width = p.stroke, join = StrokeJoin.Round))
            }
            drawText(p.layout, color = p.fg, topLeft = topLeft)
            if (p.hud) drawHudCorners(topLeft, Size(p.layout.size.width.toFloat(), p.layout.size.height.toFloat()), p.size)
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
    /** MANHWA: system windows with glowing HUD corners, shouts with a heavy outline. */
    private val dramatic: Boolean = true,
) {
    // Page pixels: 1 sp = 1 px
    private val unit = Density(1f, 1f)

    private class Plan(
        val block: TranslationBlock,
        val kind: BubbleKind,
        val area: Rect,
        val room: Rect,
        val patches: List<Patch>,
        val style: TextStyle,
        val fit: Float,
    )

    fun layout(page: PageTranslation): List<Placed?> {
        val minPx = page.imgWidth * minSize / 400f
        val pageRect = Rect(0f, 0f, page.imgWidth.coerceAtLeast(1f), page.imgHeight.coerceAtLeast(1f))
        // The cleaned area of every bubble: the text of one bubble never goes over another one
        val cleaned = page.blocks.map { block -> union(lineRects(block)) }
        val plans = page.blocks.mapIndexed { index, block ->
            if (block.translation.isBlank()) return@mapIndexed null
            val kind = BubbleKind.parse(block.kind) ?: BubbleKind.guess(block.text, block.translation)
            if (kind == BubbleKind.SFX && skipSfx) return@mapIndexed null
            val style = styleFor(kind)
            // MANHWA art text: nothing erased, the translation in a caption right under the drawn text
            val art = kind == BubbleKind.ART
            val patches = if (kind == BubbleKind.SFX || art) emptyList() else patches(block)
            val base = (if (art) artCaption(cleaned[index]) else cleaned[index]).intersect(pageRect)
            // A little more room when the translation is longer, stopping at the neighbours
            val others = cleaned.filterIndexed { i, _ -> i != index }
            val room = roomAround(base, others).intersect(pageRect)
            var area = edited(block, base)
            var fit = fit(eu.kanade.translation.translator.TextSanitizer.dropTatweel(block.translation), style, area)
            if (fit < minPx && room.width * room.height > base.width * base.height) {
                val wider = edited(block, room)
                val widerFit = fit(eu.kanade.translation.translator.TextSanitizer.dropTatweel(block.translation), style, wider)
                if (widerFit > fit) {
                    area = wider
                    fit = widerFit
                }
            }
            Plan(block, kind, area, edited(block, room), patches, style, fit)
        }
        val fits = plans.mapNotNull { it?.fit }.filter { it > 0f }.sorted()
        val median = fits.getOrNull(fits.size / 2) ?: 0f
        return plans.map { plan ->
            plan ?: return@map null
            val block = plan.block
            // Bubbles of a page look alike: big ones come down towards the median (never above their fit)
            var size = if (median > 0f) min(plan.fit, median * 1.35f) else plan.fit
            size *= sizeScale
            if (sizeScale > 1f) size = min(size, fit(eu.kanade.translation.translator.TextSanitizer.dropTatweel(block.translation), plan.style, plan.room))
            size *= block.userFont
            size = size.coerceAtLeast(4f)
            val area = if (size > plan.fit) plan.room else plan.area
            val layout = balanced(eu.kanade.translation.translator.TextSanitizer.dropTatweel(block.translation), plan.style.copy(fontSize = size.sp), area)
            val bg = Color(block.bgColor.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt()).copy(alpha = 1f)
            val fg = if (plan.kind == BubbleKind.ART) ART_TEXT else Color(block.textColor)
            val shout = dramatic && plan.kind == BubbleKind.SHOUT
            // MANHWA: the original letters' outline colour, kept on the translation
            val ownStroke = block.strokeColor.takeIf { it != 0 }?.let { Color(it) }
            val stroke = when {
                plan.kind == BubbleKind.SFX || plan.kind == BubbleKind.ART -> size * 0.18f
                shout -> size * 0.12f
                ownStroke != null -> size * 0.09f
                outline -> size * 0.08f
                else -> 0f
            }
            val outlineColor = when {
                plan.kind == BubbleKind.ART -> ART_OUTLINE
                // A shout stands out in the colour opposite to its letters
                shout -> if (fg.luminance() > 0.5f) Color.Black else Color.White
                ownStroke != null -> ownStroke
                fg.luminance() > 0.5f -> Color.Black.copy(alpha = 0.75f)
                else -> bg.copy(alpha = 0.95f)
            }
            Placed(block, area, plan.patches, layout, fg, outlineColor, stroke, hud = dramatic && plan.kind == BubbleKind.SYSTEM, size = size)
        }
    }

    private fun styleFor(kind: BubbleKind) = TextStyle(
        fontFamily = typeface.family(kind, latinOnly),
        textAlign = TextAlign.Center,
        textDirection = TextDirection.Content,
        lineHeight = lineSpacing.em,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

    /** Each original line with its erase margin (+10 px left/right, +6 px top/bottom at least). */
    private fun artCaption(text: Rect): Rect {
        val widen = text.width * 0.08f
        val height = maxOf(text.height * 0.9f, text.width * 0.16f)
        return Rect(text.left - widen, text.bottom, text.right + widen, text.bottom + height)
    }

    private fun lineRects(block: TranslationBlock): List<Rect> = block.lineBoxes.map { line ->
        val px = PageImageProcessor.padX(line.h)
        val py = PageImageProcessor.padY(line.h)
        Rect(line.x - px, line.y - py, line.right + px, line.bottom + py)
    }

    /** The patches that clean a block: one per line, plus the thin gap between two lines of the same column. */
    private fun patches(block: TranslationBlock): List<Patch> {
        val fallback = block.bgColor.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt()
        val lines = block.lineBoxes.sortedBy { it.y }
        val out = mutableListOf<Patch>()
        lines.forEachIndexed { i, line ->
            val px = PageImageProcessor.padX(line.h)
            val py = PageImageProcessor.padY(line.h)
            val color = Color(line.bg.takeIf { it != 0 } ?: fallback).copy(alpha = 1f)
            out += Patch(Rect(line.x - px, line.y - py, line.right + px, line.bottom + py), color, line.h * 0.3f)
            val next = lines.getOrNull(i + 1) ?: return@forEachIndexed
            val gapTop = line.bottom + py
            val gapBottom = next.y - PageImageProcessor.padY(next.h)
            val left = max(line.x, next.x) - px
            val right = min(line.right, next.right) + px
            if (gapBottom > gapTop && gapBottom - gapTop < min(line.h, next.h) * 1.2f && right > left) {
                out += Patch(Rect(left, gapTop - 1f, right, gapBottom + 1f), color, 0f)
            }
        }
        return out
    }

    private fun union(rects: List<Rect>): Rect = rects.reduce { a, b ->
        Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    }

    /** [base] grown by a margin, each side stopping where another bubble's cleaned area starts. */
    private fun roomAround(base: Rect, others: List<Rect>): Rect {
        var left = base.left - base.width * 0.15f
        var right = base.right + base.width * 0.15f
        var top = base.top - base.height * 0.25f
        var bottom = base.bottom + base.height * 0.25f
        for (o in others) {
            val overlapsY = o.top < bottom && o.bottom > top
            val overlapsX = o.left < right && o.right > left
            if (overlapsY && o.right <= base.left) left = max(left, o.right)
            if (overlapsY && o.left >= base.right) right = min(right, o.left)
            if (overlapsX && o.bottom <= base.top) top = max(top, o.bottom)
            if (overlapsX && o.top >= base.bottom) bottom = min(bottom, o.top)
        }
        return Rect(min(left, base.left), min(top, base.top), max(right, base.right), max(bottom, base.bottom))
    }

    /** The reader's move and resize of this bubble. */
    private fun edited(block: TranslationBlock, area: Rect): Rect {
        if (!block.edited) return area
        val c = area.center + Offset(block.userDx, block.userDy)
        val w = area.width * block.userScale
        val h = area.height * block.userScale
        return Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    }

    /** Largest size (page px) whose lines fit [area] without breaking a word. */
    private fun fit(text: String, style: TextStyle, area: Rect): Float {
        if (area.width <= 1f || area.height <= 1f) return 3f
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

/** MANHWA: glowing corners around a system window's text, like a game interface (the window in the art stays visible). */
private fun DrawScope.drawHudCorners(topLeft: Offset, text: Size, size: Float) {
    val pad = size * 0.45f
    val left = topLeft.x - pad
    val top = topLeft.y - pad * 0.7f
    val right = topLeft.x + text.width + pad
    val bottom = topLeft.y + text.height + pad * 0.7f
    val arm = min(size * 0.9f, min(right - left, bottom - top) / 3f)
    val width = max(1.5f, size * 0.07f)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
        moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
        moveTo(right, bottom - arm); lineTo(right, bottom); lineTo(right - arm, bottom)
        moveTo(left + arm, bottom); lineTo(left, bottom); lineTo(left, bottom - arm)
    }
    drawPath(path, HudColor.copy(alpha = 0.28f), style = Stroke(width = width * 3.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, HudColor, style = Stroke(width = width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = StrokeJoin.Round))
}

private val HudColor = Color(0xFF4FD8FF)

private val ART_TEXT = Color(0xFFFFD1D7)
private val ART_OUTLINE = Color(0xF2120408)
