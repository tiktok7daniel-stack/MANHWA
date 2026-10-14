package eu.kanade.tachiyomi.ui.home.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.Source
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/**
 * "My sources": the installed sources to show on the home screen, and the extensions from the reader's
 * stores that can be added with one tap. Installed extensions go straight onto the home screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSourcesSheet(
    installed: List<Source>,
    selected: Set<String>,
    addable: List<Extension.Available>,
    installing: Map<String, InstallStep>,
    onToggle: (String) -> Unit,
    onAutoPick: () -> Unit,
    onInstall: (Extension.Available) -> Unit,
    onRefresh: () -> Unit,
    onAddStore: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shownInstalled = installed.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val shownAddable = addable.filter { q.isEmpty() || it.name.lowercase().contains(q) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF09080B),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(ATMR.strings.home_my_sources),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                    Text(
                        stringResource(ATMR.strings.home_selected_count, selected.count { id -> installed.any { "${it.id}" == id } }),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
                Pill(stringResource(ATMR.strings.home_done), filled = true, onClick = onDismiss)
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .glass(RoundedCornerShape(16.dp), accent = true, alpha = 0.45f)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MaterialSymbols.Rounded.Search, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(ATMR.strings.src_search),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.5f),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Installed sources
            item(key = "installed-title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title(stringResource(ATMR.strings.src_installed), Modifier.weight(1f))
                    if (installed.isNotEmpty()) Pill(stringResource(ATMR.strings.src_auto_pick), filled = false, onClick = onAutoPick)
                }
            }
            if (installed.isEmpty()) {
                item(key = "installed-empty") { Hint(stringResource(ATMR.strings.src_none_installed)) }
            }
            items(shownInstalled, key = { "s-${it.id}" }) { source ->
                val id = "${source.id}"
                SourceRow(
                    icon = null,
                    name = source.name,
                    detail = languageName(source.lang),
                    action = {
                        AddButton(
                            state = if (id in selected) AddState.Added else AddState.Add,
                            onClick = { onToggle(id) },
                        )
                    },
                    selected = id in selected,
                    onClick = { onToggle(id) },
                )
            }

            // Extensions that can be added
            item(key = "add-title") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Title(stringResource(ATMR.strings.src_add_new), Modifier.weight(1f))
                    Box(
                        modifier = Modifier.size(36.dp).glass(CircleShape, alpha = 0.4f).clickable(onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) { Icon(MaterialSymbols.Rounded.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
            if (addable.isEmpty()) {
                item(key = "no-store") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(22.dp), accent = true, alpha = 0.45f)
                            .padding(18.dp),
                    ) {
                        Text(
                            stringResource(ATMR.strings.src_no_store_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(ATMR.strings.src_no_store_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(14.dp))
                        Pill(stringResource(ATMR.strings.src_add_store), filled = true, onClick = onAddStore)
                    }
                }
            }
            items(shownAddable, key = { "e-${it.pkgName}" }) { extension ->
                val step = installing[extension.pkgName]
                SourceRow(
                    icon = extension.iconUrl,
                    name = extension.name,
                    detail = listOf(
                        languageName(extension.lang),
                        stringResource(ATMR.strings.src_count, extension.sources.size),
                    ).joinToString(" • "),
                    action = {
                        AddButton(
                            state = when (step) {
                                InstallStep.Pending, InstallStep.Downloading, InstallStep.Installing -> AddState.Working
                                InstallStep.Installed -> AddState.Added
                                else -> AddState.Add
                            },
                            onClick = { if (step == null || step == InstallStep.Error || step == InstallStep.Idle) onInstall(extension) },
                        )
                    },
                    selected = false,
                    onClick = { if (step == null || step == InstallStep.Error || step == InstallStep.Idle) onInstall(extension) },
                )
            }
            item(key = "manage") {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp), alpha = 0.4f)
                        .clickable(onClick = onManage)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MaterialSymbols.Rounded.Explore, contentDescription = null, tint = colors.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(ATMR.strings.src_manage),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private enum class AddState { Add, Working, Added }

@Composable
private fun AddButton(state: AddState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .then(
                when (state) {
                    AddState.Added -> Modifier
                        .neonGlow(CircleShape, colors.primary, 0.35f, 10.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.45f))))
                    else -> Modifier
                        .clip(CircleShape)
                        .border(1.5.dp, colors.primary, CircleShape)
                },
            )
            .clickable(enabled = state != AddState.Working, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            AddState.Working -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            AddState.Added -> Icon(MaterialSymbols.Rounded.Check, contentDescription = null, tint = Color.White)
            AddState.Add -> Icon(MaterialSymbols.Rounded.Add, contentDescription = null, tint = colors.primary)
        }
    }
}

@Composable
private fun SourceRow(
    icon: String?,
    name: String,
    detail: String,
    action: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), accent = selected, alpha = 0.4f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.8f), lerp(colors.primary, Color.Black, 0.7f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            if (icon != null) {
                AsyncImage(
                    model = icon,
                    contentDescription = null,
                    placeholder = ColorPainter(Color.Transparent),
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

@Composable
private fun Title(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        color = Color.White,
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Pill(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (filled) Color.White else colors.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (filled) {
                    Modifier.background(Brush.horizontalGradient(listOf(colors.primary, lerp(colors.primary, Color.Black, 0.45f))))
                } else {
                    Modifier.border(1.dp, colors.primary, RoundedCornerShape(50))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

private fun languageName(lang: String): String = when (lang) {
    "all" -> "🌐"
    "" -> ""
    else -> Locale.forLanguageTag(lang).let { it.getDisplayName(it).replaceFirstChar { c -> c.uppercase() } }.ifBlank { lang }
}
