package eu.kanade.translation.speech

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.glass
import eu.kanade.presentation.components.neonGlow
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Headphones
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * MANHWA read along, on the reader's screen: a small floating button that switches it on and off at once. Lit while
 * it is on, with a soft pulse while a page is being said; it can be dragged anywhere it is out of the way.
 */
@Composable
fun ReadAlongButton(on: Boolean, speaking: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    var dx by rememberSaveable { mutableFloatStateOf(0f) }
    var dy by rememberSaveable { mutableFloatStateOf(0f) }
    val pulse = rememberInfiniteTransition(label = "readAlong")
    val beat by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "beat",
    )
    val scale = if (on && speaking) beat else 1f
    val label = stringResource(ATMR.strings.hud_speak_follow)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(48.dp)
            .then(if (on) Modifier.neonGlow(CircleShape, accent, 0.35f, 12.dp) else Modifier)
            .glass(CircleShape, accent = on, alpha = if (on) 0.85f else 0.5f)
            .border(1.dp, if (on) accent else Color.White.copy(alpha = 0.25f), CircleShape)
            .clip(CircleShape)
            .clickable(onClickLabel = label) { onToggle() },
    ) {
        Icon(
            MaterialSymbols.Rounded.Headphones,
            contentDescription = label,
            tint = if (on) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(24.dp),
        )
    }
}
