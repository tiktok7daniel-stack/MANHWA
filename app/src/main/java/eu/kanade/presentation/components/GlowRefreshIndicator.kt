package eu.kanade.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * MANHWA: the pull-to-refresh light. A ring of the accent colour draws itself as you pull, with a soft halo, and
 * spins while the refresh runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.GlowRefreshIndicator(state: PullToRefreshState, refreshing: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spin",
    )
    Canvas(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .size(46.dp)
            .graphicsLayer {
                val f = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1.5f)
                alpha = f.coerceAtMost(1f)
                translationY = f * 28.dp.toPx()
                val s = 0.6f + 0.4f * f.coerceAtMost(1f)
                scaleX = s
                scaleY = s
            },
    ) {
        val f = if (refreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), radius = r), radius = r)
        drawCircle(Color(0xEE111116), radius = r * 0.72f)
        val stroke = 3.dp.toPx()
        val inset = r * 0.38f
        rotate(if (refreshing) spin else f * 270f) {
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, accent, Color.White)),
                startAngle = 0f,
                sweepAngle = if (refreshing) 280f else 300f * f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        drawCircle(accent, radius = 2.5.dp.toPx() * f)
    }
}
