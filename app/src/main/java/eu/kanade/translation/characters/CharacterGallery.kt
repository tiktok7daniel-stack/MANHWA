package eu.kanade.translation.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.glass
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Group
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.roundedfilled.Star
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val Crimson = Color(0xFFE5173F)

/**
 * MANHWA characters gallery under a work's description: a glass strip of the cast (the hero first, then the
 * main and supporting characters) to swipe through; a card opens the character's sheet.
 */
@Composable
fun CharacterGallery(mangaId: Long, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { context.appGraph.characterStore }
    val scope = rememberCoroutineScope()
    var cast by remember(title) { mutableStateOf(store.cached(title)) }
    var loading by remember(title) { mutableStateOf(cast == null) }
    var open by remember { mutableStateOf<CharacterStore.Character?>(null) }
    var picking by remember { mutableStateOf(false) }
    val readUpTo by androidx.compose.runtime.produceState(-1.0, mangaId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.appGraph.getChaptersByMangaId.await(mangaId).filter { it.read }.maxOfOrNull { it.chapterNumber } ?: -1.0 }.getOrDefault(-1.0)
        }
    }
    LaunchedEffect(mangaId, title) {
        cast = runCatching { store.load(mangaId, title) }.getOrNull() ?: cast
        loading = false
    }
    val characters = cast?.characters.orEmpty().filter { it.role != "BACKGROUND" || (cast?.characters?.size ?: 0) < 8 }
    if (!loading && characters.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.Group, contentDescription = null, tint = Crimson, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(ATMR.strings.chars_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (cast?.uncertain == true && cast?.candidates.orEmpty().size > 1) {
                Text(
                    stringResource(ATMR.strings.chars_not_this),
                    style = MaterialTheme.typography.labelMedium,
                    color = Crimson,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { picking = true }.padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (loading && characters.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Crimson)
            }
        } else {
            val hero = CastRules.heroOf(characters)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(characters, key = { it.id }) { c -> CharacterCard(c, isHero = c == hero) { open = c } }
            }
        }
    }
    open?.let { c -> CharacterSheet(title, c, isHero = c == CastRules.heroOf(characters), glossaryNote = androidx.compose.runtime.produceState("", c, readUpTo) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { noteFor(context, mangaId, title, c, readUpTo) } }.value) { open = null } }
    if (picking) {
        PickWorkSheet(cast?.candidates.orEmpty(), onDismiss = { picking = false }) { candidate ->
            picking = false
            loading = true
            scope.launch {
                cast = runCatching { store.load(mangaId, title, force = true, anilistId = candidate.id) }.getOrNull() ?: cast
                loading = false
            }
        }
    }
}

private fun noteFor(context: android.content.Context, mangaId: Long, title: String, c: CharacterStore.Character, readUpTo: Double): String {
    val glossary = context.appGraph.glossaryStore
    val notes = glossary.notes(mangaId)
    val note = (listOf(c.name) + c.alternative + c.name.split(' ')).firstNotNullOfOrNull { n -> notes.entries.firstOrNull { it.key.equals(n, true) }?.value }.orEmpty()
    // MANHWA work brain: what they are to the others (only what the translation already met)
    val names = (listOf(c.name, c.arabicName) + c.alternative + c.name.split(' ')).filter { it.length >= 3 }.map { it.lowercase() }
    // Only up to the last chapter read: no spoilers from chapters translated ahead
    val brain = runCatching { context.appGraph.seriesBrain.get(mangaId, title) }.getOrNull()?.let { eu.kanade.translation.brain.BrainRules.upTo(it, readUpTo) }
        ?.takeIf { readUpTo >= 0 }
    val relations = brain?.relations.orEmpty().filter { r -> r.a.lowercase() in names || r.b.lowercase() in names }
        .take(8).joinToString("\n") { "${it.a} ← ${it.kind} → ${it.b}" }
    return listOf(note, relations).filter { it.isNotBlank() }.joinToString("\n")
}

@Composable
private fun CharacterCard(c: CharacterStore.Character, isHero: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .width(112.dp)
            .height(172.dp)
            .glass(shape, accent = isHero, alpha = 0.5f)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        if (c.image.isNotBlank()) {
            AsyncImage(model = c.image, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(MaterialSymbols.Rounded.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp).align(Alignment.Center))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF2060204)),
            ),
        )
        if (isHero || c.role.equals("MAIN", true)) {
            Row(
                Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isHero) Crimson else Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isHero) Icon(MaterialSymbols.RoundedFilled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                Text(
                    stringResource(if (isHero) ATMR.strings.chars_hero else ATMR.strings.chars_main),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(
                c.arabicName.ifBlank { c.name },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (c.arabicName.isNotBlank()) {
                Text(c.name, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterSheet(title: String, c: CharacterStore.Character, isHero: Boolean, glossaryNote: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { context.appGraph.characterStore }
    var about by remember(c.id) { mutableStateOf(c.arabicAbout) }
    var loading by remember(c.id) { mutableStateOf(c.arabicAbout.isBlank() && c.about.isNotBlank()) }
    LaunchedEffect(c.id) {
        if (about.isBlank() && c.about.isNotBlank()) {
            about = store.arabicAbout(title, c)
            loading = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0B0709)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 110.dp, height = 150.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.dp, if (isHero) Crimson else Color.White.copy(alpha = 0.15f), RoundedCornerShape(22.dp)),
                ) {
                    if (c.image.isNotBlank()) AsyncImage(c.image, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.arabicName.ifBlank { c.name }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.White)
                    Text(listOf(c.name, c.native).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                    val role = when {
                        isHero -> stringResource(ATMR.strings.chars_hero)
                        c.role.equals("MAIN", true) -> stringResource(ATMR.strings.chars_main)
                        c.role.equals("SUPPORTING", true) -> stringResource(ATMR.strings.chars_supporting)
                        else -> stringResource(ATMR.strings.chars_background)
                    }
                    val gender = when (c.gender.lowercase()) {
                        "male" -> stringResource(ATMR.strings.chars_male)
                        "female" -> stringResource(ATMR.strings.chars_female)
                        else -> ""
                    }
                    Text(listOf(role, gender).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = Crimson, fontWeight = FontWeight.Bold)
                    if (c.alternative.isNotEmpty()) {
                        Text(c.alternative.take(4).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), maxLines = 2)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.45f).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(ATMR.strings.chars_about), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                when {
                    loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Crimson)
                    about.isNotBlank() -> Text(about, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    c.about.isNotBlank() -> Text(c.about, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                    else -> Text(stringResource(ATMR.strings.chars_no_about), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                }
            }
            if (glossaryNote.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.45f).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(ATMR.strings.chars_in_translation), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                    Text(glossaryNote, style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
            }
            Text(stringResource(ATMR.strings.chars_source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.35f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickWorkSheet(candidates: List<CharacterStore.Candidate>, onDismiss: () -> Unit, onPick: (CharacterStore.Candidate) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF0B0709)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(ATMR.strings.chars_pick_work), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
            candidates.forEach { c ->
                Text(
                    listOf(c.title, c.country).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(16.dp), alpha = 0.45f)
                        .clickable { onPick(c) }
                        .padding(14.dp),
                )
            }
        }
    }
}
