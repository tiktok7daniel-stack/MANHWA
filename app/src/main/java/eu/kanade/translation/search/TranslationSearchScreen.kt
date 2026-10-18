package eu.kanade.translation.search

import eu.kanade.presentation.components.ManhwaInk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** MANHWA search in translations: a name or a sentence, found in every translated chapter, opened right there. */
class TranslationSearchScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val index = remember { context.appGraph.translationSearchIndex }
        var query by rememberSaveable { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<TranslationSearchIndex.Hit>>(emptyList()) }
        LaunchedEffect(query) {
            delay(250)
            hits = withContext(Dispatchers.IO) { index.search(query) }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues()
        GlassBackdrop {
            Column(Modifier.fillMaxSize()) {
                ManhwaKit.TopBar(title = stringResource(ATMR.strings.tsearch_title), subtitle = stringResource(ATMR.strings.tsearch_subtitle), onBack = navigator::pop)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(ATMR.strings.tsearch_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (query.trim().length >= 2 && hits.isEmpty()) {
                        item { Text(stringResource(ATMR.strings.tsearch_none), color = ManhwaInk.text.copy(alpha = 0.6f)) }
                    }
                    items(hits, key = { "${it.chapterId}:${it.page}:${it.original.hashCode()}" }) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(18.dp), alpha = 0.45f)
                                .clickable { context.startActivity(ReaderActivity.newIntent(context, hit.mangaId, hit.chapterId)) }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("${hit.title} · ${hit.chapterName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(hit.translation, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.text)
                            Text(hit.original, style = MaterialTheme.typography.bodySmall, color = ManhwaInk.text.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }
}
