package eu.kanade.presentation.manga.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.components.CoverPalette
import eu.kanade.presentation.components.rememberCoverPalette
import eu.kanade.presentation.components.sharedCover
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.copyToClipboard
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** MANHWA: the palette of the work on screen, for the parts of its page that draw their own colours. */
val LocalCoverPalette = staticCompositionLocalOf<CoverPalette?> { null }

/**
 * MANHWA: the page of a work, in the colours of its cover. Material's accents (and the ManhwaKit buttons, which
 * follow them) turn to the cover's strongest colour, fading over from the app's until the cover is read.
 */
@Composable
fun CoverTheme(manga: Manga, content: @Composable () -> Unit) {
    CoverTheme(rememberCoverPalette(manga), content)
}

/** [CoverTheme] for any work whose cover palette is already read, such as a novel's. */
@Composable
fun CoverTheme(palette: CoverPalette?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val primary by animateColorAsState(palette?.primary ?: base.primary, tween(700), label = "primary")
    val secondary by animateColorAsState(palette?.secondary ?: base.secondary, tween(700), label = "secondary")
    val onAccent = if (primary.luminance() > 0.5f) Color.Black else Color.White
    val scheme = base.copy(
        primary = primary,
        onPrimary = onAccent,
        primaryContainer = primary.copy(alpha = 0.28f).compositeOver(base.surface),
        onPrimaryContainer = primary,
        secondary = secondary,
        onSecondary = if (secondary.luminance() > 0.5f) Color.Black else Color.White,
        secondaryContainer = secondary.copy(alpha = 0.24f).compositeOver(base.surface),
        tertiary = secondary,
    )
    CompositionLocalProvider(LocalCoverPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
    }
}

/**
 * MANHWA: the head of a work's page as an editorial poster. The cover, lit by its rank, stands in the middle of its
 * own blurred art; the title is set huge behind it in an outlined serif and stacked bold beside it; a white tag
 * names the source; the genres are pills; and two pills start reading. Film grain and slow light streaks sit over
 * it, all in the cover's colours. Scrolling pushes the art back slower than the page (parallax) and lifts the
 * cover away.
 */
@Composable
fun PosterHeader(
    manga: Manga,
    sourceName: String,
    chaptersRead: Int,
    chapterCount: Int,
    topPadding: Dp,
    scrollProvider: () -> Float,
    onCoverClick: () -> Unit,
    doSearch: (query: String, global: Boolean) -> Unit,
    onTagSearch: (String) -> Unit,
    onContinue: (() -> Unit)?,
    continueLabel: String,
    modifier: Modifier = Modifier,
    nextChapterHint: String? = null,
) {
    PosterHero(
        workId = manga.id,
        title = manga.title,
        author = manga.author,
        genres = manga.genre.orEmpty(),
        favorite = manga.favorite,
        coverData = manga,
        chaptersRead = chaptersRead,
        chapterCount = chapterCount,
        topPadding = topPadding,
        scrollProvider = scrollProvider,
        onCoverClick = onCoverClick,
        onAuthorClick = { doSearch(it, true) },
        onTagSearch = onTagSearch,
        onContinue = onContinue,
        continueLabel = continueLabel,
        modifier = modifier,
        coverModifier = Modifier.sharedCover(manga.id),
        nextChapterHint = nextChapterHint,
    )
}

/**
 * MANHWA: the poster head itself, for any work with a cover: a manga ([PosterHeader]) or a novel. [workId] keys the
 * work's look and music in [eu.kanade.tachiyomi.music.MusicStore]; [coverData] is anything Coil can load.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosterHero(
    workId: Long,
    title: String,
    author: String?,
    genres: List<String>,
    favorite: Boolean,
    coverData: Any?,
    chaptersRead: Int,
    chapterCount: Int,
    topPadding: Dp,
    scrollProvider: () -> Float,
    onCoverClick: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onTagSearch: (String) -> Unit,
    onContinue: (() -> Unit)?,
    continueLabel: String,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    nextChapterHint: String? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val palette = LocalCoverPalette.current
    val accent = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.secondary
    val deep by animateColorAsState(palette?.deep ?: Color(0xFF0B0B10), tween(700), label = "deep")
    val background = MaterialTheme.colorScheme.background

    // The parts come in one after another
    var shown by remember(workId) { mutableStateOf(false) }
    LaunchedEffect(workId) { shown = true }
    @Composable
    fun enter(order: Int): Float {
        val value by animateFloatAsState(
            if (shown) 1f else 0f,
            tween(650, delayMillis = 70 * order, easing = FastOutSlowInEasing),
            label = "enter$order",
        )
        return value
    }

    val motion = rememberInfiniteTransition(label = "poster")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val float by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "float")
    val streak by motion.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "streak")

    // MANHWA: the work's title look (frame, font) and how its cover moves, picked by the reader
    val lookStore = remember { eu.kanade.tachiyomi.music.MusicStore.get(context) }
    val look by remember(workId) { lookStore.lookFlow(workId) }.collectAsState(lookStore.work(workId))
    val titleFont = TitleFonts.of(look.titleFont) ?: TitleFonts.forWork(workId)
    val titlePlate = TitlePlate.of(look.titlePlate) ?: TitlePlate.forWork(workId)
    val coverMotion = CoverMotion.of(look.motion)
    val effects = rememberWorkEffects(workId)
    var lookOpen by remember { mutableStateOf(false) }
    if (lookOpen) {
        TitleLookDialog(
            title = title,
            accent = accent,
            motion = coverMotion,
            plate = titlePlate,
            font = titleFont,
            onMotion = { lookStore.setTitleLook(workId, motion = it.name) },
            onPlate = { lookStore.setTitleLook(workId, plate = it.key) },
            onFont = { lookStore.setTitleLook(workId, font = it.key) },
            coverEffect = effects.cover,
            pageEffect = effects.page,
            effectColor = EffectColor.of(look.effectColor),
            effectBeat = look.effectBeat,
            onCoverEffect = { lookStore.setEffects(workId, cover = it.name) },
            onPageEffect = { lookStore.setEffects(workId, page = it.name) },
            onEffectColor = { lookStore.setEffects(workId, color = it.name) },
            onEffectBeat = { lookStore.setEffects(workId, beat = it) },
            onDismiss = { lookOpen = false },
        )
    }

    val words = remember(title) { title.split(' ').filter { it.isNotBlank() } }
    val bigWord = remember(words) { (words.maxByOrNull { it.length } ?: title).uppercase().take(10) }

    Box(modifier.fillMaxWidth()) {
        // The cover's own art, blurred and pushed back
        AsyncImage(
            model = ImageRequest.Builder(context).data(coverData).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val s = scrollProvider()
                    translationY = s * 0.5f
                    scaleX = 1.18f
                    scaleY = 1.18f
                    alpha = 0.5f
                }
                .blur(26.dp),
        )
        // Colour wash: deep cover shade on top, fading into the page
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to deep.copy(alpha = 0.55f),
                        0.45f to deep.copy(alpha = 0.35f),
                        0.8f to deep.copy(alpha = 0.2f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        // Light streaks and film grain
        val grain = remember { List(700) { Offset(Random.nextFloat(), Random.nextFloat()) } }
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            for (i in 0 until 3) {
                val x = (streak + i * 0.37f) % 1.6f - 0.3f
                val start = Offset(w * x, -h * 0.05f)
                val end = Offset(w * (x - 0.35f), h * 0.75f)
                drawLine(
                    Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f - i * 0.025f), Color.Transparent), start, end),
                    start,
                    end,
                    strokeWidth = (26 - i * 7).dp.toPx(),
                )
            }
            grain.forEachIndexed { i, p ->
                drawCircle(
                    color = if (i % 5 == 0) accent.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.07f),
                    radius = if (i % 9 == 0) 1.6f else 0.9f,
                    center = Offset(p.x * w, p.y * h),
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = topPadding + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(370.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The title, huge, outlined, behind everything
                val back = enter(0)
                Text(
                    text = bigWord,
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Black,
                        fontSize = 108.sp,
                        letterSpacing = 4.sp,
                        drawStyle = Stroke(width = 2.4f),
                    ),
                    color = accent.copy(alpha = 0.55f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .graphicsLayer {
                            alpha = back * (1f - scrollProvider() / 700f).coerceIn(0f, 1f)
                            translationX = -40.dp.toPx() * drift - (1 - back) * 60.dp.toPx()
                            translationY = scrollProvider() * 0.3f
                        }
                        .padding(top = 18.dp, start = 8.dp),
                )
                // MANHWA music: while this work's song plays, bars dance in a ring round the cover
                val music by eu.kanade.tachiyomi.music.MusicPlayer.state.collectAsState()
                val beatOn = music.playing && music.mangaId == workId
                val beatLevel by animateFloatAsState(if (beatOn) 1f else 0f, tween(600), label = "beat")
                val beat by motion.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "beatClock")
                // MANHWA: the effect picked for this work, round and behind the cover
                EffectLayer(
                    effects.cover,
                    effects.color ?: accent,
                    Modifier.fillMaxSize().graphicsLayer { alpha = back },
                    beat = effects.beat,
                )
                if (beatLevel > 0.01f) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2
                        val cy = size.height / 2
                        val rx = size.width * 0.54f * 0.5f + 18.dp.toPx()
                        val ry = rx * 1.45f
                        val bars = 72
                        for (i in 0 until bars) {
                            val a = (i.toFloat() / bars) * 2 * PI.toFloat()
                            // Several waves at once read like music: a slow swell, a beat and a flicker
                            val v = (0.5f + 0.5f * sin(beat * 4.1f + i * 0.45f)) * 0.55f +
                                (0.5f + 0.5f * sin(beat * 9.7f + i * 1.3f)) * 0.3f +
                                (0.5f + 0.5f * sin(beat * 2.3f + i * 0.11f)) * 0.15f
                            // The bars follow the song's real beat too
                            val len = (6.dp.toPx() + 26.dp.toPx() * v * (0.45f + 0.9f * effects.rawBeat())) * beatLevel
                            val dx = kotlin.math.cos(a)
                            val dy = sin(a)
                            val start = Offset(cx + dx * rx, cy + dy * ry)
                            val end = Offset(cx + dx * (rx + len), cy + dy * (ry + len))
                            drawLine(
                                Brush.linearGradient(listOf(accent.copy(alpha = 0.9f * beatLevel), Color.White.copy(alpha = 0.8f * beatLevel)), start, end),
                                start,
                                end,
                                strokeWidth = 3.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                        }
                    }
                }
                // The cover, lit by its rank, floating gently
                val coverIn = enter(2)
                Box(
                    Modifier
                        .fillMaxWidth(0.54f)
                        .graphicsLayer {
                            val s = scrollProvider()
                            val wave = sin(float * 2 * PI).toFloat()
                            val wave2 = kotlin.math.cos(float * 2 * PI).toFloat()
                            var scale = 0.92f + 0.08f * coverIn - (s / 4000f).coerceIn(0f, 0.12f)
                            translationY = s * 0.22f + (1 - coverIn) * 40.dp.toPx()
                            cameraDistance = 14 * density
                            // MANHWA: the cover moves the way picked for this work
                            when (coverMotion) {
                                CoverMotion.FLOAT -> {
                                    translationY += wave * 5.dp.toPx()
                                    rotationY = wave * 3.5f
                                    rotationZ = -1.2f + wave * 0.4f
                                }
                                CoverMotion.ORBIT -> {
                                    rotationY = wave * 16f
                                    rotationX = wave2 * 8f
                                    translationY += wave2 * 3.dp.toPx()
                                }
                                CoverMotion.PULSE -> {
                                    val p = kotlin.math.abs(sin(float * 4 * PI).toFloat())
                                    scale *= 1f + 0.035f * p
                                }
                                CoverMotion.SWING -> {
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                                    rotationZ = wave * 5f
                                }
                                CoverMotion.STILL -> Unit
                            }
                            alpha = coverIn * (1f - (s / 1400f)).coerceIn(0f, 1f)
                            scaleX = scale
                            scaleY = scale
                        },
                ) {
                    CoverAura(saved = favorite, chaptersRead = chaptersRead) {
                        Box {
                            MangaCover.Book(
                                data = ImageRequest.Builder(context).data(coverData).crossfade(true).build(),
                                contentDescription = stringResource(MR.strings.manga_cover),
                                shape = RoundedCornerShape(10.dp),
                                onClick = onCoverClick,
                                modifier = Modifier
                                    .then(coverModifier)
                                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                            )
                            // A glossy sweep over the cover
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        Brush.linearGradient(
                                            0f to Color.Transparent,
                                            (0.35f + 0.3f * float) to Color.White.copy(alpha = 0.10f),
                                            (0.45f + 0.3f * float) to Color.Transparent,
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }

            // Title and author
            val head = enter(5)
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .graphicsLayer {
                        alpha = head
                        translationY = (1 - head) * 24.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // MANHWA: the title on its frame, in its font; a tap picks how it looks and how the cover moves
                AnimatedPlate(
                    text = title.ifBlank { stringResource(MR.strings.unknown_title) },
                    plate = titlePlate,
                    font = titleFont.family,
                    accent = accent,
                    height = 104.dp,
                    modifier = Modifier.combinedClickable(
                        onClick = { lookOpen = true },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            context.copyToClipboard(title, title)
                        },
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = (author?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.unknown_author)).uppercase(),
                    style = TextStyle(fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold),
                    color = accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        author?.takeIf { it.isNotBlank() }?.let(onAuthorClick)
                    },
                )
            }

            // When the next chapter is due, from the rhythm of the last ones
            if (nextChapterHint != null) {
                Text(
                    text = nextChapterHint,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .graphicsLayer { alpha = head }
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }

            // Genres as pills
            val shownGenres = genres.filter { it.isNotBlank() }.take(5)
            if (shownGenres.isNotEmpty()) {
                val pills = enter(6)
                FlowRow(
                    Modifier
                        .padding(top = 14.dp, start = 16.dp, end = 16.dp)
                        .graphicsLayer { alpha = pills },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shownGenres.forEach { genre ->
                        Text(
                            text = "#$genre",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(50))
                                .clickable { onTagSearch(genre) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            // Two pills: how far you are, and on to the next page
            val pills = enter(7)
            Row(
                Modifier
                    .padding(top = 18.dp, bottom = 10.dp)
                    .graphicsLayer {
                        alpha = pills
                        translationY = (1 - pills) * 20.dp.toPx()
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(ATMR.strings.poster_progress, chaptersRead, chapterCount).uppercase(),
                    color = Color.White,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(deep, accent.copy(alpha = 0.55f).compositeOver(deep))))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                )
                if (onContinue != null) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.5.dp, Brush.horizontalGradient(listOf(Color.White, second)), RoundedCornerShape(50))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onContinue()
                            }
                            .padding(start = 18.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = continueLabel.uppercase(),
                            color = Color.White,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Icon(MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(status: Long): String = when (status) {
    SManga.ONGOING.toLong() -> stringResource(MR.strings.ongoing)
    SManga.COMPLETED.toLong() -> stringResource(MR.strings.completed)
    SManga.LICENSED.toLong() -> stringResource(MR.strings.licensed)
    SManga.PUBLISHING_FINISHED.toLong() -> stringResource(MR.strings.publishing_finished)
    SManga.CANCELLED.toLong() -> stringResource(MR.strings.cancelled)
    SManga.ON_HIATUS.toLong() -> stringResource(MR.strings.on_hiatus)
    else -> stringResource(MR.strings.unknown)
}
