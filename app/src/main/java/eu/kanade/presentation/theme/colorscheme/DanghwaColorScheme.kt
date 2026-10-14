package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Danghwa: near-black glass surfaces with a crimson accent.
 * Surfaces step up in small, slightly red-tinted increments so layered panels read like smoked glass.
 */
internal object DanghwaColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFF3B4E),
        onPrimary = Color(0xFF1A0004),
        primaryContainer = Color(0xFF8E0F1F),
        onPrimaryContainer = Color(0xFFFFDADC),
        secondary = Color(0xFFFF5A6A), // Unread badge
        onSecondary = Color(0xFF1A0004), // Unread badge text
        secondaryContainer = Color(0xFF5C0A15), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFFFFE3E5), // Navigation bar selector icon
        tertiary = Color(0xFFFFB3A1), // Downloaded badge
        onTertiary = Color(0xFF2B0E07), // Downloaded badge text
        tertiaryContainer = Color(0xFF6B2416),
        onTertiaryContainer = Color(0xFFFFDBD2),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF07070A),
        onBackground = Color(0xFFF1E9EA),
        surface = Color(0xFF07070A),
        onSurface = Color(0xFFF1E9EA),
        surfaceVariant = Color(0xFF1C1418), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFFCDB8BB),
        outline = Color(0xFF7A5C61),
        outlineVariant = Color(0xFF3A2A2E),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFF1E9EA),
        inverseOnSurface = Color(0xFF2A1F21),
        inversePrimary = Color(0xFFC0182F),
        surfaceDim = Color(0xFF050507),
        surfaceBright = Color(0xFF2A2024),
        surfaceContainerLowest = Color(0xFF0B0A0D),
        surfaceContainerLow = Color(0xFF110E12),
        surfaceContainer = Color(0xFF161116), // Navigation bar background
        surfaceContainerHigh = Color(0xFF1E161B),
        surfaceContainerHighest = Color(0xFF271C22),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFC0182F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDADC),
        onPrimaryContainer = Color(0xFF40000A),
        secondary = Color(0xFFC0182F), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFDADC), // Navigation bar selector pill & progress indicator (remaining)
        onSecondaryContainer = Color(0xFF40000A), // Navigation bar selector icon
        tertiary = Color(0xFF8C3A26), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFFFFDBD2),
        onTertiaryContainer = Color(0xFF3A0B03),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFBF8F8),
        onBackground = Color(0xFF1F1A1B),
        surface = Color(0xFFFBF8F8),
        onSurface = Color(0xFF1F1A1B),
        surfaceVariant = Color(0xFFF3E6E7), // Navigation bar background (ThemePrefWidget)
        onSurfaceVariant = Color(0xFF524345),
        outline = Color(0xFF857375),
        outlineVariant = Color(0xFFD7C1C3),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF352F30),
        inverseOnSurface = Color(0xFFFAEEEF),
        inversePrimary = Color(0xFFFFB2B6),
        surfaceDim = Color(0xFFE8DADB),
        surfaceBright = Color(0xFFFFF8F8),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFCF1F2),
        surfaceContainer = Color(0xFFF6EBEC), // Navigation bar background
        surfaceContainerHigh = Color(0xFFF0E5E6),
        surfaceContainerHighest = Color(0xFFEADFE0),
    )
}
