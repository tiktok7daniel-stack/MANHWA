package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaInk
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.pressClickable
import eu.kanade.tachiyomi.ui.manga.MangaCoverViewModel.CoverOption
import kotlinx.coroutines.delay
import tachiyomi.domain.manga.model.MangaCover as CoverData
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA: the covers other sources have for this series, side by side with their size, so the sharpest one can be
 * put as the series' cover. The search starts with the series' title and follows what is typed (another name).
 */
@Composable
fun CoverPickerDialog(
    title: String,
    options: List<CoverOption>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onPick: (CoverOption) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var query by remember { mutableStateOf(title) }
    var chosen by remember { mutableStateOf<CoverOption?>(null) }
    // Each picture's size once it has loaded (url to "width × height"), to tell the sharp ones
    val sizes = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    LaunchedEffect(query) {
        if (query != title) delay(SEARCH_DELAY_MS)
        onSearch(query)
    }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.cover_pick_title), subtitle = title, onBack = onDismissRequest)
                ManhwaKit.SearchField(
                    value = query,
                    hint = stringResource(ATMR.strings.cover_pick_hint),
                    onValueChange = { query = it },
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                if (searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp))
                }
                if (options.isEmpty() && !searching) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(ATMR.strings.cover_pick_empty),
                            color = ManhwaInk.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(118.dp),
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    ) {
                        items(options, key = { it.sourceId.toString() + it.url }) { option ->
                            CoverCard(option, sizes[option.url], onLoaded = { w, h -> sizes[option.url] = w to h }) {
                                chosen = option
                            }
                        }
                    }
                }
            }
        }
    }
    chosen?.let { option ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(stringResource(ATMR.strings.cover_pick_confirm)) },
            text = { Text(option.sourceName + " · " + option.title) },
            confirmButton = {
                TextButton(onClick = {
                    chosen = null
                    onPick(option)
                }) { Text(stringResource(ATMR.strings.cover_pick_use)) }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(MR.strings.action_cancel)) } },
        )
    }
}

@Composable
private fun CoverCard(option: CoverOption, size: Pair<Int, Int>?, onLoaded: (Int, Int) -> Unit, onClick: () -> Unit) {
    Column(
        Modifier
            .glass(RoundedCornerShape(16.dp), accent = option.match, alpha = 0.5f)
            .pressClickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncImage(
            // Through the app's cover loader, so the source's own headers go with the request
            model = CoverData(mangaId = -1L, sourceId = option.sourceId, isMangaFavorite = false, url = option.url, lastModified = null),
            contentDescription = option.title,
            contentScale = ContentScale.Crop,
            onSuccess = { state -> onLoaded(state.result.image.width, state.result.image.height) },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            option.sourceName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            option.title,
            style = MaterialTheme.typography.labelSmall,
            color = ManhwaInk.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            size?.let { "${it.first} × ${it.second}" } ?: "…",
            style = MaterialTheme.typography.labelSmall,
            color = ManhwaInk.muted,
        )
    }
}


private const val SEARCH_DELAY_MS = 700L
