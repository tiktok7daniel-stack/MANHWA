package eu.kanade.tachiyomi.ui.home.schedule

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * MANHWA: the week of the library, the way Webtoon lays out its days. Each work's day comes from when its chapters
 * have been coming out; the works past their day are marked late, and the ones with the most unread chapters
 * (where you are furthest behind, as Kenmei counts it) are listed under them.
 */
class ScheduleScreen : Screen() {

    private data class Entry(val manga: Manga, val rhythm: ReleaseRhythm.Rhythm?, val unread: Int)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val zone = remember { ZoneId.systemDefault() }
        val today = remember { LocalDate.now(zone) }
        var day by remember { mutableStateOf(today.dayOfWeek) }
        val entries by produceState<List<Entry>?>(null) {
            value = withContext(Dispatchers.IO) {
                val graph = context.appGraph
                val now = System.currentTimeMillis()
                val favorites = runCatching { graph.getFavorites.await() }.getOrDefault(emptyList())
                val gate = Semaphore(6)
                coroutineScope {
                    favorites.map { manga ->
                        async {
                            gate.withPermit {
                                val chapters = runCatching { graph.getChaptersByMangaId.await(manga.id) }.getOrDefault(emptyList())
                                Entry(
                                    manga = manga,
                                    rhythm = ReleaseRhythm.of(chapters.mapNotNull { it.dateUpload?.toEpochMilliseconds() }, now, zone),
                                    unread = chapters.count { !it.read },
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.schedule_title),
                    subtitle = stringResource(ATMR.strings.schedule_subtitle),
                    onBack = navigator::pop,
                )
                val list = entries
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val week = remember(today) { (0..6).map { today.plusDays(it.toLong()).dayOfWeek } }
                fun dueOn(d: DayOfWeek) = list.filter { e ->
                    val r = e.rhythm ?: return@filter false
                    if (r.lateDays > 0) return@filter false
                    r.weekday?.let { it == d } ?: (Instant.ofEpochMilli(r.nextAt).atZone(zone).toLocalDate().let { due -> !due.isAfter(today.plusDays(6)) && due.dayOfWeek == d })
                }
                val late = list.filter { (it.rhythm?.lateDays ?: 0) > 0 }.sortedBy { it.rhythm?.lateDays }
                val behind = list.filter { it.unread > 0 }.sortedByDescending { it.unread }.take(20)
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(week) { d ->
                                val on = d == day
                                val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f), label = "day")
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(bg)
                                        .clickable { day = d }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        d.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                        fontWeight = FontWeight.Bold,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                                    )
                                    Text(
                                        "${dueOn(d).size}",
                                        fontSize = 12.sp,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else ManhwaInk.muted,
                                    )
                                }
                            }
                        }
                    }
                    val todays = dueOn(day)
                    item {
                        Section(
                            if (day == today.dayOfWeek) {
                                stringResource(ATMR.strings.schedule_today)
                            } else {
                                day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                            },
                        )
                    }
                    if (todays.isEmpty()) {
                        item { Text(stringResource(ATMR.strings.schedule_none), color = ManhwaInk.muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
                    }
                    items(todays, key = { "d${it.manga.id}" }) { e ->
                        WorkRow(e.manga, line = rhythmLine(e.rhythm), badge = null) { navigator.push(MangaScreen(e.manga.id)) }
                    }
                    if (late.isNotEmpty()) {
                        item { Section(stringResource(ATMR.strings.schedule_late)) }
                        items(late, key = { "l${it.manga.id}" }) { e ->
                            WorkRow(
                                e.manga,
                                line = stringResource(ATMR.strings.schedule_late_by, e.rhythm?.lateDays ?: 0),
                                badge = null,
                            ) { navigator.push(MangaScreen(e.manga.id)) }
                        }
                    }
                    if (behind.isNotEmpty()) {
                        item { Section(stringResource(ATMR.strings.schedule_behind)) }
                        items(behind, key = { "b${it.manga.id}" }) { e ->
                            WorkRow(
                                e.manga,
                                line = rhythmLine(e.rhythm),
                                badge = stringResource(ATMR.strings.schedule_behind_by, e.unread),
                            ) { navigator.push(MangaScreen(e.manga.id)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun rhythmLine(r: ReleaseRhythm.Rhythm?): String = when {
        r == null -> stringResource(ATMR.strings.schedule_irregular)
        r.weekday != null -> stringResource(ATMR.strings.schedule_every, r.weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
        else -> stringResource(ATMR.strings.schedule_every_days, r.gapDays.toInt().coerceAtLeast(1))
    }

    @Composable
    private fun Section(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp),
        )
    }

    @Composable
    private fun WorkRow(manga: Manga, line: String, badge: String?, onClick: () -> Unit) {
        Row(
            Modifier
                .padding(horizontal = 14.dp, vertical = 5.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp), alpha = 0.35f)
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = manga,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(48.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(manga.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(line, style = MaterialTheme.typography.labelMedium, color = ManhwaInk.muted, maxLines = 1)
            }
            if (badge != null) {
                Text(
                    badge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.size(2.dp))
        }
    }
}
