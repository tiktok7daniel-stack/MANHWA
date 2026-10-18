package eu.kanade.tachiyomi.ui.home.wrapped

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import coil3.request.allowHardware
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.roundedfilled.Bolt
import mihon.icons.materialsymbols.roundedfilled.Star
import mihon.icons.materialsymbols.roundedfilled.Whatshot
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Builds the reader's year in reading from the per-chapter reading history of the library. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class WrappedViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    data class TopSeries(val title: String, val cover: MangaCover, val chapters: Int)

    data class Wrapped(
        val year: Int,
        val chapters: Int,
        val series: Int,
        val minutes: Long,
        val activeDays: Int,
        val longestStreak: Int,
        val topGenres: List<Pair<String, Int>>,
        val topSeries: List<TopSeries>,
        val nightOwl: Boolean,
    )

    private val _state = MutableStateFlow<Wrapped?>(null)
    val state = _state.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { build() }
                .onSuccess { _state.value = it }
                .onFailure {
                    logcat(LogPriority.ERROR, it)
                    _failed.value = true
                }
        }
    }

    private suspend fun build(): Wrapped {
        val zone = ZoneId.systemDefault()
        val year = LocalDate.now(zone).year
        val days = sortedSetOf<LocalDate>()
        val genres = HashMap<String, Int>()
        val top = mutableListOf<TopSeries>()
        var chapters = 0
        var series = 0
        var durationMs = 0L
        var night = 0
        getLibraryManga.await().forEach { item ->
            val reads = historyRepository.getHistoryByMangaId(item.manga.id).filter { history ->
                val at = history.readAt ?: return@filter false
                Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(zone).year == year
            }
            if (reads.isEmpty()) return@forEach
            series++
            chapters += reads.size
            reads.forEach { history ->
                val time = Instant.ofEpochMilli(history.readAt!!.toEpochMilliseconds()).atZone(zone)
                days += time.toLocalDate()
                if (time.hour >= 22 || time.hour < 5) night++
                durationMs += history.readDuration
            }
            item.manga.genre.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.forEach { genre ->
                genres[genre] = (genres[genre] ?: 0) + reads.size
            }
            top += TopSeries(item.manga.title, item.manga.asMangaCover(), reads.size)
        }
        var longest = 0
        var current = 0
        var previous: LocalDate? = null
        days.forEach { day ->
            current = if (previous?.plusDays(1) == day) current + 1 else 1
            longest = maxOf(longest, current)
            previous = day
        }
        return Wrapped(
            year = year,
            chapters = chapters,
            series = series,
            minutes = durationMs / 60_000,
            activeDays = days.size,
            longestStreak = longest,
            topGenres = genres.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
            topSeries = top.sortedByDescending { it.chapters }.take(3),
            nightOwl = chapters > 0 && night * 2 > chapters,
        )
    }
}

/** A Spotify Wrapped style recap of the year, shareable as an image. */
class WrappedScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val viewModel = metroViewModel<WrappedViewModel>()
        val wrapped by viewModel.state.collectAsState()
        val layer = rememberGraphicsLayer()

        GlassBackdrop {
            Column(modifier = Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.wrapped_title),
                    subtitle = wrapped?.year?.toString(),
                    onBack = navigator::pop,
                )
                val data = wrapped
                if (data == null) {
                    val failed by viewModel.failed.collectAsState()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (failed) {
                            Text(stringResource(ATMR.strings.recs_failed), color = ManhwaInk.text.copy(alpha = 0.7f))
                        } else {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                    return@Column
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    // Everything inside this box is what gets shared as the picture
                    Box(
                        modifier = Modifier.drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        },
                    ) {
                        Poster(data)
                    }
                    Spacer(Modifier.height(16.dp))
                }
                ManhwaKit.NeonButton(
                    text = stringResource(ATMR.strings.wrapped_share),
                    icon = MaterialSymbols.Rounded.Share,
                    modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth(),
                ) {
                    scope.launch {
                        runCatching {
                            val bitmap = layer.toImageBitmap().asAndroidBitmap()
                            val file = withContext(Dispatchers.IO) {
                                File(context.cacheDir, "manhwa_wrapped_${data.year}.png").also { out ->
                                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                }
                            }
                            context.startActivity(file.getUriCompat(context).toShareIntent(context, "image/png"))
                        }.onFailure { logcat(LogPriority.ERROR, it) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Poster(data: WrappedViewModel.Wrapped) {
        val colors = MaterialTheme.colorScheme
        val spin by rememberInfiniteTransition(label = "wrapped").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)),
            label = "spin",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF16060A), ManhwaKit.Ink)))
                .border(1.dp, Brush.linearGradient(listOf(ManhwaInk.text.copy(alpha = 0.3f), colors.primary)), RoundedCornerShape(32.dp))
                .padding(20.dp),
        ) {
            // Hero number
            Box(modifier = Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(210.dp)
                        .graphicsLayer { rotationZ = spin }
                        .background(
                            Brush.sweepGradient(listOf(colors.primary, Color.Transparent, Color(0xFF9B5CFF), Color.Transparent, colors.primary)),
                            CircleShape,
                        ),
                )
                Box(Modifier.size(196.dp).background(ManhwaKit.Ink, CircleShape))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CountUp(data.chapters, MaterialTheme.typography.displayMedium.fontSize.value.toInt() + 8)
                    Text(
                        stringResource(ATMR.strings.wrapped_chapters),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                    )
                }
            }
            Text(
                "MANHWA ${data.year}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp,
                color = ManhwaInk.text.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            // Bento grid
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(
                    icon = MaterialSymbols.RoundedFilled.Whatshot,
                    value = "${data.longestStreak}",
                    label = stringResource(ATMR.strings.wrapped_streak),
                    modifier = Modifier.weight(1.2f),
                    hot = true,
                )
                Tile(MaterialSymbols.Rounded.Schedule, "${data.minutes / 60}h", stringResource(ATMR.strings.wrapped_time), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(MaterialSymbols.RoundedFilled.Star, "${data.series}", stringResource(ATMR.strings.wrapped_series), Modifier.weight(1f))
                Tile(MaterialSymbols.RoundedFilled.Bolt, "${data.activeDays}", stringResource(ATMR.strings.wrapped_days), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            // Top genre
            val topGenre = data.topGenres.firstOrNull()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .neonGlow(RoundedCornerShape(24.dp), colors.primary, 0.2f, 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(colors.primary, lerp(colors.primary, Color(0xFF2A0610), 0.6f))))
                    .padding(18.dp),
            ) {
                Text(
                    stringResource(ATMR.strings.wrapped_top_genre),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Text(
                    topGenre?.first ?: "—",
                    style = MaterialTheme.typography.headlineMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.4f), blurRadius = 12f)),
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (data.topGenres.size > 1) {
                    Text(
                        data.topGenres.drop(1).joinToString("  •  ") { it.first },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }
            if (data.topSeries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(ATMR.strings.wrapped_top_series),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = ManhwaInk.text,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    data.topSeries.forEachIndexed { index, item ->
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, if (index == 0) colors.primary else ManhwaInk.text.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                            ) {
                                AsyncImage(
                                    // Software bitmaps, so the poster can be drawn into the shared picture
                                    model = coil3.request.ImageRequest.Builder(LocalContext.current)
                                        .data(item.cover)
                                        .allowHardware(false)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    placeholder = ColorPainter(Color(0x1F888888)),
                                    modifier = Modifier.matchParentSize(),
                                )
                                Text(
                                    "#${index + 1}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = ManhwaInk.text,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .background(if (index == 0) colors.primary else ManhwaKit.Ink.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                item.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = ManhwaInk.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                stringResource(ATMR.strings.src_count_chapters, item.chapters),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                            )
                        }
                    }
                    repeat(3 - data.topSeries.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(if (data.nightOwl) ATMR.strings.wrapped_night else ATMR.strings.wrapped_day),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = ManhwaInk.text.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun CountUp(target: Int, sizeSp: Int) {
        val value = remember { Animatable(0f) }
        LaunchedEffect(target) { value.animateTo(target.toFloat(), tween(1_600, easing = FastOutSlowInEasing)) }
        Text(
            "${value.value.toInt()}",
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Black,
            color = ManhwaInk.text,
            style = MaterialTheme.typography.displayMedium.copy(
                shadow = Shadow(MaterialTheme.colorScheme.primary, blurRadius = 30f),
            ),
        )
    }

    @Composable
    private fun Tile(icon: ImageVector, value: String, label: String, modifier: Modifier, hot: Boolean = false) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = modifier
                .glass(RoundedCornerShape(22.dp), accent = hot, alpha = 0.45f)
                .padding(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = ManhwaInk.text)
            Text(label, style = MaterialTheme.typography.labelSmall, color = ManhwaInk.text.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}
