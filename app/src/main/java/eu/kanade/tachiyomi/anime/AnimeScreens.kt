package eu.kanade.tachiyomi.anime

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaActionButton
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import mihon.icons.materialsymbols.rounded.Public
import tachiyomi.domain.library.service.LibraryPreferences
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.source.service.AdultFilter
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.presentation.browse.components.GlowHeader
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.Keep
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.roundedfilled.Keep
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.core.common.i18n.stringResource as ctxString
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPref

/** Whether an anime source is kept out by the +18 switches of the source hub (as the manga sources are). */
@Composable
private fun rememberAnimeKeepsOut(): (AnimeCatalogueSource) -> Boolean {
    val context = LocalContext.current
    val prefs = remember { context.appGraph.sourcePreferences }
    val manager = remember { context.appGraph.animeExtensionManager }
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

// ---------------------------------------------------------------- Sources and extensions

/** The anime sources, as the manga sources: pinned first, then by language (the languages turned on), Latest and pin. */
@Composable
fun AnimeSourcesList(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.appGraph }
    val manager = graph.animeExtensionManager
    val installed by manager.installed.collectAsState()
    val languages by graph.sourcePreferences.enabledLanguages.collectPref()
    val pinned by graph.sourcePreferences.animePinnedSources.collectPref()
    val keepsOut = rememberAnimeKeepsOut()
    val sources = remember(installed, languages, keepsOut) {
        manager.sources().filterNot(keepsOut).filter { it.lang in languages || it.lang == "all" || it.lang == "other" }
    }
    val grouped = remember(sources, pinned) {
        val pins = sources.filter { it.id.toString() in pinned }.sortedBy { it.name.lowercase() }
        val rest = sources.groupBy { it.lang }.toSortedMap().mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
        buildList<Pair<String, List<AnimeCatalogueSource>>> {
            if (pins.isNotEmpty()) add(PINNED to pins)
            rest.forEach { (lang, list) -> add(lang to list) }
        }
    }
    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        if (sources.isEmpty()) item { Box(Modifier.padding(16.dp)) { Hint(stringResource(ATMR.strings.anime_no_sources)) } }
        grouped.forEach { (lang, list) ->
            item(key = "h$lang") {
                GlowHeader(if (lang == PINNED) stringResource(MR.strings.pinned_sources) else LocaleHelper.getSourceDisplayName(lang, context))
            }
            items(list, key = { "$lang${it.id}" }) { source ->
                val isPinned = source.id.toString() in pinned
                BaseBrowseItem(
                    onClickItem = { navigator.push(AnimeBrowseScreen(source.id)) },
                    icon = { ExtIcon(manager.extensionOf(source.id), 48) },
                    content = {
                        Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                            Text(source.name, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(LocaleHelper.getSourceDisplayName(source.lang, context), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
                        }
                    },
                    action = {
                        if (source.supportsLatest) {
                            Pill(stringResource(MR.strings.latest)) { navigator.push(AnimeBrowseScreen(source.id, latest = true)) }
                        }
                        IconButton(onClick = {
                            val pref = graph.sourcePreferences.animePinnedSources
                            val id = source.id.toString()
                            pref.set(if (id in pref.get()) pref.get() - id else pref.get() + id)
                        }) {
                            Icon(
                                if (isPinned) MaterialSymbols.RoundedFilled.Keep else MaterialSymbols.Rounded.Keep,
                                contentDescription = null,
                                tint = if (isPinned) MaterialTheme.colorScheme.primary else ManhwaInk.muted,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** The anime extensions, as the manga extensions: the repositories on top, then updates, installed and by language. */
@Composable
fun AnimeExtensionsList(contentPadding: PaddingValues, searchQuery: String?) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.appGraph }
    val manager = graph.animeExtensionManager
    val installed by manager.installed.collectAsState()
    val available by manager.available.collectAsState()
    val loading by manager.loading.collectAsState()
    val languages by graph.sourcePreferences.enabledLanguages.collectPref()
    val hide by graph.sourcePreferences.hideAdult.collectPref()
    val only by graph.sourcePreferences.onlyAdult.collectPref()
    val repos by graph.sourcePreferences.animeRepos.collectPref()
    val scope = rememberCoroutineScope()
    var repoText by remember { mutableStateOf("") }
    val busy = remember { mutableStateListOf<String>() }
    val q = searchQuery.orEmpty().trim().lowercase()
    fun shown(name: String, nsfw: Boolean) =
        (q.isEmpty() || name.lowercase().contains(q)) && !AdultFilter.keepsOut(adult = nsfw || AdultFilter.isAdultName(name), hide = hide, only = only)
    val updates = remember(installed, available) { manager.updates() }
    val mine = installed.filter { shown(it.name, it.nsfw) }
    val offered = available.filter { a -> installed.none { it.pkg == a.pkg } && shown(a.name, a.nsfw) && (a.lang in languages || a.lang == "all" || a.lang == "other") }
        .groupBy { it.lang }.toSortedMap()

    fun install(ext: AvailableAnimeExt) {
        busy += ext.pkg
        scope.launch {
            if (!manager.install(ext)) context.toast(ATMR.strings.anime_install_failed)
            busy -= ext.pkg
        }
    }

    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        // The repositories live behind the menu (as the manhwa extension stores do)
        item(key = "repos") {
            var menu by remember { mutableStateOf(false) }
            GlowHeader(stringResource(MR.strings.label_extensions)) {
                IconButton(onClick = { scope.launch { manager.refreshAvailable(); manager.reload() } }) {
                    Icon(MaterialSymbols.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MaterialSymbols.Rounded.MoreVert, null) }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(ATMR.strings.anime_repos)) },
                            onClick = {
                                menu = false
                                navigator.push(AnimeReposScreen())
                            },
                        )
                    }
                }
            }
        }
        val withUpdate = mine.filter { it.pkg in updates }
        if (withUpdate.isNotEmpty()) {
            item(key = "hu") { GlowHeader(stringResource(MR.strings.ext_updates_pending)) }
            items(withUpdate, key = { "u" + it.pkg }) { ext ->
                ExtItem({ ExtIcon(ext, 48) }, ext.name, ext.lang, ext.versionName, ext.nsfw, ext.pkg in busy) {
                    Pill(stringResource(MR.strings.ext_update)) { updates[ext.pkg]?.let(::install) }
                }
            }
        }
        val rest = mine.filter { it.pkg !in updates }
        if (rest.isNotEmpty()) {
            item(key = "hi") { GlowHeader(stringResource(MR.strings.ext_installed)) }
            items(rest, key = { "i" + it.pkg }) { ext ->
                ExtItem({ ExtIcon(ext, 48) }, ext.name, ext.lang, ext.versionName, ext.nsfw, ext.pkg in busy) {
                    if (!ext.shared) {
                        IconButton(onClick = { scope.launch { manager.uninstall(ext.pkg) } }) {
                            Icon(MaterialSymbols.Rounded.Delete, null, tint = ManhwaInk.muted)
                        }
                    }
                }
            }
        }
        offered.forEach { (lang, list) ->
            item(key = "ha$lang") { GlowHeader(LocaleHelper.getSourceDisplayName(lang, context)) }
            items(list, key = { "a" + it.pkg }) { ext ->
                ExtItem(
                    { AsyncImage(ext.iconUrl, null, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))) },
                    ext.name, ext.lang, ext.versionName, ext.nsfw, ext.pkg in busy,
                ) {
                    Pill(stringResource(MR.strings.ext_install)) { install(ext) }
                }
            }
        }
    }
}

@Composable
private fun ExtItem(
    icon: @Composable () -> Unit,
    name: String,
    lang: String,
    version: String,
    nsfw: Boolean,
    busy: Boolean,
    action: @Composable () -> Unit,
) {
    val context = LocalContext.current
    BaseBrowseItem(
        icon = { icon() },
        content = {
            Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(LocaleHelper.getSourceDisplayName(lang, context), version, "18+".takeIf { nsfw }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = ManhwaInk.muted,
                )
            }
        },
        action = { if (busy) CircularProgressIndicator(Modifier.size(24.dp)) else action() },
    )
}

/** The accent pill the manga sources use for "Latest". */
@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        color = androidx.compose.ui.graphics.Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(ManhwaKit.accentBrush())
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private const val PINNED = "__pinned__"

/** The manhwa / anime switch on top of a page (Sources, Extensions); [content] gets the padding below it. */
@Composable
fun MangaAnimeSwitch(anime: Boolean, onChange: (Boolean) -> Unit, padding: PaddingValues, content: @Composable (PaddingValues) -> Unit) {
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
            ManhwaKit.Chip(stringResource(ATMR.strings.library_manhwa), selected = !anime) { onChange(false) }
            ManhwaKit.Chip(stringResource(ATMR.strings.library_anime), selected = anime) { onChange(true) }
        }
    }
}

private val SWITCH_HEIGHT = 48.dp

@Composable
private fun ExtIcon(ext: AnimeExt?, size: Int = 42) {
    val bitmap = remember(ext?.pkg) { runCatching { ext?.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)))
    } else {
        Box(Modifier.size(size.dp).glass(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(MaterialSymbols.RoundedFilled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.muted, modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun AnimeCard(title: String, thumbnail: String?, badge: String? = null, onClick: () -> Unit) {
    Column(Modifier.pressClickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            AsyncImage(
                model = thumbnail,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)),
            )
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = ManhwaInk.text,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).glass(RoundedCornerShape(8.dp), accent = true).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.labelMedium, color = ManhwaInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Browse a source

/** One anime source: popular, latest and search, page after page. */
class AnimeBrowseScreen(private val sourceId: Long, private val latest: Boolean = false) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { context.appGraph.animeExtensionManager.source(sourceId) }
        val scope = rememberCoroutineScope()
        val items = remember { mutableStateListOf<SAnime>() }
        var mode by remember { mutableIntStateOf(if (latest) 1 else 0) }
        var query by remember { mutableStateOf("") }
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
                        when {
                            query.isNotBlank() -> s.getSearchAnime(page, query.trim(), s.getFilterList())
                            mode == 1 && s.supportsLatest -> s.getLatestUpdates(page)
                            else -> s.getPopularAnime(page)
                        }
                    }
                    items.addAll(result.animes.filter { a -> items.none { it.url == a.url } })
                    hasNext = result.hasNextPage
                    page += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = e.message ?: e.javaClass.simpleName
                } finally {
                    loading = false
                }
            }
        }
        LaunchedEffect(mode) { load(reset = true) }

        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = source?.name ?: "", subtitle = source?.lang?.uppercase(), onBack = navigator::pop)
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManhwaKit.SearchField(query, stringResource(ATMR.strings.anime_search), onValueChange = { query = it }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Search, active = true) { load(reset = true) }
                }
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManhwaKit.Chip(stringResource(ATMR.strings.anime_popular), selected = mode == 0 && query.isBlank()) { query = ""; mode = 0; load(reset = true) }
                    if (source?.supportsLatest == true) {
                        ManhwaKit.Chip(stringResource(ATMR.strings.anime_latest), selected = mode == 1 && query.isBlank()) { query = ""; mode = 1; load(reset = true) }
                    }
                }
                failed?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.url }) { anime ->
                        AnimeCard(anime.title, anime.thumbnail_url) {
                            navigator.push(AnimeDetailsScreen(sourceId, anime.url, anime.title, anime.thumbnail_url))
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator()
                                hasNext -> ManhwaKit.GhostButton(stringResource(ATMR.strings.anime_more)) { load(reset = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- One anime

/** An anime: its details, the library button, and its episodes (each with where it was left). */
class AnimeDetailsScreen(
    private val sourceId: Long,
    private val url: String,
    private val title: String,
    private val thumbnail: String?,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val source = remember { graph.animeExtensionManager.source(sourceId) }
        val store = graph.animeStore
        val library by store.library.collectAsState()
        val progress by store.progress.collectAsState()
        var anime by remember { mutableStateOf<SAnime?>(null) }
        val episodes = remember { mutableStateListOf<SEpisode>() }
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            val s = source ?: run { loading = false; failed = context.ctxString(ATMR.strings.anime_no_sources); return@LaunchedEffect }
            try {
                val base = SAnime.create().also { it.url = url; it.title = title; it.thumbnail_url = thumbnail }
                val details = withContext(Dispatchers.IO) { runCatching { s.getAnimeDetails(base) }.getOrNull() }
                val full = base.also { b ->
                    details?.let { d ->
                        if (d.title.isNotBlank()) b.title = d.title
                        b.thumbnail_url = d.thumbnail_url ?: b.thumbnail_url
                        b.description = d.description
                        b.genre = d.genre
                        b.status = d.status
                    }
                }
                anime = full
                store.remember(sourceId, full)
                // Newest first from the sources; the player wants them in watching order
                val list = withContext(Dispatchers.IO) { s.getEpisodeList(full) }
                episodes.clear()
                episodes.addAll(if (list.any { it.episode_number > 0f }) list.sortedBy { it.episode_number } else list.reversed())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }

        val key = AnimeStore.key(sourceId, url)
        val entry = library[key]
        fun entryNow(): AnimeEntry? = entry ?: anime?.let { store.remember(sourceId, it) }
        val cover = anime?.thumbnail_url ?: thumbnail
        val fav = entry?.favorite == true
        val next = episodes.indexOfFirst { progress["$key#${it.url}"]?.seen != true }.takeIf { it >= 0 }
        val dateFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
        GlassBackdrop {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                // As the manga page: the blurred cover behind, the cover, the title, the author, status and source
                item(key = "header") {
                    Box {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .drawWithContent {
                                    drawContent()
                                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                                }
                                .blur(4.dp)
                                .alpha(0.25f),
                        )
                        Column {
                            ManhwaKit.TopBar(title = "", onBack = navigator::pop)
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(anime?.title ?: title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ManhwaInk.text)
                                    anime?.author?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.titleSmall, color = ManhwaInk.text.copy(alpha = 0.8f))
                                    }
                                    val status = when (anime?.status) {
                                        SAnime.ONGOING -> stringResource(MR.strings.ongoing)
                                        SAnime.COMPLETED -> stringResource(MR.strings.completed)
                                        else -> stringResource(MR.strings.unknown)
                                    }
                                    Text(listOfNotNull(status, source?.name).joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text.copy(alpha = 0.8f))
                                }
                            }
                        }
                    }
                }
                // The same action buttons as the manga page
                item(key = "actions") {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
                        MangaActionButton(
                            title = stringResource(if (fav) MR.strings.in_library else MR.strings.add_to_library),
                            icon = MaterialSymbols.Rounded.Favorite,
                            color = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            onClick = { entryNow()?.let { store.setFavorite(it, !fav) } },
                        )
                        if (next != null && source != null) {
                            MangaActionButton(
                                title = stringResource(ATMR.strings.anime_continue),
                                icon = MaterialSymbols.RoundedFilled.PlayArrow,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                onClick = { entryNow()?.let { play(context, source, it, episodes, next) } },
                            )
                        }
                        val http = source as? AnimeHttpSource
                        if (http != null && anime != null) {
                            MangaActionButton(
                                title = stringResource(MR.strings.action_web_view),
                                icon = MaterialSymbols.Rounded.Public,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                onClick = {
                                    runCatching { http.getAnimeUrl(anime!!) }.getOrNull()?.let { webUrl ->
                                        context.startActivity(eu.kanade.tachiyomi.ui.webview.WebViewActivity.newIntent(context, webUrl, null, anime?.title))
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "description") {
                    ExpandableMangaDescription(
                        defaultExpandState = false,
                        description = anime?.description,
                        tagsProvider = { anime?.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } },
                        notes = "",
                        onTagSearch = {},
                        onCopyTagToClipboard = {},
                        onEditNotes = {},
                    )
                }
                if (loading) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                failed?.let { item(key = "failed") { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                item(key = "count") {
                    Text(
                        stringResource(ATMR.strings.anime_episodes, episodes.size),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ManhwaInk.text,
                    )
                }
                // Newest first, as the chapters; each one as a chapter row (seen dimmed, where it was left)
                items(episodes.size, key = { "${episodes[episodes.size - 1 - it].url}#$it" }) { j ->
                    val i = episodes.size - 1 - j
                    val ep = episodes[i]
                    val p = progress["$key#${ep.url}"]
                    MangaChapterListItem(
                        title = ep.name,
                        date = ep.date_upload.takeIf { it > 0 }?.let { dateFormat.format(java.util.Date(it)) },
                        readProgress = p?.takeIf { !it.seen && it.positionMs > 0 }?.let { "${it.positionMs / 60000}:${"%02d".format((it.positionMs / 1000) % 60)}" },
                        scanlator = ep.scanlator,
                        read = p?.seen == true,
                        bookmark = false,
                        selected = false,
                        downloadIndicatorEnabled = false,
                        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
                        translationStateProvider = { Translation.State.NOT_TRANSLATED },
                        downloadProgressProvider = { 0 },
                        chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                        chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                        onLongClick = { entryNow()?.let { store.setSeen(it.key, ep.url, ep.name, p?.seen != true) } },
                        onClick = { if (source != null) entryNow()?.let { play(context, source, it, episodes, i) } },
                        onDownloadClick = null,
                        onTranslationClick = null,
                        onChapterSwipe = {},
                    )
                }
            }
        }
    }
}

private fun play(context: android.content.Context, source: AnimeCatalogueSource, entry: AnimeEntry, episodes: List<SEpisode>, index: Int) {
    AnimePlayback.current = AnimePlayback(source, entry, episodes.toList(), index)
    context.startActivity(AnimePlayerActivity.newIntent(context, index))
}

// ---------------------------------------------------------------- Library

/** The anime library: what is being watched, then the saved anime. */
@Composable
fun AnimeLibraryContent(contentPadding: PaddingValues, navigator: Navigator) {
    val context = LocalContext.current
    val store = remember { context.appGraph.animeStore }
    val library by store.library.collectAsState()
    val progress by store.progress.collectAsState()
    val favorites = remember(library) { store.favorites() }
    // The library holds only the anime with a heart; what was watched lives in the history
    val history = emptyList<Pair<AnimeEntry, EpisodeProgress>>()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.anime_watching)) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history, key = { it.first.key }) { (entry, p) ->
                        Box(Modifier.width(112.dp)) {
                            AnimeCard(entry.title, entry.thumbnailUrl, badge = p.episodeName.take(18)) {
                                navigator.push(AnimeDetailsScreen(entry.sourceId, entry.url, entry.title, entry.thumbnailUrl))
                            }
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(ATMR.strings.anime_library)) }
        if (favorites.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Hint(stringResource(ATMR.strings.anime_library_empty)) }
        }
        items(favorites, key = { it.key }) { entry ->
            AnimeCard(entry.title, entry.thumbnailUrl) {
                navigator.push(AnimeDetailsScreen(entry.sourceId, entry.url, entry.title, entry.thumbnailUrl))
            }
        }
    }
}

/** The anime watched, newest first, with the episode each was left at (the history's anime side). */
@Composable
fun AnimeHistoryContent(contentPadding: PaddingValues, navigator: Navigator) {
    val context = LocalContext.current
    val store = remember { context.appGraph.animeStore }
    val library by store.library.collectAsState()
    val progress by store.progress.collectAsState()
    val history = remember(library, progress) { store.history() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (history.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Hint(stringResource(ATMR.strings.anime_history_empty)) }
        }
        items(history, key = { it.first.key }) { (entry, p) ->
            AnimeCard(entry.title, entry.thumbnailUrl, badge = p.episodeName.take(18)) {
                navigator.push(AnimeDetailsScreen(entry.sourceId, entry.url, entry.title, entry.thumbnailUrl))
            }
        }
    }
}

/** The anime extension repositories: add one by its address, or take one away. */
class AnimeReposScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val graph = remember { context.appGraph }
        val manager = graph.animeExtensionManager
        val loading by manager.loading.collectAsState()
        val repos by graph.sourcePreferences.animeRepos.collectPref()
        var repoText by remember { mutableStateOf("") }
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.anime_repos), onBack = navigator::pop)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.5f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (repos.isEmpty()) Text(stringResource(ATMR.strings.anime_repos_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
                    repos.forEach { repo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(repo, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { manager.removeRepo(repo) }) { Icon(MaterialSymbols.Rounded.Delete, null, tint = ManhwaInk.muted) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ManhwaKit.SearchField(repoText, stringResource(ATMR.strings.anime_repo_hint), onValueChange = { repoText = it }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Add, active = true) {
                            if (manager.addRepo(repoText)) repoText = "" else context.toast(ATMR.strings.anime_repo_bad)
                        }
                    }
                    if (loading) Text(stringResource(ATMR.strings.anime_loading), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                }
            }
        }
    }
}
