package eu.kanade.presentation.more

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.GlassBackdrop
import eu.kanade.presentation.components.ManhwaKit
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.automirroredrounded.Label
import mihon.icons.materialsymbols.rounded.CloudOff
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Eyeglasses2
import mihon.icons.materialsymbols.rounded.QueryStats
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Storage
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * MANHWA "More" tab: the MANHWA brand on top and only the tools that matter, in neon glass groups.
 * Support, help and about are gone from here (about stays reachable from Settings).
 */
@Composable
fun MoreScreen(
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    incognitoMode: Boolean,
    onIncognitoModeChange: (Boolean) -> Unit,
    onClickDownloadQueue: () -> Unit,
    onClickCategories: () -> Unit,
    onClickStats: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickSettings: () -> Unit,
) {
    GlassBackdrop {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { BrandHeader() }

            item {
                Group(stringResource(ATMR.strings.more_reading)) {
                    ToggleRow(
                        icon = MaterialSymbols.Rounded.CloudOff,
                        title = stringResource(MR.strings.label_downloaded_only),
                        subtitle = stringResource(MR.strings.downloaded_only_summary),
                        checked = downloadedOnly,
                        onCheckedChange = onDownloadedOnlyChange,
                    )
                    Divider()
                    ToggleRow(
                        icon = MaterialSymbols.Rounded.Eyeglasses2,
                        title = stringResource(MR.strings.pref_incognito_mode),
                        subtitle = stringResource(MR.strings.pref_incognito_mode_summary),
                        checked = incognitoMode,
                        onCheckedChange = onIncognitoModeChange,
                    )
                }
            }

            item {
                val queue = downloadQueueStateProvider()
                val queueSubtitle = when (queue) {
                    DownloadQueueState.Stopped -> null
                    is DownloadQueueState.Paused -> {
                        if (queue.pending == 0) {
                            stringResource(MR.strings.paused)
                        } else {
                            "${stringResource(MR.strings.paused)} • ${
                                pluralStringResource(MR.plurals.download_queue_summary, count = queue.pending, queue.pending)
                            }"
                        }
                    }
                    is DownloadQueueState.Downloading ->
                        pluralStringResource(MR.plurals.download_queue_summary, count = queue.pending, queue.pending)
                }
                val pending = when (queue) {
                    is DownloadQueueState.Paused -> queue.pending
                    is DownloadQueueState.Downloading -> queue.pending
                    DownloadQueueState.Stopped -> 0
                }
                Group(stringResource(ATMR.strings.more_library)) {
                    LinkRow(
                        icon = MaterialSymbols.Rounded.Download,
                        title = stringResource(MR.strings.label_download_queue),
                        subtitle = queueSubtitle,
                        badge = pending.takeIf { it > 0 }?.toString(),
                        highlighted = queue is DownloadQueueState.Downloading,
                        onClick = onClickDownloadQueue,
                    )
                    Divider()
                    LinkRow(
                        icon = MaterialSymbols.AutoMirroredRounded.Label,
                        title = stringResource(MR.strings.categories),
                        onClick = onClickCategories,
                    )
                    Divider()
                    LinkRow(
                        icon = MaterialSymbols.Rounded.QueryStats,
                        title = stringResource(MR.strings.label_stats),
                        onClick = onClickStats,
                    )
                }
            }

            item {
                Group(stringResource(ATMR.strings.more_app)) {
                    LinkRow(
                        icon = MaterialSymbols.Rounded.Storage,
                        title = stringResource(MR.strings.label_data_storage),
                        onClick = onClickDataAndStorage,
                    )
                    Divider()
                    LinkRow(
                        icon = MaterialSymbols.Rounded.Settings,
                        title = stringResource(MR.strings.label_settings),
                        onClick = onClickSettings,
                    )
                }
            }
        }
    }
}

/** The MANHWA logo on a glowing crimson tile, the wordmark and a tagline. */
@Composable
private fun BrandHeader() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 28.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .neonGlow(RoundedCornerShape(32.dp), colors.primary, 0.32f, 22.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.linearGradient(
                        listOf(ManhwaKit.Ink, lerp(colors.primary, Color.Black, 0.55f), colors.primary),
                    ),
                )
                .border(
                    1.5.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), colors.primary, Color.Transparent)),
                    RoundedCornerShape(32.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                },
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "MANHWA",
            style = MaterialTheme.typography.headlineLarge.copy(
                shadow = Shadow(colors.primary, blurRadius = 36f),
            ),
            fontWeight = FontWeight.Black,
            letterSpacing = 8.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(64.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color.Transparent, colors.primary, Color.Transparent))),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(ATMR.strings.more_tagline),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.6f),
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp), alpha = 0.45f)
                .padding(vertical = 4.dp),
        ) { content() }
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(start = 72.dp, end = 18.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent))),
    )
}

@Composable
private fun IconTile(icon: ImageVector, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (active) Color.White else colors.primary, label = "tile")
    Box(
        modifier = Modifier
            .size(42.dp)
            .then(
                if (active) {
                    Modifier
                        .neonGlow(RoundedCornerShape(14.dp), colors.primary, 0.35f, 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ManhwaKit.accentBrush())
                } else {
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.primary.copy(alpha = 0.14f))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, active = checked)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (checked) Modifier.neonGlow(RoundedCornerShape(50), colors.primary, 0.25f, 8.dp) else Modifier,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = Color.White,
                checkedBorderColor = colors.primary,
                uncheckedTrackColor = Color.White.copy(alpha = 0.06f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    badge: String? = null,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, active = highlighted)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.primary, maxLines = 1)
            }
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier
                    .neonGlow(CircleShape, colors.primary, 0.3f, 6.dp)
                    .background(colors.primary, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            MaterialSymbols.AutoMirroredRounded.ArrowForward,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
    }
}
