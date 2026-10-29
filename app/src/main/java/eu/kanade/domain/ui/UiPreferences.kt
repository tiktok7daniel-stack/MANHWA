package eu.kanade.domain.ui

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Inject
@SingleIn(AppScope::class)
class UiPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val themeMode: Preference<ThemeMode> = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.DARK)

    val appTheme: Preference<AppTheme> = preferenceStore.getEnum(
        "pref_app_theme",
        AppTheme.DANGHWA,
    )

    val themeDarkAmoled: Preference<Boolean> = preferenceStore.getBoolean("pref_theme_dark_amoled_key", true)

    /** MANHWA accent color, applied on top of the MANHWA Glass theme. */
    val manhwaAccent: Preference<Int> = preferenceStore.getInt("manhwa_accent", 0xFFFF3B4E.toInt())

    /** MANHWA look of the whole app: "GLASS" (MANHWA Glass, the default) or "OBSIDIAN" (Obsidian Royal). */
    val manhwaStyle: Preference<String> = preferenceStore.getString("manhwa_style", "GLASS")

    /**
     * MANHWA: the app's system, a whole app inside the app (its home, series page, chapters and navigation):
     * "CLASSIC" (the original) or "NOVA" (the second). More systems are added as more names (AppSystem).
     */
    val appSystem: Preference<String> = preferenceStore.getString("manhwa_app_system", "CLASSIC")

    /** The source Nova's home shows (its "server"), -1 until the reader picks one. */
    val novaSourceId: Preference<Long> = preferenceStore.getLong("manhwa_nova_source", -1L)

    // MANHWA: the home-screen icon, the opening that plays on a cold start, and how it plays
    val logoStyle: Preference<String> = preferenceStore.getString("manhwa_logo_style", "CLASSIC")
    val introStyle: Preference<String> = preferenceStore.getString("manhwa_intro_style", "AUTO")
    val introEnabled: Preference<Boolean> = preferenceStore.getBoolean("manhwa_intro_enabled", true)

    /** MANHWA: the works' songs, everywhere (the music bar, the player, songs playing in the pages and chapters). */
    val musicEnabled: Preference<Boolean> = preferenceStore.getBoolean("manhwa_music_enabled", true)
    val introSpeed: Preference<Float> = preferenceStore.getFloat("manhwa_intro_speed", 1f)

    /** Obsidian Royal in its light version (silk paper) instead of the dark one. */
    val manhwaStyleLight: Preference<Boolean> = preferenceStore.getBoolean("manhwa_style_light", false)

    // MANHWA: one switch per home section (see HomeSection), keyed so a new section defaults on its own
    fun homeSection(key: String, shownByDefault: Boolean): Preference<Boolean> =
        preferenceStore.getBoolean("home_show_$key", shownByDefault)

    val relativeTime: Preference<Boolean> = preferenceStore.getBoolean("relative_time_v2", true)

    val dateFormat: Preference<String> = preferenceStore.getString("app_date_format", "")

    val tabletUiMode: Preference<TabletUiMode> = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    val imagesInDescription: Preference<Boolean> = preferenceStore.getBoolean("pref_render_images_description", true)

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
