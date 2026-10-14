package eu.kanade.translation.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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

/**
 * MANHWA typesetter for one page.
 *
 * Every bubble is cleaned with its own sampled colour on a mask that covers all the original lines with a
 * soft edge, then the translation is fitted inside the ellipse of the bubble: the largest size whose
 * wrapped lines fit both width and height, computed once per page in page pixels, evened out against the
 * page's median so neighbouring bubbles look consistent, then drawn at the current zoom. Text is laid out
 * right to left for Arabic, centred, with an optional outline and a typeface picked from the bubble kind.
 */
@Composable
fun BubbleLayer(translation: PageTranslation, scale: Float) {
    val context = LocalContext.current
    val prefs = remember { context.appGraph.translationPreferences }
    val typefaceIndex by prefs.translationTypeface().collectAsState()
    val sizeScale by prefs.translationSizeScale().collectAsState()
    val minSize by prefs.translationMinSize().collectAsState()
    val outline by prefs.translationOutline().collectAsState()
    val lineSpacing by prefs.translationLineSpacing().collectAsState()
    val target by prefs.translateToLanguage().collectAsState()
    val typeface = TranslationTypeface.entries.getOrNull(typefaceIndex) ?: TranslationTypeface.AUTO
    val latinOnly = remember(target) {
        val lang = TextTranslatorLanguage.entries.firstOrNull { it.name.equals(target, true) }
        lang != null && lang.code !in RTL_SCRIPTS && lang.code !in WIDE_SCRIPTS
    }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current

    // Font size per bubble, in page pixels, at zoom 1
    val sizes = remember(translation, typeface, sizeScale, minSize, lineSpacing, latinOnly) {
        val raw = translation.blocks.map { block ->
            fitSize(measurer, density, block, styleFor(block, typeface, latinOnly, lineSpacing))
        }
        val median = raw.sorted().getOrNull(raw.size / 2) ?: 0f
        raw.map { best ->
            val evened = if (median > 0f) best.coerceIn(median * 0.72f, median * 1.35f).coerceAtMost(best) else best
            evened * sizeScale
        }
    }

    translation.blocks.forEachIndexed { index, block ->
        val geometry = block.geometry()
        val style = styleFor(block, typeface, latinOnly, lineSpacing)
        val minPx = with(density) { minSize.sp.toPx() } / scale.coerceAtLeast(0.01f)
        val pagePx = max(sizes.getOrElse(index) { 12f }, minPx)
        val fontSize = with(density) { (pagePx * scale).toSp() }
        val bg = if (block.bgColor != 0) Color(block.bgColor) else Color.White
        val fg = Color(block.textColor)
        val outlineColor = if (fg.luminance() > 0.5f) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.9f)
        val w = with(density) { (geometry.width * scale).toDp() }
        val h = with(density) { (geometry.height * scale).toDp() }
        val angle = if (block.angle > 85 || block.angle < -85) 0f else block.angle
        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(with(density) { (geometry.x * scale).toDp() }, with(density) { (geometry.y * scale).toDp() })
                .requiredSize(w, h)
                .drawBehind {
                    // Soft-edged mask in the bubble's own colour: hides every original letter
                    val r = CornerRadius(size.minDimension * 0.45f)
                    val feather = size.minDimension * 0.06f
                    drawRoundRect(bg.copy(alpha = 0.55f), Offset(-feather, -feather), Size(size.width + feather * 2, size.height + feather * 2), r)
                    drawRoundRect(bg, cornerRadius = r)
                },
            contentAlignment = Alignment.Center,
        ) {
            val textModifier = Modifier
                .requiredSize(w * INNER, h * INNER)
                .wrapContentSize(Alignment.Center)
                .rotate(angle)
            if (outline) {
                Text(
                    text = block.translation,
                    style = style.copy(fontSize = fontSize, color = outlineColor, drawStyle = Stroke(width = pagePx * scale * 0.12f)),
                    modifier = textModifier,
                )
            }
            Text(
                text = block.translation,
                style = style.copy(fontSize = fontSize, color = fg),
                modifier = textModifier,
            )
        }
    }
}

private const val INNER = 0.88f
private val RTL_SCRIPTS = setOf("ar", "fa", "ur", "he", "iw")
private val WIDE_SCRIPTS = setOf("ja", "zh", "ko", "th", "hi", "ru", "uk", "el", "bg")

/** The cleaned area: the OCR box grown around all its lines, in page pixels. */
private data class Geometry(val x: Float, val y: Float, val width: Float, val height: Float)

private fun TranslationBlock.geometry(): Geometry {
    val padX = max(symWidth * 1.2f, 6f)
    val padY = max(symHeight * 0.6f, 4f)
    return Geometry(max(x - padX, 0f), max(y - padY, 0f), width + padX * 2, height + padY * 2)
}

private fun styleFor(block: TranslationBlock, typeface: TranslationTypeface, latinOnly: Boolean, lineSpacing: Float): TextStyle {
    val kind = BubbleKind.parse(block.kind) ?: BubbleKind.guess(block.text, block.translation)
    return TextStyle(
        fontFamily = typeface.family(kind, latinOnly),
        textAlign = TextAlign.Center,
        textDirection = TextDirection.Content,
        lineHeight = lineSpacing.em,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
}

/** Largest font size (page pixels) whose wrapped text fits the ellipse-inscribed area of the bubble. */
private fun fitSize(measurer: TextMeasurer, density: Density, block: TranslationBlock, style: TextStyle): Float {
    val g = block.geometry()
    val maxW = (g.width * INNER).toInt().coerceAtLeast(1)
    val maxH = (g.height * INNER).toInt().coerceAtLeast(1)
    if (block.translation.isBlank()) return 0f
    var low = 4f
    var high = (g.height * 0.9f).coerceAtLeast(6f)
    var best = low
    repeat(14) {
        val mid = (low + high) / 2f
        val layout = measurer.measure(
            text = block.translation,
            style = style.copy(fontSize = with(density) { mid.toSp() }),
            constraints = Constraints(maxWidth = maxW),
            density = density,
        )
        val fits = layout.size.height <= maxH && layout.size.width <= maxW
        if (fits) {
            best = mid
            low = mid
        } else {
            high = mid
        }
    }
    return best
}
