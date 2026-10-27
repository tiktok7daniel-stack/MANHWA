package eu.kanade.tachiyomi.music

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.CoverPalette
import eu.kanade.presentation.manga.components.BackdropScene
import eu.kanade.presentation.manga.components.LocalCoverPalette
import eu.kanade.presentation.manga.components.PageBackdrop
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.AutoAwesome
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.Headphones
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SkipPrevious
import mihon.icons.materialsymbols.roundedfilled.Pause
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

private val Ink = Color(0xFF07070A)

/** MANHWA: songs, and videos (a TikTok clip...) whose sound becomes a song. */
private val SONG_TYPES = arrayOf("audio/*", "video/*")

@Composable
private fun accentOf(palette: CoverPalette?) = palette?.primary ?: MaterialTheme.colorScheme.primary

/** A song's picture, or a glowing disc of the accent colour when it has none. */
@Composable
fun TrackArt(track: MusicStore.Track?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val file: File? = remember(track?.cover) { track?.let { MusicStore.get(context).coverFile(it) } }
    val accent = accentOf(LocalCoverPalette.current)
    if (file != null) {
        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(
            modifier.background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.35f), Ink))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaterialSymbols.Rounded.Headphones, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(36.dp))
        }
    }
}

/** A vinyl that turns while the song plays (and stops where it is), the song's picture as its label. */
@Composable
fun VinylDisc(track: MusicStore.Track?, playing: Boolean, discSize: Dp) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            angle.animateTo(angle.value + 360f, tween(6000, easing = LinearEasing))
        }
    }
    Box(
        Modifier
            .size(discSize)
            .graphicsLayer { rotationZ = angle.value % 360f },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFF0C0C10))
            for (i in 1..6) drawCircle(Color.White.copy(alpha = 0.05f), radius = size.minDimension / 2 * (0.45f + i * 0.09f), style = Stroke(1f))
            rotate(35f) {
                drawArc(Color.White.copy(alpha = 0.12f), -30f, 40f, false, style = Stroke(size.minDimension * 0.08f))
            }
        }
        TrackArt(track, Modifier.size(discSize * 0.5f).clip(CircleShape))
        Box(Modifier.size(discSize * 0.08f).clip(CircleShape).background(Ink))
    }
}

/** Four bars that dance while the music plays. */
@Composable
fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "eq").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "t")
    val level by animateFloatAsState(if (playing) 1f else 0.15f, label = "level")
    Canvas(modifier.size(18.dp, 16.dp)) {
        val w = size.width / 7
        for (i in 0 until 4) {
            val phase = sin((t + i * 0.27f) * 2 * Math.PI).toFloat()
            val h = size.height * (0.25f + 0.75f * level * (0.5f + 0.5f * phase))
            drawRoundRect(color, topLeft = Offset(i * w * 2, size.height - h), size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2))
        }
    }
}

/** The title on its plate (any of the frames, animated), or a plain glass pill. */
@Composable
fun PlateTitle(track: MusicStore.Track?, modifier: Modifier = Modifier) {
    val accent = accentOf(LocalCoverPalette.current)
    val plate = eu.kanade.presentation.manga.components.TitlePlate.of(track?.plate)
        ?: eu.kanade.presentation.manga.components.TitlePlate.all.last()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        eu.kanade.presentation.manga.components.AnimatedPlate(
            text = track?.title ?: "",
            plate = plate,
            font = androidx.compose.ui.text.font.FontFamily.Default,
            accent = accent,
            height = 96.dp,
            maxFont = 22,
        )
        if (!track?.artist.isNullOrBlank()) {
            Text(track!!.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/**
 * MANHWA: the work's songs play while one of its screens is open: they start (or carry on) when it opens or comes
 * back, and fade out shortly after it closes unless another screen of the same work opens.
 */
@Composable
fun WorkMusicEffect(workId: Long) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(workId, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) MusicPlayer.attach(context, workId)
        }
        lifecycle.lifecycle.addObserver(observer)
        MusicPlayer.attach(context, workId)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            MusicPlayer.leave(workId)
        }
    }
}

/**
 * MANHWA: the music bar of a work's page and chapters: a glass capsule with the turning vinyl, the title running
 * across, the dancing bars, play and next, and a thin glowing line of the song's progress. A tap opens the player;
 * a work with no songs yet shows a small orb to add some.
 */
@Composable
fun MusicMiniBar(mangaId: Long, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by MusicPlayer.state.collectAsState()
    val library by remember { MusicStore.get(context).library }.collectAsState()
    val hasSongs = library.works[mangaId.toString()]?.tracks?.isNotEmpty() == true
    var open by remember { mutableStateOf(false) }
    val accent = accentOf(LocalCoverPalette.current)
    val haptics = LocalHapticFeedback.current
    if (open) MusicPlayerDialog(mangaId, title) { open = false }

    AnimatedVisibility(
        visible = !hasSongs,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.75f))
                .border(1.dp, Brush.linearGradient(listOf(accent, Color.White.copy(alpha = 0.3f))), CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaterialSymbols.Rounded.Headphones, null, tint = accent, modifier = Modifier.size(22.dp))
            Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(MaterialSymbols.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
    // The song as it is now in the library (renamed, a new picture), not the copy the player started with
    val current = state.track?.let { t -> library.tracks.firstOrNull { it.id == t.id } ?: t }.takeIf { state.mangaId == mangaId }
    AnimatedVisibility(
        visible = hasSongs,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val glow by rememberInfiniteTransition(label = "bar").animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")
        Box(
            Modifier
                .width(250.dp)
                .height(60.dp)
                .drawBehind {
                    if (state.playing) {
                        drawRoundRect(
                            accent.copy(alpha = 0.35f * glow),
                            topLeft = Offset(-6f, -6f),
                            size = androidx.compose.ui.geometry.Size(size.width + 12f, size.height + 12f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                        )
                    }
                }
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.92f), accent.copy(alpha = 0.25f).compositeOverInk())))
                .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.35f), accent.copy(alpha = 0.6f))), RoundedCornerShape(50))
                .clickable { open = true },
        ) {
            Row(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                VinylDisc(current, state.playing && current != null, 48.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.title ?: stringResource(ATMR.strings.music_tap_to_play),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Equalizer(state.playing && current != null, accent)
                        Spacer(Modifier.width(6.dp))
                        Text(current?.artist?.ifBlank { null } ?: title, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                RoundIcon(if (state.playing && current != null) MaterialSymbols.RoundedFilled.Pause else MaterialSymbols.RoundedFilled.PlayArrow, accent, 38.dp) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (state.mangaId != mangaId) MusicPlayer.refresh(context, mangaId, 0) else MusicPlayer.toggle(context)
                }
                Icon(
                    MaterialSymbols.Rounded.SkipNext,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable { MusicPlayer.next() }.padding(6.dp),
                )
            }
            // The song's progress, a glowing line along the bottom
            val fraction = if (state.durationMs > 0 && current != null) state.positionMs.toFloat() / state.durationMs else 0f
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).padding(horizontal = 24.dp)) {
                drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height)
                drawLine(
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), Color.White)),
                    Offset(0f, size.height / 2),
                    Offset(size.width * fraction, size.height / 2),
                    strokeWidth = size.height,
                )
            }
        }
    }
}

private fun Color.compositeOverInk(): Color = androidx.compose.ui.graphics.Color(
    red = red * alpha + Ink.red * (1 - alpha),
    green = green * alpha + Ink.green * (1 - alpha),
    blue = blue * alpha + Ink.blue * (1 - alpha),
    alpha = 0.92f,
)

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.65f))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * MANHWA: the full player of a work. The scene of the work behind, the songs as cards in a turning carousel (each
 * in its chrome frame, its title on its plate), the progress, the controls, how the songs follow each other, and
 * the songs themselves: add from the phone, edit, give a picture, a plate and a frame, add or take off this work.
 */
@Composable
fun MusicPlayerDialog(mangaId: Long, title: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MusicPlayerContent(mangaId, title, onDismiss)
    }
}

@Composable
private fun MusicPlayerContent(mangaId: Long, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MusicStore.get(context) }
    val library by store.library.collectAsState()
    val state by MusicPlayer.state.collectAsState()
    val palette = LocalCoverPalette.current
    val accent = accentOf(palette)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val work = library.works[mangaId.toString()] ?: MusicStore.WorkMusic()
    val songs = work.tracks.mapNotNull { id -> library.tracks.firstOrNull { it.id == id } }
    val others = library.tracks.filter { it.id !in work.tracks }
    var editing by remember { mutableStateOf<MusicStore.Track?>(null) }
    var importing by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            uris.forEach { store.import(it, mangaId) }
            importing = false
            MusicPlayer.refresh(context, mangaId, if (state.mangaId == mangaId && state.playing) null else store.tracksOf(mangaId).lastIndex)
        }
    }
    LaunchedEffect(mangaId) { if (state.mangaId != mangaId) MusicPlayer.attach(context, mangaId) }

    val scene = BackdropScene.of(work.scene) ?: BackdropScene.forColor(palette?.primary, mangaId)
    PageBackdrop(scene, dim = 0.45f, custom = remember(work.scene) { store.sceneFile(work.scene) }) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top: close, what plays, and the work
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MaterialSymbols.Rounded.Close,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onDismiss).padding(9.dp),
                )
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(ATMR.strings.music_title).uppercase(), color = accent, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    MaterialSymbols.Rounded.Add,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = 0.35f)).clickable { pick.launch(SONG_TYPES) }.padding(9.dp),
                )
            }

            if (songs.isEmpty()) {
                EmptyMusic(accent, importing) { pick.launch(SONG_TYPES) }
            } else {
                SongCarousel(songs, state, mangaId, accent)
                val playingHere = state.mangaId == mangaId
                val current = if (playingHere) state.track?.let { t -> songs.firstOrNull { it.id == t.id } ?: t } else songs.first()
                PlateTitle(current, Modifier.padding(horizontal = 24.dp))
                Progress(state.takeIf { playingHere }, accent)
                Controls(state.playing && playingHere, work.mode, accent, onMode = {
                    val next = when (work.mode) { "NEXT" -> "ONE"; "ONE" -> "SHUFFLE"; else -> "NEXT" }
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    MusicPlayer.setMode(context, mangaId, next)
                }) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (!playingHere) MusicPlayer.refresh(context, mangaId, 0) else MusicPlayer.toggle(context)
                }
                Spacer(Modifier.height(18.dp))
                SectionTitle(stringResource(ATMR.strings.music_this_work), accent)
                songs.forEachIndexed { i, song ->
                    SongRow(
                        song = song,
                        playing = playingHere && state.index == i && state.playing,
                        accent = accent,
                        onClick = { if (playingHere) MusicPlayer.playAt(context, i) else MusicPlayer.refresh(context, mangaId, i) },
                        onEdit = { editing = song },
                        trailing = {
                            Icon(
                                MaterialSymbols.Rounded.Close,
                                null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(34.dp).clip(CircleShape).clickable {
                                    store.setOnWork(mangaId, song.id, false)
                                    MusicPlayer.refresh(context, mangaId)
                                }.padding(7.dp),
                            )
                        },
                    )
                }
            }
            if (others.isNotEmpty()) {
                SectionTitle(stringResource(ATMR.strings.music_library), accent)
                others.forEach { song ->
                    SongRow(
                        song = song,
                        playing = false,
                        accent = accent,
                        onClick = {
                            store.setOnWork(mangaId, song.id, true)
                            MusicPlayer.refresh(context, mangaId)
                        },
                        onEdit = { editing = song },
                        trailing = {
                            Icon(
                                MaterialSymbols.Rounded.Add,
                                null,
                                tint = accent,
                                modifier = Modifier.size(34.dp).clip(CircleShape).clickable {
                                    store.setOnWork(mangaId, song.id, true)
                                    MusicPlayer.refresh(context, mangaId)
                                }.padding(7.dp),
                            )
                        },
                    )
                }
            }
            Settings(mangaId, work, library, accent)
            Spacer(Modifier.height(30.dp))
        }
    }
    editing?.let { song -> EditSong(song, accent) { editing = null } }
}

@Composable
private fun EmptyMusic(accent: Color, importing: Boolean, onAdd: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "empty").animateFloat(0.9f, 1.08f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "p")
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(170.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent)))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaterialSymbols.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(if (importing) ATMR.strings.music_importing else ATMR.strings.music_empty),
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

/** The songs as cards turning on a carousel; the one in the middle plays. */
@Composable
private fun SongCarousel(songs: List<MusicStore.Track>, state: MusicPlayer.State, mangaId: Long, accent: Color) {
    val context = LocalContext.current
    val playingHere = state.mangaId == mangaId
    val pager = rememberPagerState(initialPage = if (playingHere) state.index.coerceIn(0, songs.lastIndex) else 0) { songs.size }
    // The carousel follows the song that plays, and a song swiped to the middle plays
    LaunchedEffect(state.index, playingHere) {
        if (playingHere && pager.currentPage != state.index && state.index in songs.indices) pager.animateScrollToPage(state.index)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val s = MusicPlayer.state.value
            if (s.mangaId == mangaId && s.index != page && s.queue.size == songs.size) MusicPlayer.playAt(context, page)
        }
    }
    val float by rememberInfiniteTransition(label = "float").animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "f")
    HorizontalPager(
        state = pager,
        contentPadding = PaddingValues(horizontal = 72.dp),
        modifier = Modifier.fillMaxWidth().height(330.dp).padding(top = 12.dp),
    ) { page ->
        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
        val song = songs.getOrNull(page) ?: return@HorizontalPager
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val a = abs(offset).coerceIn(0f, 1.5f)
                    rotationY = offset * 38f
                    cameraDistance = 12 * density
                    val s = 1f - 0.2f * a
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.45f * a
                    translationY = if (a < 0.5f) sin(float * 2 * Math.PI).toFloat() * 6.dp.toPx() else 0f
                },
            contentAlignment = Alignment.Center,
        ) {
            SongCard(song, accent, Modifier.fillMaxHeight(0.92f))
        }
    }
}

@Composable
private fun SongCard(song: MusicStore.Track, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(0.72f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (song.chrome) 14.dp else 0.dp)
                .drawBehind {
                    drawRoundRect(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent), radius = size.maxDimension),
                        topLeft = Offset(-20f, -20f),
                        size = androidx.compose.ui.geometry.Size(size.width + 40f, size.height + 40f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(40f),
                    )
                }
                .clip(RoundedCornerShape(22.dp))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(22.dp)),
        ) {
            TrackArt(song, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
        }
        if (song.chrome) {
            Image(painterResource(R.drawable.music_frame_chrome), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Progress(state: MusicPlayer.State?, accent: Color) {
    val fraction = if (state != null && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.padding(horizontal = 28.dp)) {
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { MusicPlayer.seek(it) }
                dragging = null
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(time(state?.positionMs ?: 0), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + time(((state?.durationMs ?: 0) - (state?.positionMs ?: 0)).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Controls(playing: Boolean, mode: String, accent: Color, onMode: () -> Unit, onPlay: () -> Unit) {
    val ring by rememberInfiniteTransition(label = "ring").animateFloat(1f, 1.35f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "r")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onMode).padding(4.dp)) {
            Icon(
                when (mode) {
                    "ONE" -> MaterialSymbols.Rounded.Refresh
                    "SHUFFLE" -> MaterialSymbols.Rounded.AutoAwesome
                    else -> MaterialSymbols.Rounded.SkipNext
                },
                null,
                tint = accent,
            )
            Text(
                stringResource(
                    when (mode) {
                        "ONE" -> ATMR.strings.music_mode_one
                        "SHUFFLE" -> ATMR.strings.music_mode_shuffle
                        else -> ATMR.strings.music_mode_next
                    },
                ),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Icon(MaterialSymbols.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(52.dp).clip(CircleShape).clickable { MusicPlayer.previous() }.padding(10.dp))
        Box(contentAlignment = Alignment.Center) {
            if (playing) {
                Box(
                    Modifier
                        .size(76.dp)
                        .graphicsLayer {
                            scaleX = ring
                            scaleY = ring
                            alpha = (1.35f - ring) / 0.35f * 0.6f
                        }
                        .border(2.dp, accent, CircleShape),
                )
            }
            RoundIcon(if (playing) MaterialSymbols.RoundedFilled.Pause else MaterialSymbols.RoundedFilled.PlayArrow, accent, 76.dp, onPlay)
        }
        Icon(MaterialSymbols.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(52.dp).clip(CircleShape).clickable { MusicPlayer.next() }.padding(10.dp))
        Spacer(Modifier.width(64.dp))
    }
}

@Composable
private fun SectionTitle(text: String, accent: Color) {
    Text(
        text.uppercase(),
        color = accent,
        fontSize = 12.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SongRow(
    song: MusicStore.Track,
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (playing) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (playing) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(song, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) {
                    Equalizer(true, accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(song.artist.ifBlank { time(song.durationMs) }, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
            }
        }
        Icon(MaterialSymbols.Rounded.Edit, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onEdit).padding(7.dp))
        trailing()
    }
}

@Composable
private fun Settings(mangaId: Long, work: MusicStore.WorkMusic, library: MusicStore.Library, accent: Color) {
    val context = LocalContext.current
    val store = remember { MusicStore.get(context) }
    SectionTitle(stringResource(ATMR.strings.music_scene), accent)
    // The scenes of the cover's colour come first
    val coverColor = LocalCoverPalette.current?.primary
    val scenes = remember(coverColor) {
        val family = BackdropScene.familyOf(coverColor)
        BackdropScene.entries.sortedBy { it.family != family }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SceneChip(null, work.scene == null, accent) { store.setScene(mangaId, null) }
        }
        // The reader's own pictures first (a long press removes one), then the built-in scenes
        items(library.customScenes) { name ->
            val value = MusicStore.CUSTOM + name
            CustomSceneChip(store.sceneFile(value), work.scene == value, accent, onClick = { store.setScene(mangaId, value) }) {
                store.deleteScene(value)
            }
        }
        items(scenes, key = { it.name }) { scene ->
            SceneChip(scene, work.scene == scene.name, accent) { store.setScene(mangaId, scene.name) }
        }
        item {
            val scope = rememberCoroutineScope()
            val pickScene = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) scope.launch { store.addScene(uri)?.let { store.setScene(mangaId, it) } }
            }
            Box(
                Modifier
                    .size(width = 72.dp, height = 96.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .clickable { pickScene.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(MaterialSymbols.Rounded.Add, null, tint = accent)
                    Text(stringResource(ATMR.strings.music_scene_add), color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(ATMR.strings.music_autoplay), color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = library.autoplay, onCheckedChange = { store.setAutoplay(it) })
    }
    var volume by remember { mutableFloatStateOf(library.volume) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MaterialSymbols.Rounded.Headphones, null, tint = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(10.dp))
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                MusicPlayer.setVolume(context, it)
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CustomSceneChip(file: File?, selected: Boolean, accent: Color, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 72.dp, height = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = { confirm = true }),
    ) {
        if (file != null) AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(ATMR.strings.music_scene_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text(stringResource(ATMR.strings.music_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(tachiyomi.i18n.MR.strings.action_cancel)) } },
        )
    }
}

@Composable
private fun SceneChip(scene: BackdropScene?, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 72.dp, height = 96.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (scene != null) {
            Image(painterResource(scene.res), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, Ink))), contentAlignment = Alignment.Center) {
                Text(stringResource(ATMR.strings.music_scene_auto), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Editing a song: its name and artist, its picture, the plate under its title and the chrome frame of its card. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditSong(song: MusicStore.Track, accent: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MusicStore.get(context) }
    val scope = rememberCoroutineScope()
    val library by store.library.collectAsState()
    val live = library.tracks.firstOrNull { it.id == song.id } ?: song
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { store.setCover(song.id, uri) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(ATMR.strings.music_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackArt(live, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { picture.launch("image/*") }) {
                        Icon(MaterialSymbols.Rounded.Image, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(ATMR.strings.music_picture))
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(ATMR.strings.music_song_title)) }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(ATMR.strings.music_artist)) }, singleLine = true)
                Text(stringResource(ATMR.strings.music_plate), fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    eu.kanade.presentation.manga.components.TitlePlate.all.map { it.key to it.res }.forEach { (key, res) ->
                        Box(
                            Modifier
                                .size(width = 64.dp, height = 40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(if (live.plate == key) 2.dp else 1.dp, if (live.plate == key) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .clickable { store.edit(song.id) { it.copy(plate = key) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (res != null) Image(painterResource(res), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
                            else Text("—", color = Color.White)
                        }
                    }
                }
                // The end that is not played (TikTok's sound), in whole seconds
                Text(stringResource(ATMR.strings.music_trim, (live.trimEndMs / 1000).toInt()), fontWeight = FontWeight.Bold)
                Slider(
                    value = (live.trimEndMs / 1000f),
                    onValueChange = { v -> store.edit(song.id) { it.copy(trimEndMs = (v.toInt() * 1000L)) } },
                    onValueChangeFinished = { MusicPlayer.state.value.mangaId?.let { MusicPlayer.refresh(context, it) } },
                    valueRange = 0f..10f,
                    steps = 9,
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(ATMR.strings.music_chrome), modifier = Modifier.weight(1f))
                    Switch(checked = live.chrome, onCheckedChange = { on -> store.edit(song.id) { it.copy(chrome = on) } })
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(MaterialSymbols.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(ATMR.strings.music_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.edit(song.id) { it.copy(title = title.trim().ifBlank { it.title }, artist = artist.trim()) }
                onDismiss()
            }) { Text(stringResource(tachiyomi.i18n.MR.strings.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(tachiyomi.i18n.MR.strings.action_cancel)) } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(ATMR.strings.music_delete_confirm, song.title)) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(song.id)
                    MusicPlayer.state.value.mangaId?.let { MusicPlayer.refresh(context, it) }
                    confirmDelete = false
                    onDismiss()
                }) { Text(stringResource(ATMR.strings.music_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(tachiyomi.i18n.MR.strings.action_cancel)) } },
        )
    }
}
