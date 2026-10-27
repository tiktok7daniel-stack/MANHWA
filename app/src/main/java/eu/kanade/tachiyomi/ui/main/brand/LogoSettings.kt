package eu.kanade.tachiyomi.ui.main.brand

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.ui.nova.novaClick
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/** The app's icon as the home screen shows it: its background, its mark, at [size]. */
@Composable
fun LogoTile(logo: AppLogo, size: Dp, modifier: Modifier = Modifier) {
    // The real launcher icon, drawn in the device's own icon shape, so what is picked here is what the home
    // screen shows; the drawn gradient and mark stay as the fallback when the icon cannot be read.
    val context = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap = remember(logo, px) {
        runCatching {
            androidx.core.content.res.ResourcesCompat.getDrawable(context.resources, logo.icon, context.theme)
                ?.toBitmap(px, px)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
        return
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(logo.top, logo.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(logo.mark),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(0.86f),
        )
    }
}

/**
 * MANHWA: Settings, Appearance: the home-screen icon (five, each with its own opening), which opening plays, how
 * fast, whether it plays at all, and a button that plays it now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogoSettings() {
    val context = LocalContext.current
    val ui = remember { context.appGraph.uiPreferences }
    val logoName by ui.logoStyle.collectAsState()
    val introName by ui.introStyle.collectAsState()
    val enabled by ui.introEnabled.collectAsState()
    val speed by ui.introSpeed.collectAsState()
    val logo = AppLogo.of(logoName)
    val intro = IntroStyle.of(introName)
    var preview by remember { mutableStateOf(false) }
    val changed = stringResource(ATMR.strings.logo_changed)

    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppLogo.entries.forEach { entry ->
                val on = entry == logo
                val ring by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, label = "logoRing")
                val lift by animateFloatAsState(if (on) 1.08f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "logoLift")
                Column(
                    Modifier.novaClick {
                        if (!on) {
                            ui.logoStyle.set(entry.name)
                            AppLogo.applyIcon(context, entry)
                            Toast.makeText(context, changed, Toast.LENGTH_LONG).show()
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LogoTile(
                        entry,
                        64.dp,
                        Modifier
                            .graphicsLayer {
                                scaleX = lift
                                scaleY = lift
                            }
                            .border(2.dp, ring, RoundedCornerShape(20.dp))
                            .padding(3.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(entry.title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Text(
            stringResource(ATMR.strings.intro_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IntroStyle.entries.forEach { entry ->
                val on = entry == intro
                val fill by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "introChip",
                )
                Text(
                    stringResource(entry.title),
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .novaClick { ui.introStyle.set(entry.name) }
                        .clip(RoundedCornerShape(14.dp))
                        .background(fill)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(ATMR.strings.intro_enabled), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { ui.introEnabled.set(it) })
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(ATMR.strings.intro_speed, "%.1f".format(speed)))
            Slider(
                value = speed,
                onValueChange = { ui.introSpeed.set((it * 10f).toInt() / 10f) },
                valueRange = 0.6f..1.6f,
            )
        }
        val corner by animateDpAsState(if (preview) 8.dp else 16.dp, label = "previewCorner")
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .novaClick { preview = true }
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MaterialSymbols.RoundedFilled.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(ATMR.strings.intro_preview), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
    }

    if (preview) {
        Dialog(
            onDismissRequest = { preview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize()) {
                LogoIntro(style = intro, logo = logo, speed = speed, started = true, onFinished = { preview = false })
            }
        }
    }
}
