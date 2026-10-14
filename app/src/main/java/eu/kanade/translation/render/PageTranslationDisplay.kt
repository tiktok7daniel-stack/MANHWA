package eu.kanade.translation.render

import android.content.Context
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import mihon.app.di.appGraph

/** How a reader page shows its translation right now. */
data class PageTranslationDisplay(
    val translation: PageTranslation?,
    val show: Boolean,
    val editing: Boolean,
    val revision: Int,
    val style: ScanlationRenderer.Style,
) {
    /** The page is shown with the translation cleaned and drawn into its pixels. */
    val baked: Boolean get() = show && !editing && translation != null

    /** Edit mode: the original page with the movable bubbles on top. */
    val overlay: Boolean get() = show && editing && translation != null

    companion object {
        /** Every change that means the page must be drawn again: translation, switches, edits and style. */
        fun of(context: Context, translation: Flow<PageTranslation?>): Flow<PageTranslationDisplay> {
            val graph = context.appGraph
            val edits = graph.translationEditStore
            return combine(
                translation,
                graph.readerPreferences.showTranslations.changes(),
                edits.editing,
                edits.revision,
                ScanlationRenderer.styleChanges(context),
            ) { t, show, editing, revision, style ->
                // Edits saved while editing are drawn when edit mode ends
                PageTranslationDisplay(t, show, editing, if (editing) -1 else revision, style)
            }.distinctUntilChanged()
        }
    }
}

/** Any change of the translation style settings, as the current style. */
fun ScanlationRenderer.styleChanges(context: Context): Flow<ScanlationRenderer.Style> {
    val prefs = context.appGraph.translationPreferences
    return merge(
        prefs.translationTypeface().changes().map { },
        prefs.translationSizeScale().changes().map { },
        prefs.translationMinSize().changes().map { },
        prefs.translationOutline().changes().map { },
        prefs.translationLineSpacing().changes().map { },
        prefs.translationSkipSfx().changes().map { },
        prefs.translateToLanguage().changes().map { },
    )
        .onStart { emit(Unit) }
        .map { style(context) }
        .distinctUntilChanged()
}
