package eu.kanade.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Danghwa glass: a translucent smoked panel with a light rim on top and a crimson rim at the bottom,
 * the look of the reference designs without needing a live backdrop blur.
 */
fun Modifier.glass(shape: Shape, accent: Boolean = false, alpha: Float = 0.55f): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val fill = Brush.verticalGradient(
        listOf(
            colors.surfaceContainerHighest.copy(alpha = alpha + 0.1f),
            colors.surfaceContainer.copy(alpha = alpha),
        ),
    )
    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (accent) 0.35f else 0.18f),
            colors.primary.copy(alpha = if (accent) 0.85f else 0.35f),
        ),
    )
    this
        .clip(shape)
        .background(fill, shape)
        .border(1.dp, rim, shape)
}

/** Black backdrop with soft crimson glows, used behind glass screens. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050507))
            .background(
                Brush.radialGradient(
                    listOf(primary.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(900f, 150f),
                    radius = 900f,
                ),
            )
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF7A0A2E).copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(0f, 1800f),
                    radius = 1100f,
                ),
            ),
        content = content,
    )
}
