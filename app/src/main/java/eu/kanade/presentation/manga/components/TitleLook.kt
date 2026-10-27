package eu.kanade.presentation.manga.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import eu.kanade.tachiyomi.R
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.PI
import kotlin.math.sin

/**
 * MANHWA: a plate a title sits on: its picture, whether its face is light (dark letters on it), and the part of it
 * the letters go in, as fractions of its width and height.
 */
data class TitlePlate(
    val key: String,
    @DrawableRes val res: Int?,
    val lightFace: Boolean,
    val left: Float = 0.14f,
    val right: Float = 0.86f,
    val top: Float = 0.28f,
    val bottom: Float = 0.72f,
) {
    companion object {
        val all = listOf(
            TitlePlate("THORN", R.drawable.music_plate_thorn, true),
            TitlePlate("VIOLET", R.drawable.music_plate_violet, true),
            TitlePlate("CRYSTAL", R.drawable.music_plate_crystal, true, left = 0.06f, right = 0.78f),
            TitlePlate("GOLD", R.drawable.music_plate_gold, false, left = 0.08f, right = 0.92f, top = 0.42f, bottom = 0.86f),
            TitlePlate("STAR1", R.drawable.music_plate_star1, false, left = 0.08f, right = 0.92f, top = 0.2f, bottom = 0.8f),
            TitlePlate("STAR2", R.drawable.music_plate_star2, true, left = 0.08f, right = 0.92f, top = 0.2f, bottom = 0.85f),
            TitlePlate("STAR3", R.drawable.music_plate_star3, true, left = 0.08f, right = 0.92f, top = 0.18f, bottom = 0.82f),
            TitlePlate("STAR4", R.drawable.music_plate_star4, true, left = 0.08f, right = 0.92f, top = 0.25f, bottom = 0.75f),
            TitlePlate("SHIELD", R.drawable.music_plate_shield, false, left = 0.36f, right = 0.92f, top = 0.2f, bottom = 0.8f),
            TitlePlate("ANTLER", R.drawable.music_plate_antler, false, left = 0.16f, right = 0.84f, top = 0.25f, bottom = 0.78f),
            TitlePlate("STONE", R.drawable.music_plate_stone, false, left = 0.1f, right = 0.9f, top = 0.18f, bottom = 0.82f),
            TitlePlate("FLAME", R.drawable.music_plate_flame, true, left = 0.12f, right = 0.88f, top = 0.34f, bottom = 0.66f),
            TitlePlate("ROYAL", R.drawable.music_plate_royal, true, left = 0.15f, right = 0.85f, top = 0.3f, bottom = 0.7f),
            TitlePlate("STORM", R.drawable.music_plate_storm, false, left = 0.14f, right = 0.86f, top = 0.3f, bottom = 0.7f),
            TitlePlate("NONE", null, false, left = 0.06f, right = 0.94f, top = 0.1f, bottom = 0.9f),
        )

        fun of(key: String?): TitlePlate? = all.firstOrNull { it.key == key }

        /** The plate a work gets until one is picked, different from work to work. */
        fun forWork(id: Long): TitlePlate {
            val withPicture = all.filter { it.res != null }
            return withPicture[(((id / 7) % withPicture.size + withPicture.size) % withPicture.size).toInt()]
        }
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
 * air around it), the plate breathes a glow of the accent colour, and the letters, in the work's font, shine with a
 * moving gradient and sit as big as the plate allows.
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
) {
    val motion = rememberInfiniteTransition(label = "plate")
    val sweep by motion.animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweep")
    val breathe by motion.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val shine by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "shine")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawOval(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.45f * breathe), Color.Transparent), radius = size.width * 0.55f),
                    topLeft = Offset(-size.width * 0.05f, -size.height * 0.2f),
                    size = androidx.compose.ui.geometry.Size(size.width * 1.1f, size.height * 1.4f),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (plate.res != null) {
            Image(
                painterResource(plate.res),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        // The light only lands on the plate: drawn atop what is already there
                        val x = size.width * sweep
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                start = Offset(x - size.width * 0.18f, 0f),
                                end = Offset(x + size.width * 0.18f, size.height),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
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
        val ink = if (plate.lightFace) {
            listOf(Color(0xFF14091F), accent.copy(alpha = 1f).darken(0.55f), Color(0xFF14091F))
        } else {
            listOf(Color.White, accent.lighten(0.35f), Color.White)
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
                    brush = Brush.linearGradient(
                        ink,
                        start = Offset(-400f + 800f * shine, 0f),
                        end = Offset(400f + 800f * shine, 120f),
                    ),
                    shadow = if (plate.lightFace) null else Shadow(accent.copy(alpha = 0.9f), blurRadius = 18f),
                ),
                maxLines = 2,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = maxFont.sp, stepSize = 1.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

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
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_plate), accent) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(TitlePlate.all, key = { it.key }) { p ->
                            Box(
                                Modifier
                                    .size(width = 150.dp, height = 70.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(if (p == plate) 2.dp else 1.dp, if (p == plate) accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                                    .clickable { onPlate(p) }
                                    .padding(4.dp),
                            ) {
                                AnimatedPlate(title, p, font.family, accent, height = 62.dp, maxFont = 16)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.look_font), accent) }
                items(TitleFonts.all, key = { it.key }) { f ->
                    val on = f.key == font.key
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                            .border(if (on) 2.dp else 1.dp, if (on) accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .clickable { onFont(f) }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BasicText(
                            title,
                            style = TextStyle(fontFamily = f.family, color = Color.White, textAlign = TextAlign.Center),
                            maxLines = 2,
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 22.sp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        )
                        Text(f.label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String, accent: Color) {
    Text(text.uppercase(), color = accent, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}
