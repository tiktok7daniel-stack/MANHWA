package eu.kanade.translation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/*
 * MANHWA: the rows of the Translation screen. Every row has an icon, a title, its current value, a control and an
 * (i) button that explains the setting.
 */

/** A titled glass card holding a group of rows. */
@Composable
internal fun HubSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = colors.primary,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), alpha = 0.5f)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

/** The thin line between two rows of a section. */
@Composable
internal fun HubDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 66.dp, end = 14.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.07f)),
    )
}

/** The icon of a row, in a small glowing tile of the accent colour. */
@Composable
internal fun HubIconTile(icon: ImageVector, size: Int = 38) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape((size / 3.2f).dp)
    Box(
        modifier = Modifier
            .size(size.dp)
            .neonGlow(shape, primary, 0.12f, 6.dp)
            .clip(shape)
            .background(primary.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** One row: icon, title, current value, the (i) button and [trailing] (a switch or an arrow). */
@Composable
internal fun HubRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    info: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HubIconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (info != null) InfoButton(title, info)
        trailing()
    }
}

/** The (i) next to a setting: a short explanation of what it does, when to use it and what it costs. */
@Composable
internal fun InfoButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MaterialSymbols.Rounded.Info,
            contentDescription = stringResource(ATMR.strings.th_info),
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { HubIconTile(MaterialSymbols.Rounded.Info, size = 44) },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(ATMR.strings.th_info_close)) } },
        )
    }
}

/** The arrow at the end of a row that opens something; it points the reading direction. */
@Composable
internal fun HubChevron() {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        MaterialSymbols.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.size(22.dp).graphicsLayer { if (rtl) scaleX = -1f },
    )
}

@Composable
internal fun HubSwitchRow(
    icon: ImageVector,
    title: String,
    info: String,
    checked: Boolean,
    value: String? = null,
    onChange: (Boolean) -> Unit,
) {
    HubRow(icon = icon, title = title, value = value, info = info, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun HubNavRow(icon: ImageVector, title: String, info: String, value: String? = null, onClick: () -> Unit) {
    HubRow(icon = icon, title = title, value = value, info = info, onClick = onClick) { HubChevron() }
}

/** A row whose value is picked from [options] (value to label) in a dialog. */
@Composable
internal fun <T> HubChoiceRow(
    icon: ImageVector,
    title: String,
    info: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HubRow(
        icon = icon,
        title = title,
        value = options.firstOrNull { it.first == selected }?.second,
        info = info,
        onClick = { open = true },
    ) { HubChevron() }
    if (open) {
        val start = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options) { (option, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null, modifier = Modifier.padding(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(ATMR.strings.th_cancel)) } },
        )
    }
}

/** A row whose value is typed in a dialog; [shown] is what the row shows for the saved value. */
@Composable
internal fun HubTextRow(
    icon: ImageVector,
    title: String,
    info: String,
    value: String,
    shown: String,
    numeric: Boolean = false,
    multiline: Boolean = false,
    onSave: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HubRow(icon = icon, title = title, value = shown, info = info, onClick = { open = true }) { HubChevron() }
    if (open) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 4 else 1,
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(text.trim())
                        open = false
                    },
                ) { Text(stringResource(ATMR.strings.th_save)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(ATMR.strings.th_cancel)) } },
        )
    }
}
