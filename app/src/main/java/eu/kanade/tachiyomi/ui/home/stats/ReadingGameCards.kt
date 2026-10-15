package eu.kanade.tachiyomi.ui.home.stats

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.LocalFireDepartment
import mihon.icons.materialsymbols.rounded.MilitaryTech
import mihon.icons.materialsymbols.rounded.TrendingUp
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/*
 * MANHWA: the cards of the reading game, shared by the home dashboard and the full levels screen. Vector icons
 * with a glow in the stage's colour, never emoji.
 */

/** The colour of a streak stage: amber spark, orange flame, red blaze, violet inferno. */
fun stageColor(stage: ReadingLevels.StreakStage): Color = when (stage) {
    ReadingLevels.StreakStage.NONE -> Color(0xFF8A8A92)
    ReadingLevels.StreakStage.SPARK -> Color(0xFFFFB74D)
    ReadingLevels.StreakStage.FLAME -> Color(0xFFFF7A1A)
    ReadingLevels.StreakStage.BLAZE -> Color(0xFFFF3D3D)
    ReadingLevels.StreakStage.INFERNO -> Color(0xFF9D7CFF)
}

/** The colour of a manhwa's letter rank, from grey E to gold S. */
fun rankColor(rank: ReadingLevels.Rank): Color = when (rank) {
    ReadingLevels.Rank.E -> Color(0xFF9E9EA8)
    ReadingLevels.Rank.D -> Color(0xFF4CD787)
    ReadingLevels.Rank.C -> Color(0xFF4FA8FF)
    ReadingLevels.Rank.B -> Color(0xFFB57BFF)
    ReadingLevels.Rank.A -> Color(0xFFFF8A3D)
    ReadingLevels.Rank.S -> Color(0xFFFFD54A)
}

fun hunterColor(rank: ReadingLevels.HunterRank): Color = when (rank) {
    ReadingLevels.HunterRank.E -> Color(0xFF9E9EA8)
    ReadingLevels.HunterRank.D -> Color(0xFF4CD787)
    ReadingLevels.HunterRank.C -> Color(0xFF4FA8FF)
    ReadingLevels.HunterRank.B -> Color(0xFFB57BFF)
    ReadingLevels.HunterRank.A -> Color(0xFFFF8A3D)
    ReadingLevels.HunterRank.S -> Color(0xFFFFD54A)
    ReadingLevels.HunterRank.MONARCH -> Color(0xFF9D7CFF)
}

fun hunterTitle(rank: ReadingLevels.HunterRank): String = when (rank) {
    ReadingLevels.HunterRank.E -> "E-Rank Reader"
    ReadingLevels.HunterRank.D -> "D-Rank Reader"
    ReadingLevels.HunterRank.C -> "C-Rank Hunter"
    ReadingLevels.HunterRank.B -> "B-Rank Hunter"
    ReadingLevels.HunterRank.A -> "A-Rank Hunter"
    ReadingLevels.HunterRank.S -> "S-Rank Hunter"
    ReadingLevels.HunterRank.MONARCH -> "S-Rank Monarch"
}

@Composable
private fun stageName(stage: ReadingLevels.StreakStage): String = when (stage) {
    ReadingLevels.StreakStage.NONE -> stringResource(ATMR.strings.game_stage_none)
    ReadingLevels.StreakStage.SPARK -> stringResource(ATMR.strings.game_stage_spark)
    ReadingLevels.StreakStage.FLAME -> stringResource(ATMR.strings.game_stage_flame)
    ReadingLevels.StreakStage.BLAZE -> stringResource(ATMR.strings.game_stage_blaze)
    ReadingLevels.StreakStage.INFERNO -> stringResource(ATMR.strings.game_stage_inferno)
}

/** A thin bar filled to [fraction] in [color], animated when it changes. */
@Composable
fun GlowProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(900), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown.coerceAtLeast(0.03f))
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color, Color.White.copy(alpha = 0.9f)))),
        )
    }
}

/** An icon in a glowing round tile; [pulse] makes the glow breathe (the flame of a live streak). */
@Composable
fun GlowIcon(icon: ImageVector, color: Color, size: Int = 54, pulse: Boolean = false) {
    // The breathing value is only read while drawing, so the tile itself never recomposes per frame
    val glow = if (pulse) {
        rememberInfiniteTransition(label = "glow").animateFloat(
            initialValue = 0.18f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "glowValue",
        )
    } else {
        null
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .neonGlow(CircleShape, color, { glow?.value ?: 0.22f }, 12.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.12f))))
            .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** The streak: days in a row, its stage, how far the next stage is, and the last seven days. */
@Composable
fun StreakCard(streak: ReadingLevels.Streak, modifier: Modifier = Modifier) {
    val color = stageColor(streak.stage)
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), accent = true, alpha = 0.45f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowIcon(MaterialSymbols.Rounded.LocalFireDepartment, color, pulse = streak.days > 0)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(ATMR.strings.game_streak_days, streak.days),
                    style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(color.copy(alpha = 0.7f), blurRadius = 22f)),
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                )
                Text(stageName(streak.stage), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
            }
        }
        GlowProgress(streak.toNext, color)
        val nextStage = ReadingLevels.StreakStage.entries.getOrNull(streak.stage.ordinal + 1)
        Text(
            when {
                nextStage == null -> stringResource(ATMR.strings.game_top_stage)
                !streak.readToday && streak.days > 0 -> stringResource(ATMR.strings.game_read_today)
                else -> stringResource(ATMR.strings.game_to_next_stage, streak.daysToNext ?: 0, stageName(nextStage))
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            streak.week.forEachIndexed { index, read ->
                val today = index == streak.week.lastIndex
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (read) color else Color.White.copy(alpha = if (today) 0.22f else 0.1f)),
                )
            }
        }
        Text(
            stringResource(ATMR.strings.game_longest, streak.longest),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

/** The reader's hunter rank from all their chapters and levels. */
@Composable
fun HunterCard(hunter: ReadingLevels.Hunter, totalChapters: Int, modifier: Modifier = Modifier) {
    val color = hunterColor(hunter.rank)
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), alpha = 0.45f)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowIcon(MaterialSymbols.Rounded.MilitaryTech, color, size = 48)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(ATMR.strings.game_hunter), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                Text(
                    hunterTitle(hunter.rank),
                    style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(color.copy(alpha = 0.7f), blurRadius = 18f)),
                    fontWeight = FontWeight.Black,
                    color = color,
                )
            }
            Text(
                stringResource(ATMR.strings.game_total, totalChapters),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        GlowProgress(hunter.toNext, color)
        Text(
            hunter.pointsToNext?.let { stringResource(ATMR.strings.game_points, hunter.points, it) }
                ?: stringResource(ATMR.strings.game_points_max, hunter.points),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

/** One manhwa's level: cover, title, level badge, chapters and time, the bar to the next level, continue. */
@Composable
fun SeriesLevelCard(
    series: ReadingProgressEngine.Series,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = series.level
    val cover = eu.kanade.presentation.components.rememberCoverColor(series.manga)
    val color = rankColor(level.rank)
    Row(
        modifier
            .fillMaxWidth()
            // MANHWA ambient light: the card glows in the colour of its cover
            .neonGlow(RoundedCornerShape(22.dp), cover ?: color, 0.16f, 8.dp)
            .glass(RoundedCornerShape(22.dp), alpha = 0.45f)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = series.manga.asMangaCover(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
            )
            Text(
                level.rank.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .neonGlow(CircleShape, color, 0.35f, 6.dp)
                    .clip(CircleShape)
                    .background(color)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                series.manga.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MaterialSymbols.Rounded.TrendingUp, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(ATMR.strings.game_level, level.level) + " · " + titleName(level.title),
                    style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(color.copy(alpha = 0.7f), blurRadius = 14f)),
                    fontWeight = FontWeight.Black,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (series.leveledUpAt > 0) {
                    Spacer(Modifier.width(6.dp))
                    LevelUpBadge(color)
                }
            }
            Text(
                stringResource(ATMR.strings.game_chapters, series.read, series.total) + " · " +
                    stringResource(ATMR.strings.game_time, (series.minutes / 60).toInt(), (series.minutes % 60).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GlowProgress(level.fraction, color)
            Text(
                stringResource(ATMR.strings.game_next_level, level.toNext, level.level + 1),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(42.dp)
                .neonGlow(CircleShape, color, 0.25f, 8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.9f))
                .clickable(onClick = onContinue),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MaterialSymbols.RoundedFilled.PlayArrow,
                contentDescription = stringResource(ATMR.strings.game_continue),
                tint = Color.Black,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** The name of a level title in the reader's language. */
@Composable
fun titleName(title: ReadingLevels.Title): String = when (title) {
    ReadingLevels.Title.NOVICE -> stringResource(ATMR.strings.title_novice)
    ReadingLevels.Title.FOLLOWER -> stringResource(ATMR.strings.title_follower)
    ReadingLevels.Title.DEVOTED -> stringResource(ATMR.strings.title_devoted)
    ReadingLevels.Title.ADDICT -> stringResource(ATMR.strings.title_addict)
    ReadingLevels.Title.MASTER -> stringResource(ATMR.strings.title_master)
}

/** "Level up", pulsing in the rank's colour, on a card whose level went up today. */
@Composable
fun LevelUpBadge(color: Color) {
    val transition = rememberInfiniteTransition(label = "levelUp")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "levelUpScale",
    )
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .neonGlow(RoundedCornerShape(50), color, 0.35f, 6.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MaterialSymbols.Rounded.TrendingUp, contentDescription = null, tint = Color.Black, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(ATMR.strings.level_up),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = Color.Black,
            maxLines = 1,
        )
    }
}

/** A glowing strip on the home screen for manhwa whose level went up today. */
@Composable
fun LevelUpBanner(series: ReadingProgressEngine.Series, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = rankColor(series.level.rank)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), accent = true, alpha = 0.5f)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowIcon(MaterialSymbols.Rounded.MilitaryTech, color, size = 34, pulse = true)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(ATMR.strings.level_up_banner, series.level.level, series.manga.title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        LevelUpBadge(color)
    }
}

/** One line of the details sheet: a label and its value. */
@Composable
private fun DetailRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Everything about one manhwa's level, with the way to the next chapter. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailsSheet(
    series: ReadingProgressEngine.Series,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onOpen: () -> Unit,
) {
    val level = series.level
    val color = rankColor(level.rank)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0B0B10),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = series.manga.asMangaCover(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(72.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        series.manga.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(ATMR.strings.game_level, level.level) + " · " + titleName(level.title),
                        style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(color.copy(alpha = 0.7f), blurRadius = 16f)),
                        fontWeight = FontWeight.Black,
                        color = color,
                    )
                    if (series.leveledUpAt > 0) LevelUpBadge(color)
                }
            }
            GlowProgress(level.fraction, color)
            DetailRow(stringResource(ATMR.strings.sheet_chapters), stringResource(ATMR.strings.game_chapters, series.read, series.total), Color.White)
            DetailRow(
                stringResource(ATMR.strings.sheet_time),
                stringResource(ATMR.strings.game_time, (series.minutes / 60).toInt(), (series.minutes % 60).toInt()),
                Color.White,
            )
            DetailRow(stringResource(ATMR.strings.sheet_rank), level.rank.name, color)
            DetailRow(stringResource(ATMR.strings.sheet_next), stringResource(ATMR.strings.sheet_next_value, level.toNext, level.level + 1), color)
            Spacer(Modifier.height(6.dp))
            eu.kanade.presentation.components.ManhwaKit.NeonButton(
                text = stringResource(ATMR.strings.sheet_continue),
                icon = MaterialSymbols.RoundedFilled.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                onClick = onContinue,
            )
            eu.kanade.presentation.components.ManhwaKit.GhostButton(
                text = stringResource(ATMR.strings.sheet_open),
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpen,
            )
        }
    }
}
