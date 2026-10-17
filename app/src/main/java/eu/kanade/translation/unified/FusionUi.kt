package eu.kanade.translation.unified

import android.widget.Toast
import androidx.compose.foundation.background
import mihon.icons.materialsymbols.rounded.Search
import eu.kanade.presentation.manga.components.MangaCover
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.home.stats.GlowIcon
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CallMerge
import mihon.icons.materialsymbols.rounded.CallSplit
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.SwapHoriz
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val Ember = Color(0xFFFF6A2B)
private val Mint = Color(0xFF3DDC97)
private val Amber = Color(0xFFFFB020)

/** What the series screen knows about this work's fusion: the offer, or the continuation once fused. */
@Stable
class FusionUi(val manga: Manga) {
    var fusion by mutableStateOf<ChapterFusion.Fusion?>(null)
    var offer by mutableStateOf<ChapterFusion.Offer?>(null)
    var choices by mutableStateOf<List<ChapterFusion.Offer>>(emptyList())
    var continuation by mutableStateOf<List<ChapterFusion.Continuation>>(emptyList())
    var other by mutableStateOf<Manga?>(null)
    var oursMin by mutableStateOf<Double?>(null)
    var oursMax by mutableStateOf<Double?>(null)
    var baseLang by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var dismissed by mutableStateOf(false)

    /** The badge of this work's own chapters while fused ("Arabic, ready · Team X"), null when not fused. */
    @Composable
    fun baseBadge(): String? {
        val f = fusion ?: return null
        return if (baseLang.startsWith("ar")) {
            stringResource(ATMR.strings.fusion_badge_ready_ar, f.baseSourceName)
        } else {
            stringResource(ATMR.strings.fusion_badge_ready, f.baseSourceName)
        }
    }
}

/** Keeps [FusionUi] for [manga] up to date: the saved fusion and its continuation, or a search for an offer. */
@Composable
fun rememberFusionUi(manga: Manga): FusionUi {
    val context = LocalContext.current
    val ui = remember(manga.id) { FusionUi(manga) }
    val fusions by remember { context.appGraph.chapterFusion.fusions }.collectAsState()
    val fusion = fusions[manga.id]
    LaunchedEffect(manga.id, fusion) {
        val graph = context.appGraph
        val engine = graph.chapterFusion
        ui.fusion = fusion
        withContext(Dispatchers.IO) {
            ui.baseLang = (graph.sourceManager.get(manga.source) as? CatalogueSource)?.lang.orEmpty()
            val numbers = graph.getChaptersByMangaId.await(manga.id).map { it.chapterNumber }.filter { FusionRules.countable(it) }
            ui.oursMin = numbers.minOrNull()
            ui.oursMax = numbers.maxOrNull()
            ui.dismissed = engine.isDismissed(manga.id)
        }
        if (fusion != null) {
            ui.offer = null
            ui.other = withContext(Dispatchers.IO) { graph.getMangaById.await(fusion.otherMangaId) }
            // What is already known first, then what the other source has now
            ui.continuation = engine.continuation(manga.id, refresh = false)
            ui.continuation = engine.continuation(manga.id, refresh = true)
            return@LaunchedEffect
        }
        ui.continuation = emptyList()
        ui.other = null
        // Looked for by itself on works in the reading language and on the library's works
        val lookByItself = manga.favorite || ui.baseLang.startsWith("ar")
        if (!ui.dismissed && lookByItself) search(context, ui, force = false)
    }
    return ui
}

private suspend fun search(context: android.content.Context, ui: FusionUi, force: Boolean) {
    ui.searching = true
    val found = runCatching { context.appGraph.chapterFusion.findOffers(ui.manga, force) }.getOrDefault(emptyList())
    ui.choices = found
    ui.offer = found.firstOrNull()
    ui.searching = false
    ui.searched = true
}

/** The banner on top of the chapters: the fusion offer, the fused-mode strip, or the manual search. */
@Composable
fun FusionBanner(ui: FusionUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The reader always picks the work to join with: nothing is fused without seeing it first
    var picking by remember { mutableStateOf(false) }
    val fusion = ui.fusion
    val offer = ui.offer
    when {
        fusion != null -> FusedStrip(ui, fusion, modifier, onUnfuse = {
            context.appGraph.chapterFusion.unfuse(ui.manga.id)
            ui.dismissed = true
        }, onChange = { picking = true })
        offer != null && !ui.dismissed -> OfferBanner(ui, offer, modifier, onFuse = { picking = true }, onDismiss = {
            context.appGraph.chapterFusion.dismiss(ui.manga.id)
            ui.dismissed = true
        })
        else -> ManualSearch(ui, modifier) { picking = true }
    }
    if (picking) {
        FusionPicker(
            ui,
            onPick = {
                context.appGraph.chapterFusion.fuse(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun OfferBanner(ui: FusionUi, offer: ChapterFusion.Offer, modifier: Modifier, onFuse: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.55f)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlowIcon(MaterialSymbols.Rounded.CallMerge, MaterialTheme.colorScheme.primary, size = 40, pulse = true)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(ATMR.strings.fusion_offer, number(offer.oursMax), offer.otherSourceName, number(offer.theirsMax)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(MaterialSymbols.Rounded.Close, contentDescription = stringResource(ATMR.strings.fusion_no), tint = Color.White.copy(alpha = 0.7f))
            }
        }
        ManhwaKit.NeonButton(
            text = stringResource(ATMR.strings.fusion_yes),
            icon = MaterialSymbols.Rounded.CallMerge,
            modifier = Modifier.fillMaxWidth(),
            onClick = onFuse,
        )
    }
}

@Composable
private fun FusedStrip(ui: FusionUi, fusion: ChapterFusion.Fusion, modifier: Modifier, onUnfuse: () -> Unit, onChange: () -> Unit) {
    val ours = range(ui.oursMin, ui.oursMax)
    val theirsTop = ui.continuation.maxOfOrNull { it.number }
    val theirsLow = ui.continuation.minOfOrNull { it.number }
    val theirs = range(theirsLow, theirsTop)
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), accent = true, alpha = 0.45f)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MaterialSymbols.Rounded.CallMerge, contentDescription = null, tint = Ember, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(ATMR.strings.fusion_mode, langLabel(ui.baseLang), ours, langCode(fusion.otherLang), theirs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManhwaKit.GhostButton(stringResource(ATMR.strings.fusion_change), icon = MaterialSymbols.Rounded.SwapHoriz, modifier = Modifier.weight(1f), onClick = onChange)
            ManhwaKit.GhostButton(stringResource(ATMR.strings.fusion_unfuse), icon = MaterialSymbols.Rounded.CallSplit, modifier = Modifier.weight(1f), onClick = onUnfuse)
        }
    }
}

@Composable
private fun ManualSearch(ui: FusionUi, modifier: Modifier, onSearch: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp), alpha = 0.3f)
            .clickable(enabled = !ui.searching, onClick = onSearch)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.searching) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(MaterialSymbols.Rounded.CallMerge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                ui.searching -> stringResource(ATMR.strings.fusion_searching)
                ui.searched && ui.offer == null -> stringResource(ATMR.strings.fusion_none)
                else -> stringResource(ATMR.strings.fusion_search)
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

/**
 * The works found on the other sources, each with its cover, source and last chapter, for the reader to pick
 * the right one (exact title matches first); a different name can be searched for too.
 */
@Composable
private fun FusionPicker(ui: FusionUi, onPick: (ChapterFusion.Offer) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(ui.manga.title) }
    var results by remember { mutableStateOf(ui.choices) }
    var loading by remember { mutableStateOf(true) }
    fun run(text: String?) {
        loading = true
        scope.launch {
            results = runCatching { context.appGraph.chapterFusion.searchCandidates(ui.manga, text) }.getOrDefault(emptyList())
            loading = false
        }
    }
    LaunchedEffect(Unit) { run(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(ATMR.strings.fusion_cancel)) } },
        title = { Text(stringResource(ATMR.strings.fusion_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(ATMR.strings.fusion_pick_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(ATMR.strings.fusion_search_name)) },
                    trailingIcon = {
                        IconButton(onClick = { run(query) }) {
                            Icon(MaterialSymbols.Rounded.Search, contentDescription = null)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(ATMR.strings.fusion_searching), style = MaterialTheme.typography.bodySmall)
                    }
                    results.isEmpty() -> Text(stringResource(ATMR.strings.fusion_none), style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results, key = { it.other.id }) { offer ->
                        CandidateRow(offer, current = offer.other.id == ui.fusion?.otherMangaId) { onPick(offer) }
                    }
                }
            }
        },
    )
}

@Composable
private fun CandidateRow(offer: ChapterFusion.Offer, current: Boolean, onClick: () -> Unit) {
    val sure = offer.exact
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(16.dp), accent = current, alpha = 0.4f)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(data = offer.other, modifier = Modifier.width(46.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(offer.other.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${offer.otherSourceName} · ${langCode(offer.otherLang)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(ATMR.strings.fusion_up_to, number(offer.theirsMax)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (offer.theirsMax > offer.oursMax) Ember else Color.White.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                val tag = if (sure) Mint else Amber
                Text(
                    stringResource(if (sure) ATMR.strings.fusion_exact else ATMR.strings.fusion_close),
                    style = MaterialTheme.typography.labelSmall,
                    color = tag,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tag.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The continuation's chapters, newest first, above this work's own chapters. */
fun LazyListScope.fusionContinuationItems(ui: FusionUi) {
    val fusion = ui.fusion ?: return
    items(ui.continuation, key = { "fusion-${it.chapter.id}" }) { item ->
        ContinuationRow(ui, fusion, item)
    }
}

@Composable
private fun ContinuationRow(ui: FusionUi, fusion: ChapterFusion.Fusion, item: ChapterFusion.Continuation) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = remember { context.appGraph }
    val queue by graph.downloadManager.queueState.collectAsState()
    val queued = queue.firstOrNull { it.chapter.id == item.chapter.id }
    val status = queued?.statusFlow?.collectAsState()?.value
    val other = ui.other
    val downloaded = remember(queue, other) {
        other != null && graph.downloadManager.isChapterDownloaded(item.chapter.name, item.chapter.scanlator, item.chapter.url, other.title, other.source)
    }
    val read = item.chapter.read
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .glass(RoundedCornerShape(20.dp), alpha = 0.4f)
            .clickable { context.startActivity(ReaderActivity.newIntent(context, item.chapter.mangaId, item.chapter.id)) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.chapter.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = if (read) 0.45f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ember.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MaterialSymbols.Rounded.Translate, contentDescription = null, tint = Ember, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(ATMR.strings.fusion_badge_next, langCode(fusion.otherLang), fusion.otherSourceName),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ember,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Translate ahead, from here
        IconButton(onClick = {
            val manga = other ?: return@IconButton
            scope.launch { translate(context, manga, item) }
        }) {
            Icon(MaterialSymbols.Rounded.Translate, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
        }
        // Download, or stop the download that is waiting
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                status == Download.State.QUEUE || status == Download.State.DOWNLOADING -> IconButton(onClick = {
                    scope.launch(Dispatchers.IO) { graph.downloadManager.cancelQueuedDownloads(listOfNotNull(queued)) }
                }) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Ember)
                        Icon(MaterialSymbols.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                downloaded -> Icon(MaterialSymbols.RoundedFilled.CheckCircle, contentDescription = null, tint = Mint, modifier = Modifier.size(24.dp))
                else -> IconButton(onClick = {
                    val manga = other ?: return@IconButton
                    scope.launch(Dispatchers.IO) { graph.downloadManager.downloadChapters(manga, listOf(item.chapter)) }
                }) {
                    Icon(MaterialSymbols.Rounded.Download, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

private suspend fun translate(context: android.content.Context, manga: Manga, item: ChapterFusion.Continuation) {
    withContext(Dispatchers.IO) { context.appGraph.translationManager.translateChapter(manga, item.chapter) }
    Toast.makeText(context, context.stringResource(ATMR.strings.fusion_translate_queued), Toast.LENGTH_SHORT).show()
}

private fun langLabel(lang: String): String =
    runCatching { java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.getDefault()) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: lang.uppercase()

private fun langCode(lang: String): String = lang.substringBefore('-').uppercase().ifBlank { "EN" }

private fun range(low: Double?, high: Double?): String = when {
    low == null || high == null -> ""
    low == high -> number(low)
    else -> "${number(low)}–${number(high)}"
}

private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
