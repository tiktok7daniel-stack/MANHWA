package eu.kanade.tachiyomi.novel.bridge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.presentation.util.formatChapterNumber
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AutoStories
import mihon.icons.materialsymbols.rounded.MenuBook
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** How long each half of the flip takes (the manhwa turning away, then the novel turning in). */
const val FACE_FLIP_MS = 280

/**
 * MANHWA "the other face": the novel side of the manhwa chapter on screen, turned in like the back of a card.
 * It finds the novel chapter that tells the same scene, shows its words (in Arabic when translated, with a
 * button to translate it there), and goes on to the chapters before and after it. Back, or the manhwa button,
 * turns the card again.
 */
@Composable
fun OtherFaceView(manga: Manga, chapter: Chapter, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(chapter.id) { mutableStateOf<OtherFace.State>(OtherFace.State.Locating(null)) }
    var translating by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val turn = remember { Animatable(-90f) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            turn.animateTo(-90f, tween(FACE_FLIP_MS, easing = FastOutSlowInEasing))
            onClose()
        }
    }
    BackHandler { close() }
    LaunchedEffect(Unit) { turn.animateTo(0f, tween(FACE_FLIP_MS, delayMillis = FACE_FLIP_MS, easing = FastOutSlowInEasing)) }
    LaunchedEffect(chapter.id) { OtherFace.open(context, manga, chapter) { state = it } }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = turn.value
                cameraDistance = 14f * density
                alpha = if (turn.value <= -89f) 0f else 1f
            }
            .background(Brush.verticalGradient(listOf(Color(0xFF121019), Color(0xFF07070B))))
            // Touches stay on this face, not on the page turned away under it
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // The top: back to the manhwa, and which face this is
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .glass(RoundedCornerShape(50), alpha = 0.6f)
                        .clickable { close() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MaterialSymbols.Rounded.AutoStories, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(ATMR.strings.face_back), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(ATMR.strings.face_title), color = accent, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 13.sp)
            }
            when (val s = state) {
                is OtherFace.State.Locating -> Locating(s.step, formatChapterNumber(chapter.chapterNumber))
                OtherFace.State.NoNovel -> Message(stringResource(ATMR.strings.face_no_novel))
                is OtherFace.State.Failed -> Message(
                    when (s.why) {
                        OtherFace.Why.UNSURE -> stringResource(ATMR.strings.bridge_unsure, formatChapterNumber(chapter.chapterNumber))
                        OtherFace.Why.NO_SOURCE -> stringResource(ATMR.strings.face_no_source)
                        else -> stringResource(ATMR.strings.bridge_failed, formatChapterNumber(chapter.chapterNumber))
                    },
                )
                is OtherFace.State.Ready -> ReadyFace(
                    s,
                    translating,
                    onTranslate = {
                        if (translating == null) {
                            scope.launch {
                                translating = 0 to s.paragraphs.size
                                state = runCatching { OtherFace.translate(context, s) { d, t -> translating = d to t } }.getOrDefault(s)
                                translating = null
                            }
                        }
                    },
                    onGo = { to ->
                        scope.launch {
                            state = OtherFace.State.Locating(null)
                            state = OtherFace.load(context, s.link, s.chapters, to)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadyFace(s: OtherFace.State.Ready, translating: Pair<Int, Int>?, onTranslate: () -> Unit, onGo: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val list = rememberLazyListState()
    LaunchedEffect(s.index) { list.scrollToItem(0) }
    LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "head") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.55f)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = s.link.cover.ifBlank { null },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.link.title, color = Color.White, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(ATMR.strings.face_chapter_of, s.index + 1, s.chapters.size) + " · " + s.chapter.name,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // How the scene was found: from the words both share, or estimated from the pace
                val badge = if (s.matched) stringResource(ATMR.strings.face_same_scene, s.share) else stringResource(ATMR.strings.face_estimate)
                Text(
                    badge,
                    color = if (s.matched) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (s.matched) accent else Color(0xFFFFB020).copy(alpha = 0.3f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
                // How far the novel goes past this scene
                val ahead = s.chapters.size - 1 - s.index
                if (ahead > 0) {
                    Text(stringResource(ATMR.strings.face_ahead, ahead), color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.index > 0) ManhwaKit.GhostButton(stringResource(ATMR.strings.face_prev)) { onGo(s.index - 1) }
                    if (s.index < s.chapters.size - 1) ManhwaKit.GhostButton(stringResource(ATMR.strings.face_next)) { onGo(s.index + 1) }
                }
                if (!s.translated) {
                    if (translating != null) {
                        Text(stringResource(ATMR.strings.face_translating, translating.first, translating.second), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        LinearProgressIndicator(
                            progress = { if (translating.second > 0) translating.first.toFloat() / translating.second else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ManhwaKit.NeonButton(stringResource(ATMR.strings.face_translate), modifier = Modifier.fillMaxWidth(), onClick = onTranslate)
                    }
                }
            }
        }
        itemsIndexed(s.paragraphs, key = { i, _ -> "p$i" }) { _, p ->
            Text(
                p,
                style = TextStyle(
                    color = Color(0xFFECE7F2),
                    fontSize = 17.sp,
                    lineHeight = 29.sp,
                    textDirection = TextDirection.Content,
                ),
            )
        }
    }
}

@Composable
private fun Locating(step: NovelAligner.Step?, number: String) {
    val accent = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "face").animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .neonGlow(CircleShape, accent, 0.5f, 22.dp)
                .clip(CircleShape)
                .border(2.dp, accent, CircleShape)
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MaterialSymbols.Rounded.MenuBook, contentDescription = null, tint = accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            when (step) {
                NovelAligner.Step.READING_MANHWA -> stringResource(ATMR.strings.bridge_step_manhwa, number)
                NovelAligner.Step.READING_NOVEL -> stringResource(ATMR.strings.bridge_step_novel)
                NovelAligner.Step.COMPARING -> stringResource(ATMR.strings.bridge_step_compare, number)
                else -> stringResource(ATMR.strings.face_finding)
            },
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 24.sp)
    }
}
