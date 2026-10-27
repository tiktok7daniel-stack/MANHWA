package eu.kanade.tachiyomi.ui.home.mood

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Bedtime
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.LocalFireDepartment
import mihon.icons.materialsymbols.rounded.Psychology
import mihon.icons.materialsymbols.rounded.RocketLaunch
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.CloudOff
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.tachiyomi.ui.home.mood.MoodRules.Mood

/**
 * MANHWA: the library by mood. Pick how you feel (light action, something sad, one to finish today...) and the
 * works of your library that fit show up, the ones you have not finished first.
 */
class MoodScreen : Screen() {

    private data class Look(val mood: Mood, val icon: ImageVector, val label: dev.icerock.moko.resources.StringResource, val a: Color, val b: Color)

    private val looks = listOf(
        Look(Mood.LIGHT_ACTION, MaterialSymbols.Rounded.LocalFireDepartment, ATMR.strings.mood_light_action, Color(0xFFFF5F45), Color(0xFFFFB347)),
        Look(Mood.SAD, MaterialSymbols.Rounded.CloudOff, ATMR.strings.mood_sad, Color(0xFF3A6FF8), Color(0xFF7FB2FF)),
        Look(Mood.ONE_DAY, MaterialSymbols.Rounded.Schedule, ATMR.strings.mood_one_day, Color(0xFF16A085), Color(0xFF6EE7B7)),
        Look(Mood.ROMANCE, MaterialSymbols.Rounded.Favorite, ATMR.strings.mood_romance, Color(0xFFE91E63), Color(0xFFFF8FB1)),
        Look(Mood.MYSTERY, MaterialSymbols.Rounded.Psychology, ATMR.strings.mood_mystery, Color(0xFF6A3DE8), Color(0xFFB39DFF)),
        Look(Mood.POWER, MaterialSymbols.Rounded.RocketLaunch, ATMR.strings.mood_power, Color(0xFFF59E0B), Color(0xFFFDE68A)),
        Look(Mood.FUNNY, MaterialSymbols.Rounded.AutoAwesome, ATMR.strings.mood_funny, Color(0xFF22C55E), Color(0xFFBEF264)),
        Look(Mood.DARK, MaterialSymbols.Rounded.Bedtime, ATMR.strings.mood_dark, Color(0xFF374151), Color(0xFF9CA3AF)),
    )

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        var mood by remember { mutableStateOf<Mood?>(null) }
        val library by produceState<List<LibraryManga>?>(null) {
            value = withContext(Dispatchers.IO) { runCatching { context.appGraph.getLibraryManga.await() }.getOrDefault(emptyList()) }
        }
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.mood_title), subtitle = stringResource(ATMR.strings.mood_subtitle), onBack = navigator::pop)
                val all = library
                if (all == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val works = remember(all) { all.distinctBy { it.id } }
                val picked = remember(mood, works) {
                    mood?.let { m ->
                        MoodRules.pick(m, works) { MoodRules.Work(it.manga.genre.orEmpty(), it.totalChapters, it.manga.status, it.unreadCount) }
                    }.orEmpty()
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            looks.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { look ->
                                        val on = mood == look.mood
                                        val lift by animateFloatAsState(if (on) 1f else 0f, label = "mood")
                                        Row(
                                            Modifier
                                                .weight(1f)
                                                .height(64.dp)
                                                .graphicsLayer {
                                                    scaleX = 1f + lift * 0.04f
                                                    scaleY = 1f + lift * 0.04f
                                                }
                                                .clip(RoundedCornerShape(18.dp))
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(look.a.copy(alpha = if (on) 1f else 0.35f), look.b.copy(alpha = if (on) 0.9f else 0.15f)),
                                                    ),
                                                )
                                                .border(1.dp, if (on) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                                                .clickable { mood = if (on) null else look.mood }
                                                .padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(look.icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                            Spacer(Modifier.size(10.dp))
                                            Text(stringResource(look.label), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
                                        }
                                    }
                                }
                            }
                            if (mood != null) {
                                Text(
                                    stringResource(ATMR.strings.mood_count, picked.size),
                                    color = ManhwaInk.muted,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                    items(picked, key = { it.id }) { lm ->
                        Column(Modifier.clickable { navigator.push(MangaScreen(lm.id)) }) {
                            AsyncImage(
                                model = lm.manga,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Text(
                                lm.manga.title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
