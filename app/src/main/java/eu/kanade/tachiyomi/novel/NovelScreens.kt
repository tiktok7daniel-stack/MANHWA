package eu.kanade.tachiyomi.novel

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import eu.kanade.presentation.components.rememberCoverPalette
import eu.kanade.presentation.manga.components.BackdropScene
import eu.kanade.presentation.manga.components.CoverTheme
import eu.kanade.presentation.manga.components.PageBackdrop
import eu.kanade.presentation.manga.components.PosterHero
import eu.kanade.tachiyomi.music.MusicMiniBar
import eu.kanade.tachiyomi.music.MusicStore
import eu.kanade.tachiyomi.music.WorkMusicEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.source.service.AdultFilter
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.nova.novaAppear
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.toast
import ireader.core.source.CatalogSource
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.Filter
import ireader.core.source.model.MangaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.AutoStories
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.rounded.MenuBook
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.roundedfilled.Favorite
import mihon.icons.materialsymbols.roundedfilled.Keep
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPref

/** The chapter lists of the novels opened in this run of the app, so the reader can go on to the next one. */
internal object NovelSession {
    val chapters = HashMap<String, List<ChapterInfo>>()
}

/** Whether a novel source is kept out by the +18 switches of the source hub (as the manga sources are). */
@Composable
private fun rememberNovelKeepsOut(): (CatalogSource) -> Boolean {
    val context = LocalContext.current
    val prefs = remember { context.appGraph.sourcePreferences }
    val manager = remember { context.appGraph.novelExtensionManager }
    val hide by prefs.hideAdult.collectPref()
    val only by prefs.onlyAdult.collectPref()
    val installed by manager.installed.collectAsState()
    return remember(hide, only, installed) {
        { source ->
            val adult = manager.extensionOf(source.id)?.nsfw == true || AdultFilter.isAdultName(source.name)
            AdultFilter.keepsOut(adult = adult, hide = hide, only = only)
        }
    }
}

// ---------------------------------------------------------------- The three kinds of story

/** Manhwa, novels and anime: the chips that switch a screen between its three sides. */
@Composable
fun MediaSwitch(kind: Int, onChange: (Int) -> Unit, padding: PaddingValues, content: @Composable (PaddingValues) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content(PaddingValues(top = padding.calculateTopPadding() + SWITCH_HEIGHT, bottom = padding.calculateBottomPadding()))
        Row(
            Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxWidth()
                .height(SWITCH_HEIGHT)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManhwaKit.Chip(stringResource(ATMR.strings.library_manhwa), selected = kind == KIND_MANGA) { onChange(KIND_MANGA) }
            ManhwaKit.Chip(stringResource(ATMR.strings.novel_kind), selected = kind == KIND_NOVEL) { onChange(KIND_NOVEL) }
            ManhwaKit.Chip(stringResource(ATMR.strings.library_anime), selected = kind == KIND_ANIME) { onChange(KIND_ANIME) }
        }
    }
}

const val KIND_MANGA = 0
const val KIND_NOVEL = 1
const val KIND_ANIME = 2

private val SWITCH_HEIGHT = 48.dp

// ---------------------------------------------------------------- Sources and extensions

/** The novel sources, by language (the languages turned on), each opening its catalogue. */
@Composable
fun NovelSourcesList(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.appGraph }
    val manager = graph.novelExtensionManager
    val installed by manager.installed.collectAsState()
    val languages by graph.sourcePreferences.enabledLanguages.collectPref()
    val keepsOut = rememberNovelKeepsOut()
    val sources = remember(installed, languages, keepsOut) {
        installed.filterNot { keepsOut(it.source) }
            .filter { it.lang in languages || it.lang == "all" || it.lang == "multi" || it.lang.isBlank() }
            .sortedWith(compareBy({ it.lang != "ar" }, { it.lang }, { it.name.lowercase() }))
    }
    if (sources.isEmpty()) {
        EmptyNovels(contentPadding, stringResource(ATMR.strings.novel_no_sources))
        return
    }
    val pinned by graph.sourcePreferences.novelPinnedSources.collectPref()
    val available by manager.available.collectAsState()
    val iconUrls = remember(available) { available.associate { it.pkg to it.iconUrl } }
    val pins = remember(sources, pinned) { sources.filter { it.source.id.toString() in pinned } }
    val rest = remember(sources, pinned) { sources.filterNot { it.source.id.toString() in pinned } }

    @Composable
    fun SourceRow(ext: NovelExt) {
        val isPinned = ext.source.id.toString() in pinned
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .glass(RoundedCornerShape(18.dp), alpha = if (isPinned) 0.5f else 0.35f)
                .pressClickable { navigator.push(NovelBrowseScreen(ext.source.id)) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtIcon(ext, fallbackUrl = iconUrls[ext.pkg])
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ext.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(LocaleHelper.getSourceDisplayName(ext.lang, context), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
            }
            // The pin keeps a favourite source on top
            IconButton(onClick = {
                val pref = graph.sourcePreferences.novelPinnedSources
                val id = ext.source.id.toString()
                pref.set(if (id in pref.get()) pref.get() - id else pref.get() + id)
            }) {
                Icon(
                    if (isPinned) MaterialSymbols.RoundedFilled.Keep else MaterialSymbols.Rounded.Keep,
                    contentDescription = stringResource(ATMR.strings.novel_pin),
                    tint = if (isPinned) MaterialTheme.colorScheme.primary else ManhwaInk.muted,
                )
            }
        }
    }

    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        if (pins.isNotEmpty()) {
            item(key = "pinned-title") { Section(stringResource(ATMR.strings.novel_pinned)) }
            items(pins, key = { "pin-${it.pkg}" }) { ext -> SourceRow(ext) }
        }
        var lastLang: String? = null
        rest.forEach { ext ->
            if (ext.lang != lastLang) {
                lastLang = ext.lang
                item(key = "lang-${ext.lang}") {
                    Section(LocaleHelper.getSourceDisplayName(ext.lang, context))
                }
            }
            item(key = ext.pkg) { SourceRow(ext) }
        }
    }
}

/** The novel extensions: the repositories, what is installed (updates, removal) and what can be installed. */
@Composable
fun NovelExtensionsList(contentPadding: PaddingValues, query: String?) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    val manager = remember { context.appGraph.novelExtensionManager }
    val installed by manager.installed.collectAsState()
    val available by manager.available.collectAsState()
    val loading by manager.loading.collectAsState()
    var repoText by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf(manager.repos()) }
    val busy = remember { mutableStateListOf<String>() }
    val q = query.orEmpty().trim().lowercase()
    val updates = remember(installed, available) { manager.updates() }
    val installedPkgs = remember(installed) { installed.map { it.pkg }.toSet() }
    val offered = remember(available, installedPkgs, q) {
        available.filter { it.pkg !in installedPkgs && (q.isEmpty() || it.name.lowercase().contains(q)) }
    }
    val installFailed = stringResource(ATMR.strings.novel_install_failed)

    fun install(ext: AvailableNovelExt) {
        busy += ext.pkg
        scope.launch {
            val ok = manager.install(ext)
            busy -= ext.pkg
            if (!ok) context.toast(installFailed)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (installed.isNotEmpty()) {
            item(key = "installed-title") { Section(stringResource(ATMR.strings.novel_installed)) }
            items(installed, key = { "i-${it.pkg}" }) { ext ->
                ExtRow(
                    icon = { ExtIcon(ext) },
                    name = ext.name,
                    sub = "${LocaleHelper.getSourceDisplayName(ext.lang, context)} · v${ext.versionName}",
                    nsfw = ext.nsfw,
                ) {
                    val update = updates[ext.pkg]
                    when {
                        ext.pkg in busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        update != null -> ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh, active = true) { install(update) }
                        !ext.shared -> ManhwaKit.IconOrb(MaterialSymbols.Rounded.Delete) { scope.launch { manager.uninstall(ext.pkg) } }
                    }
                }
            }
        }
        item(key = "available-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Section(stringResource(ATMR.strings.novel_available)) }
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (offered.isEmpty() && !loading) {
            item(key = "available-empty") {
                Text(
                    stringResource(if (repos.isEmpty()) ATMR.strings.novel_no_repo else ATMR.strings.novel_none_left),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ManhwaInk.muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(offered, key = { "a-${it.pkg}" }) { ext ->
            ExtRow(
                icon = {
                    AsyncImage(
                        model = ext.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                },
                name = ext.name,
                sub = "${LocaleHelper.getSourceDisplayName(ext.lang, context)} · v${ext.versionName}",
                nsfw = ext.nsfw,
            ) {
                if (ext.pkg in busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Download, active = true) { install(ext) }
                }
            }
        }
    }
}

@Composable
private fun ExtRow(icon: @Composable () -> Unit, name: String, sub: String, nsfw: Boolean, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.3f).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (nsfw) {
                    Spacer(Modifier.width(6.dp))
                    Text("18+", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                }
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { action() }
    }
}

/** A source's icon: the extension's own, else the one its repository shows, else a book. */
@Composable
private fun ExtIcon(ext: NovelExt?, size: Int = 42, fallbackUrl: String? = null) {
    val bitmap = remember(ext?.pkg) { runCatching { ext?.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)))
    } else if (fallbackUrl != null) {
        AsyncImage(
            model = fallbackUrl,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else {
        Box(Modifier.size(size.dp).glass(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(MaterialSymbols.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyNovels(contentPadding: PaddingValues, text: String) {
    Box(Modifier.fillMaxSize().padding(contentPadding).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(MaterialSymbols.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = ManhwaInk.muted, textAlign = TextAlign.Center)
        }
    }
}

/** A novel's cover with its title under it; covers keep the book shape (2:3) and are never squared. */
@Composable
fun NovelCard(title: String, cover: String, modifier: Modifier = Modifier, badge: String? = null, onClick: () -> Unit) {
    Column(modifier.pressClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            AsyncImage(
                model = cover.ifBlank { null },
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
    }
}

// ---------------------------------------------------------------- A source's catalogue

/** A novel source: its lists (popular, latest, as the source names them) and its search, page after page. */
class NovelBrowseScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { context.appGraph.novelExtensionManager.source(sourceId) }
        val scope = rememberCoroutineScope()
        val items = remember { mutableStateListOf<MangaInfo>() }
        val listings = remember(source) { runCatching { source?.getListings().orEmpty() }.getOrDefault(emptyList()) }
        var mode by rememberSaveable { mutableIntStateOf(0) }
        var query by rememberSaveable { mutableStateOf("") }
        var searching by remember { mutableStateOf(false) }
        var page by remember { mutableIntStateOf(1) }
        var hasNext by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf<String?>(null) }

        fun load(reset: Boolean) {
            val s = source ?: return
            if (reset) {
                items.clear()
                page = 1
            }
            loading = true
            failed = null
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (searching && query.isNotBlank()) {
                            s.getMangaList(searchFilters(s, query.trim()), page)
                        } else {
                            s.getMangaList(listings.getOrNull(mode), page)
                        }
                    }
                    items.addAll(result.mangas.filter { m -> m.key.isNotBlank() && items.none { it.key == m.key } })
                    hasNext = result.hasNextPage && result.mangas.isNotEmpty()
                    page += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed = e.message ?: e.javaClass.simpleName
                } finally {
                    loading = false
                }
            }
        }
        LaunchedEffect(Unit) { if (items.isEmpty()) load(reset = true) }

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = source?.name ?: "", subtitle = source?.lang?.uppercase(), onBack = navigator::pop)
                if (source == null) {
                    EmptyNovels(PaddingValues(), stringResource(ATMR.strings.novel_source_missing))
                    return@Column
                }
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManhwaKit.SearchField(query, stringResource(ATMR.strings.novel_search), onValueChange = { query = it }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Search, active = true) {
                        searching = query.isNotBlank()
                        load(reset = true)
                    }
                }
                if (listings.size > 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(listings) { i, listing ->
                            ManhwaKit.Chip(listing.name, selected = !searching && mode == i) {
                                searching = false
                                query = ""
                                mode = i
                                load(reset = true)
                            }
                        }
                    }
                }
                failed?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { load(reset = items.isEmpty()) },
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(items, key = { i, m -> "${m.key}#$i" }) { i, novel ->
                        if (i >= items.size - 6 && hasNext && !loading) LaunchedEffect(items.size) { load(reset = false) }
                        NovelCard(novel.title, novel.cover, Modifier.novaAppear(if (i < 12) i else i % 3)) {
                            navigator.push(NovelDetailsScreen(sourceId, novel.key, novel.title, novel.cover))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator()
                                items.isEmpty() && failed == null -> Text(stringResource(ATMR.strings.novel_nothing), color = ManhwaInk.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The source's filters with [query] as the title searched (IReader sources search through their title filter). */
private fun searchFilters(source: CatalogSource, query: String): List<Filter<*>> {
    val filters = source.getFilters()
    val title = filters.firstOrNull { it is Filter.Title } ?: filters.firstOrNull { it is Filter.Text }
    if (title is Filter.Text) title.value = query
    return if (title == null) filters + Filter.Title().apply { value = query } else filters
}

// ---------------------------------------------------------------- One novel

/** A novel, on the same poster page as a manhwa: its numbers, the library and continue buttons, its story and chapters. */
class NovelDetailsScreen(
    private val sourceId: Long,
    private val url: String,
    private val title: String,
    private val cover: String,
) : Screen() {

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val store = graph.novelStore
        val source = remember { graph.novelExtensionManager.source(sourceId) }
        val scope = rememberCoroutineScope()
        val novelKey = NovelStore.key(sourceId, url)
        val library by store.library.collectAsState()
        val progress by store.progress.collectAsState()
        val entry = library[novelKey]
        var info by remember { mutableStateOf(entry?.toInfo() ?: MangaInfo(key = url, title = title, cover = cover)) }
        var chapters by remember { mutableStateOf(NovelSession.chapters[novelKey].orEmpty()) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf<String?>(null) }
        var newestFirst by rememberSaveable { mutableStateOf(false) }
        var expanded by rememberSaveable { mutableStateOf(false) }
        var find by rememberSaveable { mutableStateOf("") }

        fun refresh() {
            val s = source ?: return
            loading = true
            failed = null
            scope.launch {
                try {
                    val details = withContext(Dispatchers.IO) { runCatching { s.getMangaDetails(info, emptyList()) }.getOrNull() }
                    val merged = (details ?: info).let { d -> if (d.key.isBlank()) d.copy(key = url) else d }
                    info = merged.copy(title = merged.title.ifBlank { title }, cover = merged.cover.ifBlank { cover })
                    val list = withContext(Dispatchers.IO) { s.getChapterList(info, emptyList()) }.filter { it.key.isNotBlank() }
                    chapters = list
                    NovelSession.chapters[novelKey] = list
                    store.remember(sourceId, info, list.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed = e.message ?: e.javaClass.simpleName
                } finally {
                    loading = false
                }
            }
        }
        LaunchedEffect(Unit) { if (chapters.isEmpty()) refresh() else store.remember(sourceId, info, chapters.size) }

        val readCount = remember(progress, chapters) { chapters.count { store.progressOf(novelKey, it.key)?.read == true } }
        val last = remember(progress) { store.history().firstOrNull { it.first.key == novelKey }?.second }
        val next = remember(chapters, progress) {
            last?.let { p -> chapters.firstOrNull { it.key == p.chapterUrl }?.takeUnless { p.read } ?: chapters.getOrNull(chapters.indexOfFirst { it.key == p.chapterUrl } + 1) }
                ?: chapters.firstOrNull()
        }
        val shown = remember(chapters, newestFirst, find) {
            val base = if (newestFirst) chapters.asReversed() else chapters
            if (find.isBlank()) base else base.filter { it.name.contains(find.trim(), ignoreCase = true) || it.number.toString().startsWith(find.trim()) }
        }

        fun open(chapter: ChapterInfo) {
            navigator.push(NovelReaderScreen(sourceId, url, chapter.key))
        }

        // MANHWA: the same page as a manhwa's: the poster with its framed title, the cover's colours and light, the
        // scene behind, and the novel's own music
        val workId = remember(novelKey) { NovelStore.workId(novelKey) }
        val palette = rememberCoverPalette(workId, info.cover)
        val musicStore = remember { MusicStore.get(context) }
        val pickedScene by remember(workId) { musicStore.sceneFlow(workId) }.collectAsState(null)
        val listState = rememberLazyListState()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        WorkMusicEffect(workId)
        CoverTheme(palette) {
        PageBackdrop(
            scene = BackdropScene.of(pickedScene) ?: BackdropScene.forColor(palette?.primary, workId),
            custom = remember(pickedScene) { musicStore.sceneFile(pickedScene) },
        ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "hero") {
                    Box(Modifier.fillMaxWidth()) {
                        PosterHero(
                            workId = workId,
                            title = info.title.ifBlank { title },
                            author = info.author,
                            genres = info.genres,
                            favorite = entry?.favorite == true,
                            coverData = info.cover.ifBlank { null },
                            chaptersRead = readCount,
                            chapterCount = chapters.size,
                            topPadding = topInset + 48.dp,
                            scrollProvider = {
                                if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 2000f
                            },
                            onCoverClick = {},
                            onAuthorClick = {},
                            onTagSearch = {},
                            onContinue = null,
                            continueLabel = "",
                        )
                        ManhwaKit.TopBar(title = "", onBack = navigator::pop) {
                            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Refresh) { refresh() }
                        }
                    }
                }
                item(key = "actions") {
                    Row(Modifier.novaAppear(1).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            next == null -> stringResource(ATMR.strings.novel_read)
                            last == null -> stringResource(ATMR.strings.novel_start)
                            else -> stringResource(ATMR.strings.novel_continue, next.name)
                        }
                        ManhwaKit.NeonButton(label, modifier = Modifier.weight(1f), icon = MaterialSymbols.RoundedFilled.PlayArrow) { next?.let(::open) }
                        val fav = entry?.favorite == true
                        ManhwaKit.IconOrb(if (fav) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite, active = fav, size = 52) {
                            val e = entry ?: store.remember(sourceId, info, chapters.size)
                            store.setFavorite(e, !fav)
                        }
                    }
                }
                item(key = "stats") {
                    Row(
                        Modifier.novaAppear(2).fillMaxWidth().padding(horizontal = 16.dp).glass(RoundedCornerShape(20.dp), alpha = 0.35f).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Stat("${chapters.size}", stringResource(ATMR.strings.nova_stat_chapters))
                        Stat("$readCount", stringResource(ATMR.strings.nova_stat_read))
                        Stat(statusLabel(info.status), stringResource(ATMR.strings.novel_status))
                    }
                }
                if (info.description.isNotBlank()) {
                    item(key = "story") {
                        Column(Modifier.novaAppear(3).animateContentSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                            Text(stringResource(ATMR.strings.nova_story), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                info.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ManhwaInk.muted,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                stringResource(if (expanded) ATMR.strings.nova_less else ATMR.strings.nova_more),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded },
                            )
                            // The poster carries the first five genres; the rest are here
                            val moreGenres = info.genres.filter { it.isNotBlank() }.drop(5).take(12)
                            if (moreGenres.isNotEmpty()) {
                                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    moreGenres.forEach { g ->
                                        Text(g, style = MaterialTheme.typography.labelMedium, modifier = Modifier.glass(RoundedCornerShape(50), alpha = 0.3f).padding(horizontal = 10.dp, vertical = 5.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "chapters-head") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(ATMR.strings.nova_chapters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            ManhwaKit.Chip(stringResource(if (newestFirst) ATMR.strings.nova_newest_first else ATMR.strings.nova_oldest_first), selected = true) { newestFirst = !newestFirst }
                        }
                        Spacer(Modifier.height(8.dp))
                        ManhwaKit.SearchField(find, stringResource(ATMR.strings.nova_search_chapter), onValueChange = { find = it })
                    }
                }
                when {
                    loading && chapters.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    failed != null && chapters.isEmpty() -> item(key = "failed") {
                        Text(
                            failed.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp).clickable { refresh() },
                        )
                    }
                }
                items(shown, key = { it.key }) { chapter ->
                    val p = progress[novelKeyChapter(novelKey, chapter.key)]
                    Row(
                        Modifier.fillMaxWidth().clickable { open(chapter) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                chapter.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (p?.read == true) FontWeight.Normal else FontWeight.SemiBold,
                                color = if (p?.read == true) ManhwaInk.muted else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (p != null && !p.read && p.fraction > 0f) {
                                ManhwaKit.GlowBar(p.fraction, Modifier.padding(top = 6.dp).width(120.dp))
                            }
                        }
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { store.setRead(novelKey, chapter.key, chapter.name, p?.read != true) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MaterialSymbols.Rounded.Check,
                                contentDescription = null,
                                tint = if (p?.read == true) MaterialTheme.colorScheme.primary else ManhwaInk.muted.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
            MusicMiniBar(
                mangaId = workId,
                title = info.title.ifBlank { title },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 24.dp),
            )
        }
        }
        }
    }
}

/** The key [NovelStore] keeps a chapter's progress under. */
internal fun novelKeyChapter(novelKey: String, chapterUrl: String) = "$novelKey#$chapterUrl"

@Composable
private fun statusLabel(status: Long): String = stringResource(
    when (status) {
        MangaInfo.ONGOING -> ATMR.strings.novel_ongoing
        MangaInfo.COMPLETED, MangaInfo.PUBLISHING_FINISHED -> ATMR.strings.novel_completed
        else -> ATMR.strings.novel_unknown
    },
)

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(value: String, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
    }
}

// ---------------------------------------------------------------- Library

/** The novels in the library and the ones being read, for the library's novel side. */
@Composable
fun NovelLibraryContent(contentPadding: PaddingValues, navigator: Navigator) {
    val context = LocalContext.current
    val store = remember { context.appGraph.novelStore }
    val library by store.library.collectAsState()
    val progress by store.progress.collectAsState()
    val favorites = remember(library) { store.favorites() }
    // The library holds only the novels with a heart; what was read lives in the history
    val reading = emptyList<Pair<NovelEntry, NovelProgress>>()
    if (favorites.isEmpty()) {
        EmptyNovels(contentPadding, stringResource(ATMR.strings.novel_library_empty))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (reading.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "reading-title") { Section(stringResource(ATMR.strings.novel_reading)) }
            item(span = { GridItemSpan(maxLineSpan) }, key = "reading") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(reading, key = { it.first.key }) { (novel, p) ->
                        ContinueCard(novel, p) { navigator.push(NovelReaderScreen(novel.sourceId, novel.url, p.chapterUrl)) }
                    }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "fav-title") { Section(stringResource(ATMR.strings.novel_library)) }
            itemsIndexed(favorites, key = { _, n -> n.key }) { i, novel ->
                NovelCard(novel.title, novel.cover, Modifier.novaAppear(if (i < 12) i else 0), badge = novel.chapterCount.takeIf { it > 0 }?.toString()) {
                    navigator.push(NovelDetailsScreen(novel.sourceId, novel.url, novel.title, novel.cover))
                }
            }
        }
    }
}

/** A novel being read: its cover, the chapter it is at and how far in, opening straight into the reader. */
@Composable
fun ContinueCard(novel: NovelEntry, progress: NovelProgress, onClick: () -> Unit) {
    Row(
        Modifier.width(280.dp).height(120.dp).glass(RoundedCornerShape(20.dp), alpha = 0.4f).pressClickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = novel.cover.ifBlank { null },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.height(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(novel.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(progress.chapterName, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ManhwaKit.GlowBar(if (progress.read) 1f else progress.fraction)
        }
    }
}

/** Opens [novel] where it was left, or its page when nothing of it was read yet. */
fun openNovel(navigator: Navigator, novel: NovelEntry, progress: NovelProgress?) {
    if (progress != null) {
        navigator.push(NovelReaderScreen(novel.sourceId, novel.url, progress.chapterUrl))
    } else {
        navigator.push(NovelDetailsScreen(novel.sourceId, novel.url, novel.title, novel.cover))
    }
}

/** Small round mark of the library state, for the shells' cards. */
@Composable
fun NovelFavoriteMark(on: Boolean) {
    if (!on) return
    Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(MaterialSymbols.RoundedFilled.Favorite, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}


/** The novels read, newest first, each opening where it was left (the history's novel side). */
@Composable
fun NovelHistoryContent(contentPadding: PaddingValues, navigator: Navigator) {
    val context = LocalContext.current
    val store = remember { context.appGraph.novelStore }
    val library by store.library.collectAsState()
    val progress by store.progress.collectAsState()
    val history = remember(library, progress) { store.history() }
    if (history.isEmpty()) {
        EmptyNovels(contentPadding, stringResource(ATMR.strings.novel_history_empty))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(history, key = { it.first.key }) { (novel, p) ->
            Box(Modifier.fillMaxWidth()) {
                ContinueCard(novel, p) { navigator.push(NovelReaderScreen(novel.sourceId, novel.url, p.chapterUrl)) }
            }
        }
    }
}

/** The novel extension repositories: IReader's in one tap, one of your own by its address, or take one away. */
class NovelReposScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val manager = remember { context.appGraph.novelExtensionManager }
        var repoText by remember { mutableStateOf("") }
        var repos by remember { mutableStateOf(manager.repos()) }
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.novel_repos), onBack = navigator::pop)
                Box(Modifier.padding(horizontal = 14.dp)) {
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), alpha = 0.35f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(ATMR.strings.novel_repos), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(ATMR.strings.novel_repos_hint), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
                        repos.forEach { repo ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(MaterialSymbols.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(repo, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { manager.removeRepo(repo); repos = manager.repos() }) {
                                    Icon(MaterialSymbols.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (NovelExtensionManager.IREADER_REPO !in repos) {
                            ManhwaKit.NeonButton(stringResource(ATMR.strings.novel_add_ireader), modifier = Modifier.fillMaxWidth(), icon = MaterialSymbols.Rounded.Add) {
                                manager.addRepo(NovelExtensionManager.IREADER_REPO)
                                repos = manager.repos()
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ManhwaKit.SearchField(repoText, stringResource(ATMR.strings.novel_repo_hint), onValueChange = { repoText = it }, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Add, active = true) {
                                if (manager.addRepo(repoText)) {
                                    repoText = ""
                                    repos = manager.repos()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
