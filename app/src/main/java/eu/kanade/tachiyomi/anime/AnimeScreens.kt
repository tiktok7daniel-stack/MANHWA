package eu.kanade.tachiyomi.anime

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Visibility
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
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

/** The anime part of Browse: the anime sources, and the anime extensions with their repositories. */
@Composable
fun AnimeSourcesContent(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.appGraph }
    val manager = graph.animeExtensionManager
    val installed by manager.installed.collectAsState()
    val available by manager.available.collectAsState()
    val loading by manager.loading.collectAsState()
    val disabled by graph.sourcePreferences.animeDisabledSources.collectPref()
    val keepsOut = rememberAnimeKeepsOut()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var repoText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val busy = remember { mutableStateListOf<String>() }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManhwaKit.Chip(stringResource(ATMR.strings.anime_sources), selected = tab == 0) { tab = 0 }
                ManhwaKit.Chip(stringResource(ATMR.strings.anime_extensions), selected = tab == 1) { tab = 1 }
            }
        }
        if (tab == 0) {
            val sources = manager.sources().filterNot(keepsOut)
            if (sources.isEmpty()) {
                item { Hint(stringResource(ATMR.strings.anime_no_sources)) }
            }
            items(sources, key = { it.id }) { source ->
                val on = source.id.toString() !in disabled
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.5f)
                        .pressClickable { if (on) navigator.push(AnimeBrowseScreen(source.id)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExtIcon(manager.extensionOf(source.id))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.name, fontWeight = FontWeight.Bold, color = ManhwaInk.text)
                        Text(source.lang.uppercase(), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                    }
                    Switch(checked = on, onCheckedChange = { checked ->
                        val id = source.id.toString()
                        val set = graph.sourcePreferences.animeDisabledSources
                        set.set(if (checked) set.get() - id else set.get() + id)
                    })
                }
            }
        } else {
            item {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.5f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(ATMR.strings.anime_repos), fontWeight = FontWeight.Bold, color = ManhwaInk.text)
                    Text(stringResource(ATMR.strings.anime_repos_info), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.muted)
                    manager.repos().forEach { repo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(repo, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ManhwaKit.IconOrb(MaterialSymbols.Rounded.Delete, size = 34) { manager.removeRepo(repo) }
                        }
                    }
                    ManhwaKit.SearchField(repoText, stringResource(ATMR.strings.anime_repo_hint), onValueChange = { repoText = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ManhwaKit.NeonButton(stringResource(ATMR.strings.anime_repo_add), Modifier.weight(1f), MaterialSymbols.Rounded.Add) {
                            if (manager.addRepo(repoText)) repoText = "" else context.toast(ATMR.strings.anime_repo_bad)
                        }
                        ManhwaKit.GhostButton(stringResource(ATMR.strings.anime_refresh), Modifier.weight(1f), MaterialSymbols.Rounded.Refresh) {
                            scope.launch { manager.refreshAvailable(); manager.reload() }
                        }
                    }
                    if (loading) Text(stringResource(ATMR.strings.anime_loading), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                }
            }
            item { ManhwaKit.SearchField(query, stringResource(ATMR.strings.anime_search_ext), onValueChange = { query = it }) }
            val updates = manager.updates()
            val q = query.trim().lowercase()
            val mine = installed.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            if (mine.isNotEmpty()) item { Section(stringResource(ATMR.strings.anime_installed)) }
            items(mine, key = { "i" + it.pkg }) { ext ->
                ExtRow(
                    icon = { ExtIcon(ext) },
                    name = ext.name,
                    sub = "${ext.lang.uppercase()} · ${ext.versionName}" + if (ext.nsfw) " · 18+" else "",
                    busy = ext.pkg in busy,
                ) {
                    updates[ext.pkg]?.let { up ->
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Download, active = true, size = 34) {
                            busy += ext.pkg
                            scope.launch { if (!manager.install(up)) context.toast(ATMR.strings.anime_install_failed); busy -= ext.pkg }
                        }
                    }
                    if (!ext.shared) {
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Delete, size = 34) { scope.launch { manager.uninstall(ext.pkg) } }
                    }
                }
            }
            val hide = graph.sourcePreferences.hideAdult.get()
            val only = graph.sourcePreferences.onlyAdult.get()
            val offered = available.filter { a ->
                installed.none { it.pkg == a.pkg } &&
                    (q.isEmpty() || a.name.lowercase().contains(q)) &&
                    !AdultFilter.keepsOut(adult = a.nsfw || AdultFilter.isAdultName(a.name), hide = hide, only = only)
            }
            if (offered.isNotEmpty()) item { Section(stringResource(ATMR.strings.anime_available)) }
            items(offered, key = { "a" + it.pkg }) { ext ->
                ExtRow(
                    icon = { AsyncImage(ext.iconUrl, null, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))) },
                    name = ext.name,
                    sub = "${ext.lang.uppercase()} · ${ext.versionName}" + if (ext.nsfw) " · 18+" else "",
                    busy = ext.pkg in busy,
                ) {
                    ManhwaKit.IconOrb(MaterialSymbols.Rounded.Download, size = 34) {
                        busy += ext.pkg
                        scope.launch { if (!manager.install(ext)) context.toast(ATMR.strings.anime_install_failed); busy -= ext.pkg }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtIcon(ext: AnimeExt?) {
    val bitmap = remember(ext?.pkg) { runCatching { ext?.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Box(Modifier.size(42.dp).glass(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(MaterialSymbols.RoundedFilled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ExtRow(icon: @Composable () -> Unit, name: String, sub: String, busy: Boolean, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), alpha = 0.5f).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp)) else actions()
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
class AnimeBrowseScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { context.appGraph.animeExtensionManager.source(sourceId) }
        val scope = rememberCoroutineScope()
        val items = remember { mutableStateListOf<SAnime>() }
        var mode by remember { mutableIntStateOf(0) }
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
            val s = source ?: run { loading = false; failed = context.getString(android.R.string.unknownName); return@LaunchedEffect }
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
        GlassBackdrop {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ManhwaKit.TopBar(title = anime?.title ?: title, subtitle = source?.name, onBack = navigator::pop) }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AsyncImage(
                            model = anime?.thumbnail_url ?: thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            anime?.genre?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted, maxLines = 3) }
                            val fav = entry?.favorite == true
                            ManhwaKit.NeonButton(
                                stringResource(if (fav) ATMR.strings.anime_in_library else ATMR.strings.anime_add_library),
                                Modifier.fillMaxWidth(),
                                MaterialSymbols.Rounded.Favorite,
                            ) {
                                val e = entry ?: anime?.let { store.remember(sourceId, it) } ?: return@NeonButton
                                store.setFavorite(e, !fav)
                            }
                            // Go on with the first episode not seen to the end
                            val next = episodes.indexOfFirst { progress["$key#${it.url}"]?.seen != true }.takeIf { it >= 0 }
                            if (next != null && source != null && entry != null) {
                                ManhwaKit.GhostButton(stringResource(ATMR.strings.anime_continue), Modifier.fillMaxWidth(), MaterialSymbols.RoundedFilled.PlayArrow) {
                                    play(context, source, entry, episodes, next)
                                }
                            }
                        }
                    }
                }
                anime?.description?.takeIf { it.isNotBlank() }?.let { d ->
                    item { Text(d, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text, maxLines = 8, overflow = TextOverflow.Ellipsis) }
                }
                if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                failed?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                item { Text(stringResource(ATMR.strings.anime_episodes, episodes.size), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                items(episodes.size, key = { episodes[it].url }) { i ->
                    val ep = episodes[i]
                    val p = progress["$key#${ep.url}"]
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), accent = p?.seen == false && (p.positionMs > 0), alpha = 0.45f)
                            .pressClickable {
                                val e = entry ?: anime?.let { store.remember(sourceId, it) } ?: return@pressClickable
                                if (source != null) play(context, source, e, episodes, i)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ep.name, color = if (p?.seen == true) ManhwaInk.muted else ManhwaInk.text, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(ep.scanlator, p?.takeIf { !it.seen && it.positionMs > 0 }?.let { "${it.positionMs / 60000}:${"%02d".format((it.positionMs / 1000) % 60)}" })
                            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = ManhwaInk.muted)
                        }
                        ManhwaKit.IconOrb(MaterialSymbols.Rounded.Visibility, active = p?.seen == true, size = 32) {
                            val e = entry ?: anime?.let { store.remember(sourceId, it) } ?: return@IconOrb
                            store.setSeen(e.key, ep.url, ep.name, p?.seen != true)
                        }
                    }
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
    val history = remember(library, progress) { store.history().take(12) }

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
