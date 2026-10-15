package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.components.ManhwaGlassPalette
import mihon.app.di.appGraph
import tachiyomi.presentation.core.util.collectAsState

/** First run: pick the MANHWA Glass variant (the only look of the app). */
internal class ThemeStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val uiPreferences = remember { context.appGraph.uiPreferences }
        val accent by uiPreferences.manhwaAccent.collectAsState()

        Column {
            ManhwaGlassPalette(selected = accent, onPick = { uiPreferences.manhwaAccent.set(it) })
        }
    }
}
