package eu.kanade.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * MANHWA: the one place the app's look comes from. Each style is a set of tokens (backgrounds, surfaces, text,
 * light rims, glow, card shape, touch feedback); the building blocks ([glass], [neonGlow], [GlassBackdrop],
 * [ManhwaKit]) and the screens read them, so choosing a style in the settings changes the whole app at once.
 *
 * - [ManhwaStyle.GLASS]: MANHWA Glass, the original neon glass look (unchanged, the default).
 * - [ManhwaStyle.OBSIDIAN]: Obsidian Royal: deep obsidian (or warm silk paper in its light version), solid
 *   surfaces lit by thin specular rims instead of blur and glow, editorial cards and a soft press. Light on the
 *   phone's GPU and battery. Both follow the accent the reader picked.
 * - [ManhwaStyle.AURORA], [ManhwaStyle.INK], [ManhwaStyle.HOLO]: Aurora Veil, Manhwa Ink and Monarch Holo (see
 *   ManhwaLooks.kt), each with its own surfaces, backdrop and touch.
 */
enum class ManhwaStyle {
    GLASS,
    OBSIDIAN,
    AURORA,
    INK,
    HOLO,
    ;

    /** Whether the look has a light version the reader can choose. */
    val hasLight: Boolean get() = this == OBSIDIAN || this == INK

    companion object {
        /** The saved style, MANHWA Glass when unknown. */
        fun of(name: String?): ManhwaStyle = entries.firstOrNull { it.name == name } ?: GLASS
    }
}

@Immutable
data class ManhwaLook(
    val style: ManhwaStyle,
    val light: Boolean,
    /** The screen behind everything. */
    val background: Color,
    /** Cards, lists and sheets. */
    val surface: Color,
    /** A raised surface (a card on a card, the dock). */
    val surfaceRaised: Color,
    /** Titles and main text. */
    val text: Color,
    /** Secondary text, chapter numbers, hints. */
    val muted: Color,
    /** Top of a card's light rim, fading to [rimBottom] at its bottom. */
    val rimTop: Color,
    val rimBottom: Color,
    /** The rim of a highlighted (accent) card. */
    val rimAccentTop: Color,
    /** Neon glow around cards, buttons and icons. */
    val glow: Boolean,
    /** How small a card or button gets while pressed (1 = no change). */
    val pressScale: Float,
    /** Corners of the library's cover cards (dp). */
    val cardRadius: Int = 18,
    /** The press springs back (a little bounce) instead of easing back. */
    val pressSpring: Boolean = false,
    /** Degrees a pressed card tips back, for depth (0 = flat). */
    val pressTilt: Float = 0f,
) {
    val isObsidian: Boolean get() = style == ManhwaStyle.OBSIDIAN

    /** Any look but MANHWA Glass: solid surfaces, editorial cards, the pill dock. */
    val isSolid: Boolean get() = style != ManhwaStyle.GLASS

    /** A still look: no breathing halo on the AI orb. */
    val isCalm: Boolean get() = style == ManhwaStyle.OBSIDIAN || style == ManhwaStyle.INK

    companion object {
        /** MANHWA Glass: the values its building blocks always used. */
        fun glass(accent: Color) = ManhwaLook(
            style = ManhwaStyle.GLASS,
            light = false,
            background = Color(0xFF030304),
            surface = Color(0xFF0D0D12),
            surfaceRaised = Color(0xFF15151C),
            text = Color.White,
            muted = Color.White.copy(alpha = 0.6f),
            rimTop = Color.White.copy(alpha = 0.22f),
            rimBottom = Color.White.copy(alpha = 0.06f),
            rimAccentTop = accent,
            glow = true,
            pressScale = 1f,
        )

        /** Obsidian Royal, dark or light, with surfaces tinted a little by the [accent]. */
        fun obsidian(accent: Color, light: Boolean) = if (light) {
            ManhwaLook(
                style = ManhwaStyle.OBSIDIAN,
                light = true,
                background = ObsidianTokens.PaperBackground,
                surface = ObsidianTokens.PaperSurface,
                surfaceRaised = lerp(ObsidianTokens.PaperSurface, accent, 0.03f),
                text = ObsidianTokens.PaperInk,
                muted = ObsidianTokens.PaperMuted,
                rimTop = accent.copy(alpha = 0.18f),
                rimBottom = accent.copy(alpha = 0.03f),
                rimAccentTop = accent.copy(alpha = 0.45f),
                glow = false,
                pressScale = 0.97f,
                cardRadius = ObsidianTokens.CARD_RADIUS,
            )
        } else {
            ManhwaLook(
                style = ManhwaStyle.OBSIDIAN,
                light = false,
                background = ObsidianTokens.Background,
                surface = lerp(ObsidianTokens.Surface, accent, 0.04f),
                surfaceRaised = lerp(ObsidianTokens.SurfaceRaised, accent, 0.05f),
                text = ObsidianTokens.Ink,
                muted = ObsidianTokens.Muted,
                rimTop = Color.White.copy(alpha = 0.18f),
                rimBottom = Color.White.copy(alpha = 0.03f),
                rimAccentTop = accent.copy(alpha = 0.55f),
                glow = false,
                pressScale = 0.97f,
                cardRadius = ObsidianTokens.CARD_RADIUS,
            )
        }

        /** Aurora Veil: midnight panels tinted by the accent, round corners, a soft spring. */
        fun aurora(accent: Color) = ManhwaLook(
            style = ManhwaStyle.AURORA,
            light = false,
            background = LookTokens.AuroraBackground,
            surface = lerp(LookTokens.AuroraSurface, accent, 0.05f),
            surfaceRaised = lerp(LookTokens.AuroraSurface, accent, 0.12f),
            text = LookTokens.AuroraInk,
            muted = LookTokens.AuroraMuted,
            rimTop = accent.copy(alpha = 0.45f),
            rimBottom = accent.hueShift(50f).copy(alpha = 0.2f),
            rimAccentTop = accent,
            glow = false,
            pressScale = 0.96f,
            cardRadius = 22,
            pressSpring = true,
        )

        /** Manhwa Ink on manga paper or on night ink: square-ish panels, a crisp press. */
        fun ink(accent: Color, light: Boolean) = ManhwaLook(
            style = ManhwaStyle.INK,
            light = light,
            background = if (light) LookTokens.InkPaper else LookTokens.InkNight,
            surface = if (light) LookTokens.InkPaperSurface else LookTokens.InkNightSurface,
            surfaceRaised = if (light) lerp(LookTokens.InkPaperSurface, accent, 0.06f) else lerp(LookTokens.InkNightSurface, accent, 0.1f),
            text = if (light) LookTokens.InkBlack else LookTokens.InkNightLine,
            muted = if (light) LookTokens.InkPaperMuted else LookTokens.InkNightMuted,
            rimTop = if (light) LookTokens.InkBlack else LookTokens.InkNightLine,
            rimBottom = if (light) LookTokens.InkBlack else LookTokens.InkNightLine,
            rimAccentTop = accent,
            glow = false,
            pressScale = 0.97f,
            cardRadius = 6,
        )

        /** Monarch Holo: deep panels with a holographic rim, a spring with a slight tilt. */
        fun holo(accent: Color) = ManhwaLook(
            style = ManhwaStyle.HOLO,
            light = false,
            background = LookTokens.HoloBackground,
            surface = lerp(LookTokens.HoloSurface, accent, 0.05f),
            surfaceRaised = lerp(LookTokens.HoloSurface, accent, 0.12f),
            text = LookTokens.HoloInk,
            muted = LookTokens.HoloMuted,
            rimTop = accent.copy(alpha = 0.6f),
            rimBottom = accent.hueShift(-60f).copy(alpha = 0.3f),
            rimAccentTop = accent,
            glow = true,
            pressScale = 0.95f,
            cardRadius = 16,
            pressSpring = true,
            pressTilt = 5f,
        )

        /** The look of [style] for [accent], in its light version when it has one and [light] is set. */
        fun of(style: ManhwaStyle, accent: Color, light: Boolean = false): ManhwaLook = when (style) {
            ManhwaStyle.GLASS -> glass(accent)
            ManhwaStyle.OBSIDIAN -> obsidian(accent, light)
            ManhwaStyle.AURORA -> aurora(accent)
            ManhwaStyle.INK -> ink(accent, light)
            ManhwaStyle.HOLO -> holo(accent)
        }
    }
}

/** The dark colour scheme of [style] on the MANHWA [base] scheme. */
fun styleDarkScheme(base: ColorScheme, style: ManhwaStyle): ColorScheme = when (style) {
    ManhwaStyle.GLASS -> base
    ManhwaStyle.OBSIDIAN -> obsidianDarkScheme(base)
    ManhwaStyle.AURORA -> lookDarkScheme(base, LookTokens.AuroraBackground, LookTokens.AuroraSurface, LookTokens.AuroraSurface, LookTokens.AuroraInk, LookTokens.AuroraMuted)
    ManhwaStyle.INK -> lookDarkScheme(base, LookTokens.InkNight, LookTokens.InkNightSurface, LookTokens.InkNightSurface, LookTokens.InkNightLine, LookTokens.InkNightMuted)
    ManhwaStyle.HOLO -> lookDarkScheme(base, LookTokens.HoloBackground, LookTokens.HoloSurface, LookTokens.HoloSurface, LookTokens.HoloInk, LookTokens.HoloMuted)
}

/** The fixed values of Obsidian Royal. */
object ObsidianTokens {
    val Background = Color(0xFF08080C)
    val Surface = Color(0xFF111118)
    val SurfaceRaised = Color(0xFF17171F)
    val Ink = Color(0xFFF8F9FA)
    val Muted = Color(0xFF8E929F)
    val PaperBackground = Color(0xFFF7F6F2)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperInk = Color(0xFF121316)

    /** A touch darker than [Muted] on paper, so secondary text stays readable on a light page. */
    val PaperMuted = Color(0xFF737785)

    /** The fill of the small pills on the cards. */
    val PillFill = Color(0xE60E0E14)

    /** Card corners of the editorial cards. */
    const val CARD_RADIUS = 14
}

/** The look of the screen being drawn (each screen may be in the light or the dark version). */
val LocalManhwaLook = staticCompositionLocalOf { ManhwaLook.glass(Color(0xFFFF3B4E)) }

/** The reader chose the light version (it applies to the screens that already follow the style). */
val LocalManhwaWantsLight = staticCompositionLocalOf { false }

/**
 * The style chosen in the settings, for what is drawn outside of composition (the glow is drawn by [neonGlow]
 * while drawing). Set by the theme from the reader's settings.
 */
object ManhwaStyleState {
    var style by mutableStateOf(ManhwaStyle.GLASS)

    val glow: Boolean get() = style == ManhwaStyle.GLASS || style == ManhwaStyle.HOLO
}

/** The colours of the current look, read by the screens instead of fixed colours. */
object ManhwaInk {
    /** Titles and main text (white on the dark looks, ink on paper). */
    val text: Color
        @Composable @ReadOnlyComposable
        get() = LocalManhwaLook.current.text

    val muted: Color
        @Composable @ReadOnlyComposable
        get() = LocalManhwaLook.current.muted

    val background: Color
        @Composable @ReadOnlyComposable
        get() = LocalManhwaLook.current.background

    val surface: Color
        @Composable @ReadOnlyComposable
        get() = LocalManhwaLook.current.surface

    val surfaceRaised: Color
        @Composable @ReadOnlyComposable
        get() = LocalManhwaLook.current.surfaceRaised

    /** Text and icons on the accent colour (always white). */
    val onAccent: Color get() = Color.White
}

/**
 * The screens that follow the style already get its light version when the reader chose it; the others keep the
 * dark version of the same style until they follow it too, so no screen ever shows white text on paper.
 */
@Composable
fun ManhwaStyledScreen(content: @Composable () -> Unit) {
    val look = LocalManhwaLook.current
    if (!look.style.hasLight || look.light || !LocalManhwaWantsLight.current) {
        content()
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val dark = MaterialTheme.colorScheme
    val scheme = remember(accent, dark, look.style) {
        if (look.style == ManhwaStyle.INK) inkLightScheme(accent, dark) else obsidianLightScheme(accent, dark)
    }
    // Manhwa Ink on paper uses the accent made readable, as its scheme does
    val lightLook = remember(accent, look.style, scheme) {
        ManhwaLook.of(look.style, if (look.style == ManhwaStyle.INK) scheme.primary else accent, light = true)
    }
    // Dark clock and icons over the paper while this screen shows; given back when it goes
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        val bars = activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val status = bars?.isAppearanceLightStatusBars
        val navigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = true
        bars?.isAppearanceLightNavigationBars = true
        onDispose {
            status?.let { bars?.isAppearanceLightStatusBars = it }
            navigation?.let { bars?.isAppearanceLightNavigationBars = it }
        }
    }
    CompositionLocalProvider(LocalManhwaLook provides lightLook) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The dark Obsidian Royal scheme: the MANHWA scheme on deep obsidian surfaces. */
fun obsidianDarkScheme(base: ColorScheme): ColorScheme {
    val accent = base.primary
    val surface = lerp(ObsidianTokens.Surface, accent, 0.04f)
    return base.copy(
        background = ObsidianTokens.Background,
        onBackground = ObsidianTokens.Ink,
        surface = ObsidianTokens.Background,
        onSurface = ObsidianTokens.Ink,
        surfaceVariant = surface,
        onSurfaceVariant = ObsidianTokens.Muted,
        surfaceContainerLowest = ObsidianTokens.Background,
        surfaceContainerLow = lerp(ObsidianTokens.Background, surface, 0.5f),
        surfaceContainer = surface,
        surfaceContainerHigh = lerp(ObsidianTokens.SurfaceRaised, accent, 0.05f),
        surfaceContainerHighest = lerp(ObsidianTokens.SurfaceRaised, accent, 0.08f),
        outline = ObsidianTokens.Muted.copy(alpha = 0.5f),
        outlineVariant = Color.White.copy(alpha = 0.08f),
    )
}

/** The light Obsidian Royal scheme: silk paper, white cards, dark ink, the accent made deep enough to read. */
fun obsidianLightScheme(accent: Color, dark: ColorScheme): ColorScheme {
    val primary = if (accent.luminance() > 0.45f) lerp(accent, Color.Black, 0.35f) else accent
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = lerp(primary, Color.White, 0.82f),
        onPrimaryContainer = lerp(primary, Color.Black, 0.55f),
        secondary = lerp(primary, ObsidianTokens.PaperInk, 0.2f),
        onSecondary = Color.White,
        secondaryContainer = lerp(primary, Color.White, 0.88f),
        onSecondaryContainer = lerp(primary, Color.Black, 0.6f),
        tertiary = dark.tertiary,
        background = ObsidianTokens.PaperBackground,
        onBackground = ObsidianTokens.PaperInk,
        surface = ObsidianTokens.PaperBackground,
        onSurface = ObsidianTokens.PaperInk,
        surfaceVariant = Color(0xFFEFEDE7),
        onSurfaceVariant = ObsidianTokens.PaperMuted,
        surfaceTint = primary,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFBFAF7),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF2F0EB),
        surfaceContainerHighest = Color(0xFFEAE8E2),
        outline = ObsidianTokens.PaperMuted.copy(alpha = 0.6f),
        outlineVariant = ObsidianTokens.PaperInk.copy(alpha = 0.08f),
        error = dark.error,
        inversePrimary = accent,
    )
}

/**
 * A click that shrinks the element a little while it is pressed when the style asks for it (Obsidian Royal), so
 * the screen feels alive under the finger; a plain click otherwise.
 */
fun Modifier.pressClickable(onClick: () -> Unit): Modifier = composed {
    val look = LocalManhwaLook.current
    if (look.pressScale >= 1f) return@composed clickable(onClick = onClick)
    val interaction = remember { MutableInteractionSource() }
    pressFeel(look, interaction).clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick)
}

/**
 * The look's touch: the card gives under the finger (fast, ease-out) and comes back with a soft spring on the
 * looks that spring, tipping back a few degrees on Monarch Holo. Scale and tilt only, drawn in the card's layer.
 */
@Composable
private fun Modifier.pressFeel(look: ManhwaLook, interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val still = LocalReduceMotion.current
    val spec: androidx.compose.animation.core.AnimationSpec<Float> = when {
        pressed -> tween(110, easing = androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f))
        look.pressSpring && !still -> androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 600f)
        else -> tween(160)
    }
    val press by animateFloatAsState(if (pressed) 1f else 0f, spec, label = "press")
    return graphicsLayer {
        // The spring may pass its rest a little; the card never grows past its size or tips forward
        val value = press.coerceAtLeast(0f)
        val scale = 1f - (1f - look.pressScale) * value
        scaleX = scale
        scaleY = scale
        if (look.pressTilt > 0f && !still) {
            rotationX = look.pressTilt * value
            cameraDistance = 12f * density
        }
    }
}

/** [pressClickable] with a long press too (cards of the library). */
fun Modifier.pressCombinedClickable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier = composed {
    val look = LocalManhwaLook.current
    val interaction = remember { MutableInteractionSource() }
    val scaled = if (look.pressScale < 1f) pressFeel(look, interaction) else this
    scaled.combinedClickable(
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

/**
 * The badge on the cards (chapter counts, status, ratings), in the look's own way:
 * - Obsidian Royal: a small dark opaque pill edged with a 0.5 dp line of the accent;
 * - Aurora Veil: a frosted midnight pill with a rim from the accent to its neighbour hue;
 * - Manhwa Ink: a paper sticker with a bold ink border and square-ish corners, black ink on it;
 * - Monarch Holo: a deep pill with a holographic rim.
 * Its text colour is [pillText].
 */
fun Modifier.obsidianPill(): Modifier = composed {
    val accent = MaterialTheme.colorScheme.primary
    val look = LocalManhwaLook.current
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
    when (look.style) {
        ManhwaStyle.AURORA -> this
            .clip(pill)
            .background(LookTokens.AuroraSurface.copy(alpha = 0.9f), pill)
            .border(0.75.dp, androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(accent, accent.hueShift(50f))), pill)
            .padding(horizontal = 5.dp, vertical = 1.dp)
        ManhwaStyle.INK -> {
            val sticker = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            this
                .clip(sticker)
                .background(LookTokens.InkPaperSurface, sticker)
                .border(1.2.dp, LookTokens.InkBlack, sticker)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        }
        ManhwaStyle.HOLO -> this
            .clip(pill)
            .background(LookTokens.HoloSurface.copy(alpha = 0.92f), pill)
            .border(
                0.75.dp,
                androidx.compose.ui.graphics.Brush.sweepGradient(listOf(accent, accent.hueShift(60f), Color.White, accent.hueShift(-60f), accent)),
                pill,
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
        else -> this
            .clip(pill)
            .background(ObsidianTokens.PillFill, pill)
            .border(0.5.dp, accent.copy(alpha = 0.9f), pill)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    }
}

/** The text colour on [obsidianPill]: black ink on Manhwa Ink's paper sticker, light on the dark pills. */
val pillText: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalManhwaLook.current.style == ManhwaStyle.INK) LookTokens.InkBlack else ObsidianTokens.Ink
