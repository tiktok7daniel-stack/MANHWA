package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.lifecycle.viewModelScope
import eu.kanade.translation.search.AiSearchAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.TranslationPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class GlobalSearchViewModel(
    @Assisted initialQuery: String,
    @Assisted initialExtensionFilter: String?,
    sourcePreferences: SourcePreferences,
    sourceManager: SourceManager,
    extensionManager: ExtensionManager,
    networkToLocalManga: NetworkToLocalManga,
    getManga: GetManga,
    preferences: SourcePreferences,
    translationPreferences: TranslationPreferences,
) : SearchViewModel(
    initialState = State(searchQuery = initialQuery),
    sourcePreferences = sourcePreferences,
    sourceManager = sourceManager,
    extensionManager = extensionManager,
    networkToLocalManga = networkToLocalManga,
    getManga = getManga,
    preferences = preferences,
) {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialQuery: String, initialExtensionFilter: String?): GlobalSearchViewModel
    }

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    // Danghwa: AI search turns a description or half-remembered title into real titles, searched in every source
    private val aiAssistant = AiSearchAssistant(translationPreferences)
    private val _aiState = MutableStateFlow<AiSearchState>(AiSearchState.Idle)
    val aiState = _aiState.asStateFlow()

    fun aiSearch() {
        val request = state.value.searchQuery?.takeIf { it.isNotBlank() } ?: return
        _aiState.value = AiSearchState.Loading
        viewModelScope.launch {
            _aiState.value = try {
                val queries = aiAssistant.suggestQueries(request)
                if (queries.isEmpty()) {
                    AiSearchState.Error(null)
                } else {
                    searchAllSources(queries.first())
                    AiSearchState.Suggestions(request, queries, queries.first())
                }
            } catch (e: AiSearchAssistant.NotConfiguredException) {
                AiSearchState.NotConfigured
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AiSearchState.Error(e.message)
            }
        }
    }

    fun searchSuggestion(query: String) {
        searchAllSources(query)
        _aiState.update { if (it is AiSearchState.Suggestions) it.copy(selected = query) else it }
    }

    private fun searchAllSources(query: String) {
        updateSearchQuery(query)
        if (state.value.sourceFilter != SourceFilter.All) setSourceFilter(SourceFilter.All) else search()
    }

    override suspend fun getEnabledSources(): List<Source> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != SourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}

sealed interface AiSearchState {
    data object Idle : AiSearchState
    data object Loading : AiSearchState
    data object NotConfigured : AiSearchState
    data class Suggestions(val request: String, val queries: List<String>, val selected: String) : AiSearchState
    data class Error(val message: String?) : AiSearchState
}
