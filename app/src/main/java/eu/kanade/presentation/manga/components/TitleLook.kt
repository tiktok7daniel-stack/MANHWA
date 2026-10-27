package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.icerock.moko.resources.StringResource
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.PI
import kotlin.math.sin

/**
 * MANHWA: a plate a title sits on: one of the hundred drawn frames (or none), whether its face is light (dark letters
 * on it), and the part of it the letters go in, as fractions of its width and height.
 */
data class TitlePlate(
    val key: String,
    val drawn: DrawnFrame?,
    val lightFace: Boolean,
    val left: Float = 0.14f,
    val right: Float = 0.86f,
    val top: Float = 0.28f,
    val bottom: Float = 0.72f,
    /** One of the hundred frames in the work's own colours, instead of [drawn]. */
    val energy: EnergyFrame? = null,
) {
    companion object {
        /** The hundred frames: every shape in every finish, shapes kept together. */
        val drawnFrames: List<TitlePlate> = FrameShape.entries.flatMap { shape ->
            FramePalette.entries.map { pal ->
                val f = DrawnFrame(shape, pal)
                TitlePlate(f.key, f, pal.lightFace, shape.left, shape.right, shape.top, shape.bottom)
            }
        }

        /** The hundred frames in the work's own colours (the cover's), ten families of ten. */
        val energyFrames: List<TitlePlate> = EnergyFrame.all.map { e ->
            val f = e.family
            TitlePlate(e.key, null, false, f.left, f.right, f.top, f.bottom, energy = e)
        }

        /** Every plate; the plain one ("NONE") stays last. */
        val all: List<TitlePlate> = drawnFrames + energyFrames + TitlePlate("NONE", null, false, left = 0.06f, right = 0.94f, top = 0.1f, bottom = 0.9f)

        /** The picture plates of earlier versions, now drawn: a work or song that had one keeps the nearest look. */
        private val legacy = mapOf(
            "THORN" to "F_THORN_SHADOW",
            "VIOLET" to "F_ROYAL_VIOLET",
            "CRYSTAL" to "F_CRYSTAL_SILVER",
            "GOLD" to "F_CRYSTAL_GOLD",
            "STAR1" to "F_CELESTIAL_VIOLET",
            "STAR2" to "F_CELESTIAL_ICE",
            "STAR3" to "F_ROYAL_PEARL",
            "STAR4" to "F_ROYAL_SUN",
            "SHIELD" to "F_BLADE_SILVER",
            "ANTLER" to "F_THORN_GOLD",
            "STONE" to "F_GOTHIC_SHADOW",
            "FLAME" to "F_FLAME_SUN",
            "ROYAL" to "F_GOTHIC_GOLD",
            "STORM" to "F_NEON_ICE",
        )

        private val byKey = all.associateBy { it.key }

        fun of(key: String?): TitlePlate? = key?.let { byKey[legacy[it] ?: it] }

        /** The frame a work gets until the reader picks one: the plain one (the reader dresses each work up). */
        @Suppress("UNUSED_PARAMETER")
        fun forWork(id: Long): TitlePlate = all.last()
    }
}

/** MANHWA: how a work's cover moves on its page. */
enum class CoverMotion(val label: StringResource) {
    FLOAT(ATMR.strings.motion_float),
    ORBIT(ATMR.strings.motion_orbit),
    PULSE(ATMR.strings.motion_pulse),
    SWING(ATMR.strings.motion_swing),
    STILL(ATMR.strings.motion_still),
    ;

    companion object {
        fun of(key: String?): CoverMotion = entries.firstOrNull { it.name == key } ?: FLOAT
    }
}

/**
 * MANHWA: a title on its plate, alive. A band of light sweeps across the plate (only over the plate itself, not the
 * air around it), the plate breathes a glow of its colour, and the letters, in the work's font, shine with a moving
 * band of light and sit as big as the plate allows. Frames in the work's own colours take them from its cover.
 *
 * Everything that moves is read while drawing, so the title is laid out once, not again on every frame. [still]
 * holds the plate at one moment (the many small previews of the look picker).
 */
@Composable
fun AnimatedPlate(
    text: String,
    plate: TitlePlate,
    font: FontFamily,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    maxFont: Int = 30,
    still: Boolean = false,
) {
    val cover = LocalCoverPalette.current
    val energyColors = remember(plate.energy, cover, accent) { plate.energy?.let { EnergyColors.of(cover, accent, it.metal) } }
    val autoColors = remember(cover, accent) { FrameColors.of(cover, accent) }
    val glowColor = when {
        energyColors != null -> energyColors.p
        plate.drawn?.palette == FramePalette.AUTO -> autoColors.glow
        else -> plate.drawn?.palette?.glow ?: accent
    }
    val motion = if (still) null else rememberInfiniteTransition(label = "plate")
    val sweep = motion?.animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweep")
    val breathe = motion?.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val shine = motion?.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "shine")
    val clock = motion?.animateFloat(0f, 10_000f, infiniteRepeatable(tween(10_000_000, easing = LinearEasing)), label = "clock")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                // Light added onto the page behind it, so it can only brighten (it used to darken pale pages)
                drawOval(
                    Brush.radialGradient(listOf(glowColor.copy(alpha = 0.3f * (breathe?.value ?: 0.55f)), Color.Transparent), radius = size.width * 0.5f),
                    topLeft = Offset(-size.width * 0.05f, -size.height * 0.2f),
                    size = androidx.compose.ui.geometry.Size(size.width * 1.1f, size.height * 1.4f),
                    blendMode = BlendMode.Plus,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val drawn = plate.drawn
        val energy = plate.energy
        if (drawn != null || energy != null) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        // The light only lands on the frame: drawn atop what is already there
                        val s = sweep?.value ?: return@drawWithContent
                        val x = size.width * s
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                                start = Offset(x - size.width * 0.18f, 0f),
                                end = Offset(x + size.width * 0.18f, size.height),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            ) {
                val t = clock?.value ?: STILL_TIME
                if (energy != null && energyColors != null) drawEnergyFrame(energy, energyColors, t) else if (drawn != null) drawFrame(drawn, t, autoColors)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.7f)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.06f), accent.copy(alpha = 0.18f), Color.White.copy(alpha = 0.06f))))
                    .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.5f), accent, Color.White.copy(alpha = 0.5f))), RoundedCornerShape(50)),
            )
        }
        val w = maxWidth
        val h = maxHeight
        val inkColor = if (energyColors != null) energyColors.p else accent
        val ink = remember(plate.lightFace, inkColor) {
            Brush.horizontalGradient(
                if (plate.lightFace) {
                    listOf(Color(0xFF14091F), inkColor.darken(0.55f), Color(0xFF14091F))
                } else {
                    listOf(Color.White, inkColor.lighten(0.35f), Color.White)
                },
            )
        }
        Box(
            Modifier
                .padding(start = w * plate.left, end = w * (1f - plate.right), top = h * plate.top, bottom = h * (1f - plate.bottom))
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = text,
                style = TextStyle(
                    fontFamily = font,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    brush = ink,
                    shadow = if (plate.lightFace) null else Shadow(inkColor.copy(alpha = 0.9f), blurRadius = 18f),
                ),
                maxLines = 2,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = maxFont.sp, stepSize = 1.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        // The letters shine: a band of light crosses them, drawn only on the glyphs
                        val s = shine?.value ?: return@drawWithContent
                        val x = size.width * s
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = if (plate.lightFace) 0.35f else 0.7f), Color.Transparent),
                                start = Offset(x - size.height, 0f),
                                end = Offset(x + size.height, size.height),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
        }
    }
}

/** The moment a still plate is shown at: gems lit, light half way round. */
private const val STILL_TIME = 1.7f

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

/**
 * MANHWA: the look of a work's title, picked by the reader: how the cover moves, the plate, and one of the fifty
 * fonts (each shown with the title itself).
 */
@Composable
fun TitleLookDialog(
    title: String,
    accent: Color,
    motion: CoverMotion,
    plate: TitlePlate,
    font: TitleFonts.Entry,
    onMotion: (CoverMotion) -> Unit,
    onPlate: (TitlePlate) -> Unit,
    onFont: (TitleFonts.Entry) -> Unit,
    coverEffect: String?,
    pageEffect: String?,
    effectColor: EffectColor,
    effectBeat: Boolean,
    onCoverEffect: (String) -> Unit,
    onPageEffect: (String) -> Unit,
    onEffectColor: (EffectColor) -> Unit,
    onEffectBeat: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val float by rememberInfiniteTransition(label = "look").animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "f")
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0B0B12), Color(0xFF050507))))
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MaterialSymbols.Rounded.Close,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onDismiss).padding(9.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(ATMR.strings.look_title), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            // The title as it will look
            AnimatedPlate(
                text = title,
                plate = plate,
                font = font.family,
                accent = accent,
                height = 110.dp,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .graphicsLayer { translationY = sin(float * 2 * PI).toFloat() * 4.dp.toPx() },
            )
            val arabicSample = stringResource(ATMR.strings.look_arabic_sample)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_motion), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CoverMotion.entries) { m ->
                            val on = m == motion
                            Text(
                                stringResource(m.label),
                                color = if (on) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (on) accent else Color.White.copy(alpha = 0.08f))
                                    .clickable { onMotion(m) }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                // The hundred frames: first the shape (in the finish picked), then the finish (in the shape picked)
                val current = plate.drawn
                val finish = current?.palette ?: FramePalette.AUTO
                val shapeNow = current?.shape
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_plate), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val choices = FrameShape.entries.mapNotNull { TitlePlate.of(DrawnFrame(it, finish).key) } + listOfNotNull(TitlePlate.of("NONE"))
                    PlateRow(choices, plate, title, font, accent, onPlate)
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_finish), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val finishes = listOf(FramePalette.AUTO) + FramePalette.entries.filter { it != FramePalette.AUTO }
                    val choices = finishes.mapNotNull { TitlePlate.of(DrawnFrame(shapeNow ?: FrameShape.ROYAL, it).key) }
                    PlateRow(choices, plate, title, font, accent, onPlate)
                }
                // MANHWA: the hundred frames in the work's own colours, a row for each family
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_energy), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(ATMR.strings.look_energy_sub), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                EnergyFamily.entries.forEach { family ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "energy_${family.name}") {
                        PlateRow(TitlePlate.energyFrames.filter { it.energy?.family == family }, plate, title, font, accent, onPlate)
                    }
                }
                // MANHWA effects: each choice shows itself, live
                val tint = effectColor.color ?: accent
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_effect_cover), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) { EffectRow(coverEffect, tint, accent, onCoverEffect) }
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_effect_page), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) { EffectRow(pageEffect, tint, accent, onPageEffect) }
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_effect_color), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(EffectColor.entries) { c ->
                            val on = c == effectColor
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        c.color?.let { Brush.radialGradient(listOf(Color.White, it, it.copy(alpha = 0.6f))) }
                                            ?: Brush.sweepGradient(listOf(accent, Color.White, accent)),
                                    )
                                    .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.2f), CircleShape)
                                    .clickable { onEffectColor(c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (c == EffectColor.AUTO) Text("A", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (effectBeat) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (effectBeat) accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .clickable { onEffectBeat(!effectBeat) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(ATMR.strings.look_effect_beat), color = Color.White, fontWeight = FontWeight.Bold)
                            Text(stringResource(ATMR.strings.look_effect_beat_sub), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        Box(
                            Modifier
                                .size(width = 46.dp, height = 26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (effectBeat) accent else Color.White.copy(alpha = 0.15f))
                                .padding(3.dp),
                            contentAlignment = if (effectBeat) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
                // Arabic fonts first for an Arabic title, the Latin ones first otherwise
                val arabicTitle = TitleFonts.isArabic(title)
                val groups = listOf(
                    ATMR.strings.look_font_arabic to TitleFonts.arabic,
                    ATMR.strings.look_font to TitleFonts.all,
                ).let { if (arabicTitle) it else it.reversed() }
                groups.forEach { (label, fonts) ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(label), accent) }
                    items(fonts, key = { it.key }) { f ->
                        FontTile(f, if (f.arabic && !arabicTitle) arabicSample else title, f.key == font.key, accent) { onFont(f) }
                    }
                }
            }
        }
    }
}

/** A font, shown with the title (or an Arabic sample for an Arabic font on a Latin title). */
@Composable
private fun FontTile(f: TitleFonts.Entry, sample: String, on: Boolean, accent: Color, onPick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
            .border(if (on) 2.dp else 1.dp, if (on) accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(onClick = onPick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            sample,
            style = TextStyle(fontFamily = f.family, color = Color.White, textAlign = TextAlign.Center),
            maxLines = 2,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 22.sp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
        Text(f.label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
    }
}

/** Frames side by side, each with the title on it. */
@Composable
private fun PlateRow(
    choices: List<TitlePlate>,
    current: TitlePlate,
    title: String,
    font: TitleFonts.Entry,
    accent: Color,
    onPlate: (TitlePlate) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(choices, key = { it.key }) { p ->
            val on = p.key == current.key
            Box(
                Modifier
                    .size(width = 170.dp, height = 78.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(if (on) 2.dp else 1.dp, if (on) accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                    .clickable { onPlate(p) }
                    .padding(4.dp),
            ) {
                AnimatedPlate(title, p, font.family, accent, height = 70.dp, maxFont = 16, still = !on)
            }
        }
    }
}

/**
 * The effects side by side, each one playing in its tile: none, each effect alone, "every day" (a new mix by itself
 * each day), and the fifty mixes.
 */
@Composable
private fun EffectRow(current: String?, tint: Color, accent: Color, onPick: (String) -> Unit) {
    val selected = current ?: WorkEffect.NONE.name
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(EffectChoice.keys, key = { it }) { key ->
            val on = key == selected
            val shown = remember(key) { EffectChoice.resolve(key, 0L) }
            Column(
                Modifier
                    .width(104.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .border(if (on) 2.dp else 1.dp, if (on) accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .clickable { onPick(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
                    EffectLayers(shown, tint, Modifier.fillMaxSize(), still = !on)
                }
                val label = when {
                    key == EffectChoice.DAILY -> stringResource(ATMR.strings.effect_daily)
                    key.startsWith("MIX_") -> {
                        val (a, b) = EffectChoice.mixes[key.removePrefix("MIX_").toInt()]
                        stringResource(a.label) + " + " + stringResource(b.label)
                    }
                    else -> stringResource(WorkEffect.of(key).label)
                }
                Text(
                    label,
                    color = if (on) accent else Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(text: String, accent: Color) {
    Text(text.uppercase(), color = accent, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}
