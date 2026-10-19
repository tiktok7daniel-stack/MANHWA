package eu.kanade.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import eu.kanade.presentation.components.LocalManhwaLook
import eu.kanade.presentation.components.LocalManhwaWantsLight
import eu.kanade.presentation.components.ManhwaLook
import eu.kanade.presentation.components.ManhwaStyle
import eu.kanade.presentation.components.ManhwaStyleState
import eu.kanade.presentation.components.styleDarkScheme
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.DanghwaColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState

@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val uiPreferences = remember { context.appGraph.uiPreferences }
    val accent by uiPreferences.manhwaAccent.collectAsState()
    val styleName by uiPreferences.manhwaStyle.collectAsState()
    val light by uiPreferences.manhwaStyleLight.collectAsState()
    BaseTachiyomiTheme(
        appTheme = appTheme ?: uiPreferences.appTheme.get(),
        isAmoled = amoled ?: uiPreferences.themeDarkAmoled.get(),
        accent = accent,
        style = ManhwaStyle.of(styleName),
        light = light,
        content = content,
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.DANGHWA,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(appTheme, isAmoled, content = content)

@Composable
private fun BaseTachiyomiTheme(
    @Suppress("UNUSED_PARAMETER") appTheme: AppTheme,
    @Suppress("UNUSED_PARAMETER") isAmoled: Boolean,
    accent: Int? = null,
    style: ManhwaStyle = ManhwaStyle.GLASS,
    light: Boolean = false,
    content: @Composable () -> Unit,
) {
    // MANHWA: the dark scheme tinted live with the chosen accent; Obsidian Royal lays its obsidian surfaces on it.
    // Its light version is given by the screens that follow the style ([ManhwaStyledScreen]).
    val scheme = remember(accent, style) {
        DanghwaColorScheme.getColorScheme(isDark = true, isAmoled = true, overrideDarkSurfaceContainers = true)
            .let { if (accent != null) it.withAccent(Color(accent), true) else it }
            .let { styleDarkScheme(it, style) }
    }
    val look = remember(scheme.primary, style) {
        ManhwaLook.of(style, scheme.primary, light = false)
    }
    // Read while drawing (the glow), outside of composition
    SideEffect { ManhwaStyleState.style = style }
    CompositionLocalProvider(LocalManhwaLook provides look, LocalManhwaWantsLight provides light) {
        // One clock for the looks that move (Aurora Veil, Monarch Holo), still for the others
        eu.kanade.presentation.components.ProvideLookClock(moving = style == ManhwaStyle.AURORA || style == ManhwaStyle.HOLO) {
            MaterialExpressiveTheme(colorScheme = scheme, content = content)
        }
    }
}

/** Re-tints the MANHWA Glass scheme with the accent the reader picked in the side menu. */
private fun ColorScheme.withAccent(accent: Color, isDark: Boolean): ColorScheme {
    val deep = lerp(accent, Color.Black, 0.55f)
    val deeper = lerp(accent, Color.Black, 0.72f)
    return if (isDark) {
        val onAccent = if (accent.luminance() > 0.55f) Color(0xFF14090B) else Color.White
        copy(
            primary = accent,
            onPrimary = onAccent,
            onPrimaryContainer = lerp(accent, Color.White, 0.8f),
            onSecondaryContainer = lerp(accent, Color.White, 0.85f),
            primaryContainer = deep,
            secondary = lerp(accent, Color.White, 0.15f),
            secondaryContainer = deeper,
            inversePrimary = deep,
            surfaceTint = accent,
        )
    } else {
        copy(primary = deep, secondary = deep, inversePrimary = accent, surfaceTint = deep)
    }
}
