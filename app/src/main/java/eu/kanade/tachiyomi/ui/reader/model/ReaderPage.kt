package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    translation: PageTranslation? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    private val _translationFlow = MutableStateFlow(translation)

    /** The page's translation; live translation can set it while the page is on screen. */
    val translationFlow: StateFlow<PageTranslation?> = _translationFlow.asStateFlow()

    var translation: PageTranslation?
        get() = _translationFlow.value
        set(value) {
            _translationFlow.value = value
        }
}
