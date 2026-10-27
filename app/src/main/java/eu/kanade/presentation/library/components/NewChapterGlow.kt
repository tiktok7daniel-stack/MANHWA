package eu.kanade.presentation.library.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tachiyomi.domain.library.model.LibraryManga
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** MANHWA: a work with a chapter out in the last two days that is still unread. */
fun LibraryManga.hasFreshChapter(): Boolean {
    val latest = latestUpload ?: return false
    return unreadCount > 0 && Clock.System.now() - latest < 48.hours
}

/** MANHWA: a dot that breathes light, on the covers of works with a fresh chapter. */
@Composable
fun NewChapterGlow(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "fresh").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "pulse",
    )
    Canvas(modifier.padding(4.dp).size(18.dp)) {
        val r = size.minDimension / 2
        drawCircle(accent.copy(alpha = 0.45f * (1 - pulse)), radius = r * (0.45f + 0.55f * pulse))
        drawCircle(Brush.radialGradient(listOf(Color.White, accent), center = center, radius = r * 0.4f), radius = r * 0.36f)
    }
}
