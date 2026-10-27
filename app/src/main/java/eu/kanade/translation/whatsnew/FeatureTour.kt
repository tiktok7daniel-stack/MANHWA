package eu.kanade.translation.whatsnew

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.rounded.Headphones
import mihon.icons.materialsymbols.rounded.LocalFireDepartment
import mihon.icons.materialsymbols.rounded.Psychology
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Bedtime
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.TouchApp
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.AutoStories
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One stop of the tour: what it is, and the steps to use it. */
data class TourStop(
    val icon: ImageVector,
    val title: StringResource,
    val body: StringResource,
    val steps: List<StringResource>,
    /** The tour version that brought this stop: a reader who saw an older tour sees only the newer stops. */
    val since: Int = 1,
)

/**
 * MANHWA: a full-screen guided tour of a big update, shown once. Each stop has a glowing emblem orbited by sparks,
 * what the feature is, and numbered steps to use it; the pages slide with parallax, the dots stretch to the one on
 * screen, and the last button starts the app. It can be replayed from the home menu.
 */
object FeatureTour {

    private const val PREFS = "manhwa_tour"

    /** Raised whenever the tour gets new stops, so it shows again once. */
    private const val VERSION = 3

    fun shouldShow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("seen", 0) < VERSION

    /** The stops not seen yet: all of them on a first tour, only the newer ones after. */
    fun unseen(context: Context): List<TourStop> {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("seen", 0)
        return if (seen == 0) stops else stops.filter { it.since > seen }.ifEmpty { stops }
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("seen", VERSION).apply()
    }

    val stops: List<TourStop>
        get() = listOf(
            TourStop(MaterialSymbols.Rounded.AutoAwesome, ATMR.strings.tour_welcome_t, ATMR.strings.tour_welcome_b, emptyList()),
            TourStop(
                MaterialSymbols.Rounded.Image,
                ATMR.strings.tour_poster_t,
                ATMR.strings.tour_poster_b,
                listOf(ATMR.strings.tour_poster_s1, ATMR.strings.tour_poster_s2, ATMR.strings.tour_poster_s3),
            ),
            TourStop(
                MaterialSymbols.Rounded.Palette,
                ATMR.strings.tour_colors_t,
                ATMR.strings.tour_colors_b,
                listOf(ATMR.strings.tour_colors_s1, ATMR.strings.tour_colors_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.LocalLibrary,
                ATMR.strings.tour_shelf_t,
                ATMR.strings.tour_shelf_b,
                listOf(ATMR.strings.tour_shelf_s1, ATMR.strings.tour_shelf_s2, ATMR.strings.tour_shelf_s3),
            ),
            TourStop(
                MaterialSymbols.Rounded.DoneAll,
                ATMR.strings.tour_end_t,
                ATMR.strings.tour_end_b,
                listOf(ATMR.strings.tour_end_s1, ATMR.strings.tour_end_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.TouchApp,
                ATMR.strings.tour_haptics_t,
                ATMR.strings.tour_haptics_b,
                listOf(ATMR.strings.tour_haptics_s1),
            ),
            TourStop(
                MaterialSymbols.Rounded.Bedtime,
                ATMR.strings.tour_amoled_t,
                ATMR.strings.tour_amoled_b,
                listOf(ATMR.strings.tour_amoled_s1, ATMR.strings.tour_amoled_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.SkipNext,
                ATMR.strings.tour_credits_t,
                ATMR.strings.tour_credits_b,
                listOf(ATMR.strings.tour_credits_s1, ATMR.strings.tour_credits_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.EditNote,
                ATMR.strings.tour_notes_t,
                ATMR.strings.tour_notes_b,
                listOf(ATMR.strings.tour_notes_s1, ATMR.strings.tour_notes_s2, ATMR.strings.tour_notes_s3),
            ),
            TourStop(
                MaterialSymbols.Rounded.Refresh,
                ATMR.strings.tour_refresh_t,
                ATMR.strings.tour_refresh_b,
                listOf(ATMR.strings.tour_refresh_s1, ATMR.strings.tour_refresh_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.Group,
                ATMR.strings.tour_storymap_t,
                ATMR.strings.tour_storymap_b,
                listOf(ATMR.strings.tour_storymap_s1, ATMR.strings.tour_storymap_s2, ATMR.strings.tour_storymap_s3),
            ),
            TourStop(
                MaterialSymbols.Rounded.Search,
                ATMR.strings.tour_who_t,
                ATMR.strings.tour_who_b,
                listOf(ATMR.strings.tour_who_s1, ATMR.strings.tour_who_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.Schedule,
                ATMR.strings.tour_schedule_t,
                ATMR.strings.tour_schedule_b,
                listOf(ATMR.strings.tour_schedule_s1, ATMR.strings.tour_schedule_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.Psychology,
                ATMR.strings.tour_mood_t,
                ATMR.strings.tour_mood_b,
                listOf(ATMR.strings.tour_mood_s1, ATMR.strings.tour_mood_s2),
            ),
            TourStop(
                MaterialSymbols.Rounded.AutoAwesome,
                ATMR.strings.tour_similar_t,
                ATMR.strings.tour_similar_b,
                listOf(ATMR.strings.tour_similar_s1),
            ),
            TourStop(
                MaterialSymbols.Rounded.LocalFireDepartment,
                ATMR.strings.tour_cinema_t,
                ATMR.strings.tour_cinema_b,
                listOf(ATMR.strings.tour_cinema_s1, ATMR.strings.tour_cinema_s2, ATMR.strings.tour_cinema_s3),
            ),
            TourStop(
                MaterialSymbols.Rounded.Headphones,
                ATMR.strings.tour_voices_t,
                ATMR.strings.tour_voices_b,
                listOf(ATMR.strings.tour_voices_s1),
            ),
            TourStop(
                MaterialSymbols.Rounded.Headphones,
                ATMR.strings.tour_music_t,
                ATMR.strings.tour_music_b,
                listOf(ATMR.strings.tour_music_s1, ATMR.strings.tour_music_s2, ATMR.strings.tour_music_s3, ATMR.strings.tour_music_s4),
                since = 2,
            ),
            TourStop(
                MaterialSymbols.Rounded.Image,
                ATMR.strings.tour_scene_t,
                ATMR.strings.tour_scene_b,
                listOf(ATMR.strings.tour_scene_s1),
                since = 2,
            ),
            TourStop(
                MaterialSymbols.Rounded.Palette,
                ATMR.strings.tour_titlelook_t,
                ATMR.strings.tour_titlelook_b,
                listOf(ATMR.strings.tour_titlelook_s1, ATMR.strings.tour_titlelook_s2, ATMR.strings.tour_titlelook_s3, ATMR.strings.tour_titlelook_s4),
                since = 3,
            ),
            TourStop(
                MaterialSymbols.Rounded.AutoStories,
                ATMR.strings.tour_novels_t,
                ATMR.strings.tour_novels_b,
                listOf(ATMR.strings.tour_novels_s1, ATMR.strings.tour_novels_s2),
                since = 3,
            ),
            TourStop(
                MaterialSymbols.Rounded.Visibility,
                ATMR.strings.tour_spoilers_t,
                ATMR.strings.tour_spoilers_b,
                listOf(ATMR.strings.tour_spoilers_s1, ATMR.strings.tour_spoilers_s2),
            ),
        )
}

@Composable
fun FeatureTourDialog(onDismiss: () -> Unit, onlyNew: Boolean = false) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val stops = androidx.compose.runtime.remember { if (onlyNew) FeatureTour.unseen(context) else FeatureTour.stops }
        FeatureTourContent(stops, onDismiss)
    }
}

@Composable
private fun FeatureTourContent(stops: List<TourStop>, onDismiss: () -> Unit) {
    val pager = rememberPagerState { stops.size }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070A)),
    ) {
        TourBackdrop(pager, accent)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${pager.currentPage + 1} / ${stops.size}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(ATMR.strings.tour_skip),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                TourPage(stops[page], offset, accent)
            }
            // The dots: the one on screen stretches into a bar
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                stops.indices.forEach { i ->
                    val on = i == pager.currentPage
                    val width by animateDpAsState(if (on) 26.dp else 7.dp, label = "dot")
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(7.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(if (on) accent else Color.White.copy(alpha = 0.22f)),
                    )
                }
            }
            val last = pager.currentPage == stops.lastIndex
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 18.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.7f))))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        if (last) onDismiss() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (last) ATMR.strings.tour_start else ATMR.strings.tour_next),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

/** Two slow blobs of the accent colour that drift with the pages. */
@Composable
private fun TourBackdrop(pager: PagerState, accent: Color) {
    val drift by rememberInfiniteTransition(label = "tour").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "drift",
    )
    Canvas(Modifier.fillMaxSize()) {
        val p = pager.currentPage + pager.currentPageOffsetFraction
        val a = drift * 2 * PI.toFloat()
        val c1 = Offset(size.width * (0.2f + 0.15f * sin(a)) - p * 40f, size.height * (0.25f + 0.08f * cos(a)))
        val c2 = Offset(size.width * (0.85f + 0.1f * cos(a)) - p * 70f, size.height * (0.7f + 0.1f * sin(a)))
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), c1, size.width * 0.7f), size.width * 0.7f, c1)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7C4DFF).copy(alpha = 0.22f), Color.Transparent), c2, size.width * 0.6f), size.width * 0.6f, c2)
    }
}

@Composable
private fun TourPage(stop: TourStop, offset: Float, accent: Color) {
    val spin by rememberInfiniteTransition(label = "orbit").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "spin",
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .graphicsLayer {
                alpha = 1f - abs(offset).coerceIn(0f, 1f) * 0.7f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        // The emblem: a glowing orb with sparks orbiting it, moving faster than the text (parallax)
        Box(
            Modifier
                .size(190.dp)
                .graphicsLayer {
                    translationX = offset * 220.dp.toPx()
                    val s = 1f - abs(offset).coerceIn(0f, 1f) * 0.3f
                    scaleX = s
                    scaleY = s
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.5f), Color.Transparent), center, r), r)
                for (i in 0 until 6) {
                    val a = (spin + i / 6f) * 2 * PI.toFloat()
                    val ring = r * (0.62f + 0.12f * (i % 2))
                    drawCircle(
                        Color.White.copy(alpha = 0.85f - i * 0.1f),
                        radius = (3.5f - i * 0.4f).dp.toPx(),
                        center = Offset(center.x + cos(a) * ring, center.y + sin(a) * ring * 0.55f),
                    )
                }
            }
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f))))
                    .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(stop.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(stop.title),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { translationX = offset * 90.dp.toPx() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(stop.body),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer { translationX = offset * 60.dp.toPx() },
        )
        if (stop.steps.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(ATMR.strings.tour_how),
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                stop.steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(step),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
