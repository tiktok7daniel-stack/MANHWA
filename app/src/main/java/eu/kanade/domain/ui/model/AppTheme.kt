package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * MANHWA ships one look: MANHWA Glass. Its colour variants are accents on top of it (see [UiPreferences.manhwaAccent]),
 * so the old Mihon themes are gone. A stored old theme name simply falls back to this one.
 */
enum class AppTheme(val titleRes: StringResource?) {
    DANGHWA(MR.strings.theme_danghwa),
}
