package eu.kanade.tachiyomi.ui.nova

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import mihon.app.di.appGraph
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import mihon.icons.materialsymbols.MaterialSymbols

/**
 * MANHWA: the app's systems. Each one is a whole app of its own inside the app: its home, its series page, its
 * chapters, its navigation and its look; the app's features stay in all of them. The reader picks one in
 * Settings, Appearance, and the app changes at once. A new system is a new entry here and its own screens.
 */
enum class AppSystem(val title: StringResource, val about: StringResource) {
    CLASSIC(ATMR.strings.system_classic, ATMR.strings.system_classic_sub),
    NOVA(ATMR.strings.system_nova, ATMR.strings.system_nova_sub),
    ;

    companion object {
        fun of(name: String?): AppSystem = entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

/** The system the reader picked, followed live. */
@Composable
fun rememberAppSystem(): AppSystem {
    val context = LocalContext.current
    val pref = remember { context.appGraph.uiPreferences.appSystem }
    val name by pref.collectAsState()
    return AppSystem.of(name)
}

/** The systems side by side, each with a small picture of its home; a tap changes the whole app at once. */
@androidx.compose.runtime.Composable
fun AppSystemPicker() {
    val context = LocalContext.current
    val pref = remember { context.appGraph.uiPreferences.appSystem }
    val current = rememberAppSystem()
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        AppSystem.entries.forEach { system ->
            val on = system == current
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            val edge by androidx.compose.animation.animateColorAsState(
                if (on) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
                label = "systemEdge",
            )
            val width by androidx.compose.animation.core.animateDpAsState(if (on) 2.dp else 1.dp, label = "systemWidth")
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier
                    .novaClick { pref.set(system.name) }
                    .fillMaxWidth()
                    .clip(shape)
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer)
                    .border(width, edge, shape)
                    .padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                SystemPreview(system)
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(14.dp))
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    androidx.compose.material3.Text(
                        tachiyomi.presentation.core.i18n.stringResource(system.title),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    )
                    androidx.compose.material3.Text(
                        tachiyomi.presentation.core.i18n.stringResource(system.about),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = on,
                    enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.45f)) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
                ) {
                    androidx.compose.material3.Icon(
                        mihon.icons.materialsymbols.MaterialSymbols.RoundedFilled.CheckCircle,
                        null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** A small drawing of a system's home: its background, its cards and its bar. */
@androidx.compose.runtime.Composable
private fun SystemPreview(system: AppSystem) {
    val (bg, card, accent) = when (system) {
        AppSystem.CLASSIC -> Triple(androidx.compose.ui.graphics.Color(0xFF050507), androidx.compose.ui.graphics.Color(0xFF17171F), androidx.compose.ui.graphics.Color(0xFFFF3B4E))
        AppSystem.NOVA -> Triple(NovaColors.Background, NovaColors.Surface, NovaColors.Blue)
    }
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier
            .size(width = 64.dp, height = 110.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(bg)
            .padding(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.8f)))
        repeat(2) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                repeat(if (system == AppSystem.NOVA) 3 else 2) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.weight(1f).height(28.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(card),
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().height(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(card))
    }
}
