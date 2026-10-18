package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Search

/** Shared building blocks of the MANHWA neon glass look. */
object ManhwaKit {

    val Ink = Color(0xFF050507)

    @Composable
    fun accentBrush(): Brush {
        val primary = MaterialTheme.colorScheme.primary
        return Brush.horizontalGradient(listOf(primary, lerp(primary, Color.Black, 0.45f)))
    }

    /** Transparent top bar with a glass back button, a bold title and optional actions. */
    @Composable
    fun TopBar(
        title: String,
        subtitle: String? = null,
        onBack: () -> Unit,
        actions: @Composable () -> Unit = {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconOrb(MaterialSymbols.AutoMirroredRounded.ArrowBack, onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.let { base ->
                        // The neon halo behind the title belongs to MANHWA Glass
                        if (LocalManhwaLook.current.glow) base.copy(shadow = Shadow(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), blurRadius = 22f)) else base
                    },
                    fontWeight = FontWeight.Black,
                    color = ManhwaInk.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            actions()
        }
    }

    @Composable
    fun IconOrb(icon: ImageVector, active: Boolean = false, size: Int = 42, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .size(size.dp)
                .then(
                    if (active) {
                        Modifier
                            .neonGlow(CircleShape, colors.primary, 0.35f, 10.dp)
                            .clip(CircleShape)
                            .background(accentBrush())
                    } else {
                        Modifier.glass(RoundedCornerShape((size / 3).dp), alpha = 0.45f)
                    },
                )
                .pressClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    active -> Color.White
                    // On paper the pale tint would fade: the accent itself
                    LocalManhwaLook.current.light -> colors.primary
                    else -> colors.primary.let { lerp(it, Color.White, 0.55f) }
                },
                modifier = Modifier.size((size * 0.5f).dp),
            )
        }
    }

    @Composable
    fun NeonButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = modifier
                .neonGlow(RoundedCornerShape(16.dp), colors.primary, 0.25f, 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(accentBrush())
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .pressClickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
        }
    }

    @Composable
    fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
        Row(
            modifier = modifier
                .glass(RoundedCornerShape(16.dp), alpha = 0.35f)
                .pressClickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ManhwaInk.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ManhwaInk.text, maxLines = 1)
        }
    }

    @Composable
    fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(50)
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else ManhwaInk.text.copy(alpha = 0.75f),
            maxLines = 1,
            modifier = Modifier
                .then(
                    if (selected) {
                        Modifier
                            .neonGlow(shape, MaterialTheme.colorScheme.primary, 0.2f, 8.dp)
                            .clip(shape)
                            .background(accentBrush())
                    } else {
                        Modifier.glass(shape, alpha = 0.35f)
                    },
                )
                .pressClickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }

    @Composable
    fun SearchField(value: String, hint: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(50.dp)
                .glass(RoundedCornerShape(18.dp), accent = true, alpha = 0.45f)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MaterialSymbols.Rounded.Search,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(22.dp).neonGlow(CircleShape, colors.primary, 0.25f, 8.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = ManhwaInk.muted, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = ManhwaInk.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    /** Section title with a glowing bar and a small accent line above it. */
    @Composable
    fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
        val colors = MaterialTheme.colorScheme
        Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(if (subtitle != null) 38.dp else 24.dp)
                    .neonGlow(RoundedCornerShape(2.dp), colors.primary, 0.3f, 6.dp)
                    .background(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.2f))), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = ManhwaInk.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }

    /** Thin glowing progress bar. */
    @Composable
    fun GlowBar(fraction: Float, modifier: Modifier = Modifier) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(ManhwaInk.text.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(listOf(lerp(colors.primary, Color.Black, 0.3f), colors.primary, Color.White)),
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}
