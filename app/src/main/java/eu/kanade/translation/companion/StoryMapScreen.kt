package eu.kanade.translation.companion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.translation.translator.appAiConfigured
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.History
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.rounded.Visibility
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * MANHWA: a work's story map, in two tabs. The timeline runs down a glowing line, each key event a coloured node with
 * its chapter; the relations are a web of the characters, the one with the most ties in the middle, lines coloured
 * by what they are to each other. Tapping a character lights only their ties and lists them.
 */
class StoryMapScreen(private val mangaId: Long, private val title: String) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var tab by remember { mutableStateOf(0) }
        var map by remember { mutableStateOf(StoryMap.cached(context, mangaId)) }
        var loading by remember { mutableStateOf(true) }
        var upTo by remember { mutableStateOf(0.0) }

        suspend fun build(force: Boolean) {
            loading = true
            val graph = context.appGraph
            val read = withContext(Dispatchers.IO) {
                runCatching { graph.getChaptersByMangaId.await(mangaId).filter { it.read }.maxOfOrNull { it.chapterNumber } ?: 0.0 }.getOrDefault(0.0)
            }
            upTo = read
            val manga = withContext(Dispatchers.IO) { runCatching { graph.getMangaById.await(mangaId) }.getOrNull() }
            val cast = graph.characterStore.cached(title)?.characters.orEmpty().map { it.name }
            if (force) withContext(Dispatchers.IO) { StoryMap.forget(context, mangaId) }
            map = StoryMap.load(context, mangaId, title, read, graph.glossaryStore, useAi = true, description = manga?.description, cast = cast)
            loading = false
        }
        LaunchedEffect(mangaId) { build(force = false) }

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(
                    title = stringResource(ATMR.strings.storymap_title),
                    subtitle = title,
                    onBack = navigator::pop,
                    actions = {
                        // MANHWA visual recap: the map as a card to share
                        val heading = stringResource(ATMR.strings.storymap_title)
                        val upToLine = stringResource(ATMR.strings.storymap_spoiler_free, upTo.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })
                        map?.takeIf { it.events.isNotEmpty() }?.let { current ->
                            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Share) {
                                scope.launch {
                                    val manga = withContext(Dispatchers.IO) { runCatching { context.appGraph.getMangaById.await(mangaId) }.getOrNull() }
                                    if (manga != null) RecapCard.share(context, manga, current, heading, upToLine)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh) { scope.launch { build(force = true) } }
                    },
                )
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabPill(MaterialSymbols.Rounded.History, stringResource(ATMR.strings.storymap_events), tab == 0) { tab = 0 }
                    TabPill(MaterialSymbols.Rounded.Group, stringResource(ATMR.strings.storymap_relations), tab == 1) { tab = 1 }
                }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MaterialSymbols.Rounded.Visibility, null, tint = ManhwaInk.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(ATMR.strings.storymap_spoiler_free, upTo.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }),
                        style = MaterialTheme.typography.labelSmall,
                        color = ManhwaInk.muted,
                    )
                }
                val current = map
                when {
                    loading && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    current == null || (tab == 0 && current.events.isEmpty()) || (tab == 1 && current.relations.isEmpty()) -> Empty(
                        if (!appAiConfigured()) stringResource(ATMR.strings.storymap_need_ai) else stringResource(ATMR.strings.storymap_empty),
                    )
                    tab == 0 -> Timeline(current.events)
                    else -> RelationWeb(current.relations)
                }
                if (loading && current != null) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
                }
            }
        }
    }

    @Composable
    private fun TabPill(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }

    @Composable
    private fun Empty(text: String) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(MaterialSymbols.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(10.dp))
            Text(text, color = ManhwaInk.muted, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun Timeline(events: List<StoryMap.Event>) {
        LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 30.dp)) {
            itemsIndexed(events) { i, e ->
                val color = eventColor(e.kind)
                val appear by animateFloatAsState(1f, tween(400, delayMillis = (i * 40).coerceAtMost(600)), label = "ev")
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).graphicsLayer { alpha = appear }) {
                    // The line and the node
                    Box(Modifier.width(34.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        Canvas(Modifier.width(2.dp).fillMaxHeight()) {
                            drawLine(
                                Color.White.copy(alpha = 0.15f),
                                Offset(size.width / 2, if (i == 0) 20.dp.toPx() else 0f),
                                Offset(size.width / 2, if (i == events.lastIndex) 20.dp.toPx() else size.height),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                        Box(
                            Modifier
                                .padding(top = 12.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(3.dp, color.copy(alpha = 0.3f), CircleShape),
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(bottom = 10.dp)
                            .glass(RoundedCornerShape(16.dp), alpha = 0.32f)
                            .padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                e.chapter.ifBlank { "#${e.number}" },
                                color = color,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (e.title.isNotBlank() && e.title != e.chapter) {
                            Text(e.title, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        }
                        if (e.text.isNotBlank()) {
                            Text(e.text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.78f))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RelationWeb(relations: List<StoryMap.Relation>) {
        val names = remember(relations) {
            relations.flatMap { listOf(it.a, it.b) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(14)
        }
        val shown = relations.filter { it.a in names && it.b in names }
        var picked by remember(relations) { mutableStateOf<String?>(null) }
        LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                BoxWithConstraints(
                    Modifier
                        .padding(14.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .glass(RoundedCornerShape(24.dp), alpha = 0.28f),
                ) {
                    val density = LocalDensity.current
                    val w = with(density) { maxWidth.toPx() }
                    val h = with(density) { maxHeight.toPx() }
                    val center = Offset(w / 2, h / 2)
                    val radius = minOf(w, h) * 0.36f
                    // The most tied character in the middle, the others round them
                    val spots = remember(names, w, h) {
                        names.mapIndexed { i, n ->
                            n to if (i == 0) {
                                center
                            } else {
                                val a = 2 * PI * (i - 1) / (names.size - 1).coerceAtLeast(1) - PI / 2
                                Offset(center.x + (radius * cos(a)).toFloat(), center.y + (radius * sin(a)).toFloat())
                            }
                        }.toMap()
                    }
                    Canvas(Modifier.fillMaxSize()) {
                        shown.forEach { r ->
                            val p1 = spots[r.a] ?: return@forEach
                            val p2 = spots[r.b] ?: return@forEach
                            val lit = picked == null || picked == r.a || picked == r.b
                            drawLine(
                                relationColor(r.kind).copy(alpha = if (lit) 0.85f else 0.08f),
                                p1,
                                p2,
                                strokeWidth = (if (lit && picked != null) 3.5f else 2f).dp.toPx(),
                                pathEffect = if (r.kind == StoryMap.RelationType.RIVAL) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
                            )
                        }
                    }
                    spots.forEach { (name, p) ->
                        val main = name == names.firstOrNull()
                        val on = picked == null || picked == name || shown.any { (it.a == name || it.b == name) && (it.a == picked || it.b == picked) }
                        Column(
                            Modifier
                                .offset { IntOffset((p.x - with(density) { 46.dp.toPx() }).roundToInt(), (p.y - with(density) { 18.dp.toPx() }).roundToInt()) }
                                .width(92.dp)
                                .graphicsLayer { alpha = if (on) 1f else 0.3f },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(if (main) 30.dp else 22.dp)
                                    .clip(CircleShape)
                                    .background(if (picked == name) MaterialTheme.colorScheme.primary else Color(0xFF1B1B22))
                                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (main) 1f else 0.5f), CircleShape)
                                    .clickable { picked = if (picked == name) null else name },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(name.take(1).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White)
                            }
                            Text(
                                name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 92.dp),
                            )
                        }
                    }
                }
            }
            // The legend
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StoryMap.RelationType.entries.filter { t -> shown.any { it.kind == t } }.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(relationColor(t)))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(relationLabel(t)), fontSize = 11.sp, color = ManhwaInk.muted)
                        }
                    }
                }
            }
            val list = shown.filter { picked == null || it.a == picked || it.b == picked }
            itemsIndexed(list) { _, r ->
                Row(
                    Modifier
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(14.dp), alpha = 0.3f)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(relationColor(r.kind)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${r.a}  ·  ${r.b}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            listOf(stringResource(relationLabel(r.kind)), r.note).filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 12.sp,
                            color = ManhwaInk.muted,
                        )
                    }
                }
            }
        }
    }
}

private fun eventColor(t: StoryMap.EventType) = when (t) {
    StoryMap.EventType.BATTLE -> Color(0xFFFF5252)
    StoryMap.EventType.DEATH -> Color(0xFF9E9EA8)
    StoryMap.EventType.REVEAL -> Color(0xFFB57BFF)
    StoryMap.EventType.POWER -> Color(0xFFFFD54A)
    StoryMap.EventType.MEET -> Color(0xFF4CD787)
    StoryMap.EventType.TURN -> Color(0xFF4FA8FF)
    StoryMap.EventType.OTHER -> Color(0xFFFF8A3D)
}

private fun relationColor(t: StoryMap.RelationType) = when (t) {
    StoryMap.RelationType.ENEMY -> Color(0xFFFF5252)
    StoryMap.RelationType.ALLY -> Color(0xFF4CD787)
    StoryMap.RelationType.FAMILY -> Color(0xFF4FA8FF)
    StoryMap.RelationType.LOVE -> Color(0xFFFF6FB5)
    StoryMap.RelationType.RIVAL -> Color(0xFFFF8A3D)
    StoryMap.RelationType.MASTER -> Color(0xFFFFD54A)
    StoryMap.RelationType.OTHER -> Color(0xFF9E9EA8)
}

private fun relationLabel(t: StoryMap.RelationType) = when (t) {
    StoryMap.RelationType.ENEMY -> ATMR.strings.rel_enemy
    StoryMap.RelationType.ALLY -> ATMR.strings.rel_ally
    StoryMap.RelationType.FAMILY -> ATMR.strings.rel_family
    StoryMap.RelationType.LOVE -> ATMR.strings.rel_love
    StoryMap.RelationType.RIVAL -> ATMR.strings.rel_rival
    StoryMap.RelationType.MASTER -> ATMR.strings.rel_master
    StoryMap.RelationType.OTHER -> ATMR.strings.rel_other
}
