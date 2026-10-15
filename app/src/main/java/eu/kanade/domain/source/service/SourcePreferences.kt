package eu.kanade.domain.source.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.domain.extension.model.ContentWarning
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode

@Inject
@SingleIn(AppScope::class)
class SourcePreferences(
    preferenceStore: PreferenceStore,
) {

    val sourceDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    /** MANHWA: keeps +18 sources, extensions and series out of every list (on by default). */
    val hideAdult: Preference<Boolean> = preferenceStore.getBoolean("manhwa_hide_adult", true)

    /** MANHWA: fame scores the team's AI gave to sources the built-in index doesn't know, as JSON. */
    val sourceFameAi: Preference<String> = preferenceStore.getString("manhwa_source_fame_ai", "{}")

    /** MANHWA: privately installed extensions update on their own, with no prompt (on by default). */
    val autoUpdateExtensions: Preference<Boolean> = preferenceStore.getBoolean("manhwa_auto_update_extensions", true)

    /** MANHWA: when the stores were last checked for extension updates in the background. */
    val lastExtensionCheck: Preference<Long> = preferenceStore.getLong("manhwa_last_extension_check", 0L)

    /** MANHWA: how the source hub sorts sources: 0 fame, 1 speed, 2 A to Z. */
    val hubSort: Preference<Int> = preferenceStore.getInt("manhwa_hub_sort", 0)

    // MANHWA: sources whose content is gathered on the home screen
    /** Whether the built-in MANHWA extension store was already added on first run. */
    val manhwaBuiltinStore: Preference<Boolean> = preferenceStore.getBoolean("manhwa_builtin_store", false)

    /** Last AI recommendations as JSON [{"m": mangaId, "r": reason}], and when they were made. */
    val manhwaAiRecs: Preference<String> = preferenceStore.getString("manhwa_ai_recs", "")

    /** MANHWA reading game: the level each manhwa had when last seen, and when it went up, as JSON. */
    val manhwaSeenLevels: Preference<String> = preferenceStore.getString("manhwa_seen_levels", "{}")
    val manhwaAiRecsAt: Preference<Long> = preferenceStore.getLong("manhwa_ai_recs_at", 0L)

    /** The reader's order of the home sources, as comma separated ids. */
    val homeSourcesOrder: Preference<String> = preferenceStore.getString("manhwa_home_sources_order", "")

    val homeSources: Preference<Set<String>> = preferenceStore.getStringSet("manhwa_home_sources", emptySet())

    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    val enabledContentWarnings: Preference<Set<ContentWarning>> = preferenceStore.getEnumSet(
        "enabled_content_warnings",
        setOf(ContentWarning.SAFE, ContentWarning.MIXED, ContentWarning.NSFW),
    )

    val applyContentWarningsToInstalled: Preference<Boolean> = preferenceStore.getBoolean(
        "apply_content_warnings_to_installed",
        true,
    )

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    val extensionRepos: Preference<Set<String>> = preferenceStore.getStringSet("extension_repos", emptySet())

    val extensionUpdatesCount: Preference<Int> = preferenceStore.getInt("ext_updates_count", 0)

    val trustedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    val globalSearchFilterState: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_chapters",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )
}
