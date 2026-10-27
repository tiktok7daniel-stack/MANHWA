package eu.kanade.presentation.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * MANHWA: a cover tapped in the library flies to its place on the manhwa's page (and back), instead of the screen
 * just sliding. The app's navigator shares one transition scope; each screen's own animation scope rides along.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedCoverScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalScreenAnimationScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
private val CoverFlight = BoundsTransform { _: Rect, _: Rect ->
    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = Rect.VisibilityThreshold)
}

/** The cover of manhwa [mangaId], shared between the screens that show it. Unchanged outside the navigator. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCover(mangaId: Long): Modifier {
    val shared = LocalSharedCoverScope.current ?: return this
    val screen = LocalScreenAnimationScope.current ?: return this
    return with(shared) {
        this@sharedCover.sharedElement(
            sharedContentState = rememberSharedContentState(key = "manhwa-cover-$mangaId"),
            animatedVisibilityScope = screen,
            boundsTransform = CoverFlight,
        )
    }
}

