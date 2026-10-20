package eu.kanade.tachiyomi.ui.browse.migration.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.MigrateSourceScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.covers.BulkCoversScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Help
import mihon.icons.materialsymbols.rounded.Image
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.migrateSourceTab(): TabContent {
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.currentOrThrow
    val viewModel = metroViewModel<MigrateSourceViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // MANHWA: covers mode: a source picked here gets all its series' covers changed instead of being migrated
    var coverMode by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    return TabContent(
        titleRes = MR.strings.label_migration,
        actions = listOf(
            AppBar.Action(
                title = stringResource(ATMR.strings.covers_bulk_title),
                icon = MaterialSymbols.Rounded.Image,
                onClick = {
                    coverMode = !coverMode
                    context.toast(if (coverMode) ATMR.strings.covers_mode_on else ATMR.strings.covers_mode_off)
                },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.migration_help_guide),
                icon = MaterialSymbols.AutoMirroredRounded.Help,
                onClick = {
                    uriHandler.openUri("https://mihon.app/docs/guides/source-migration")
                },
            ),
        ),
        content = { contentPadding, _ ->
            MigrateSourceScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source ->
                    if (coverMode) {
                        navigator.push(BulkCoversScreen(source.id))
                    } else {
                        navigator.push(MigrateMangaScreen(source.id))
                    }
                },
                onToggleSortingDirection = viewModel::toggleSortingDirection,
                onToggleSortingMode = viewModel::toggleSortingMode,
            )
        },
    )
}
