package eu.kanade.presentation.browse.components

import mihon.icons.materialsymbols.rounded.AutoAwesome
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.AiSearchState
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Glass strip under the search bar: the AI search action and the titles it found. */
@Composable
fun AiSearchBar(
    state: AiSearchState,
    onAiSearch: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.7f), colors.outlineVariant)),
                shape = shape,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = true,
                onClick = onAiSearch,
                enabled = state !is AiSearchState.Loading,
                label = { Text(stringResource(ATMR.strings.ai_search)) },
                leadingIcon = if (state is AiSearchState.Loading) {
                    { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }
                } else {
                    {
                        androidx.compose.material3.Icon(
                            mihon.icons.materialsymbols.MaterialSymbols.Rounded.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.primary,
                    selectedLabelColor = colors.onPrimary,
                ),
                shape = RoundedCornerShape(50),
            )
            if (state is AiSearchState.Suggestions) {
                state.queries.forEach { query ->
                    FilterChip(
                        selected = query == state.selected,
                        onClick = { onSuggestion(query) },
                        label = { Text(query, maxLines = 1) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = colors.surfaceContainerHigh.copy(alpha = 0.6f),
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
        val message = when (state) {
            AiSearchState.Idle -> stringResource(ATMR.strings.ai_search_hint)
            AiSearchState.NotConfigured -> stringResource(ATMR.strings.ai_search_not_configured)
            is AiSearchState.Error -> stringResource(ATMR.strings.ai_search_failed, state.message.orEmpty())
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is AiSearchState.Error || state is AiSearchState.NotConfigured) {
                    colors.error
                } else {
                    colors.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }
}
