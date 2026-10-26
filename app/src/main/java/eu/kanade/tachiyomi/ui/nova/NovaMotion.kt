package eu.kanade.tachiyomi.ui.nova

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * MANHWA: Nova's motion. A tap sinks what it touches a little and lets it spring back; a card arrives once, rising
 * and fading in, a beat after the one before it. Both are quick and follow the phone's animation scale.
 */
fun Modifier.novaClick(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "novaPress",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
        .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
}

/** Rises in the first time it is shown; one shown already (scrolled back to, or after a turn) stays put. */
fun Modifier.novaAppear(index: Int = 0): Modifier = composed {
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown) return@LaunchedEffect
        delay(index.coerceIn(0, 9) * 40L)
        progress.animateTo(1f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
        shown = true
    }
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 22.dp.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}
