package eu.kanade.domain.source.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.domain.extension.model.ContentWarning

/**
 * MANHWA +18 isolation: knows which sources and extensions are adult (marked NSFW by their store, or adult by
 * name) and, while the reader keeps them hidden (the default), keeps them out of every list in the app. The reverse
 * mode ([SourcePreferences.onlyAdult]) keeps every other source out instead, so only the +18 ones show.
 */
@Inject
@SingleIn(AppScope::class)
class AdultFilter(
    private val preferences: SourcePreferences,
    extensionManager: ExtensionManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ids of the installed sources whose extension is marked NSFW or has an adult name. */
    val adultSourceIds: StateFlow<Set<Long>> = extensionManager.loadedExtensionsFlow
        .map { loaded ->
            loaded.filter { it.contentWarning == ContentWarning.NSFW || isAdultName(it.name) }
                .flatMap { extension -> extension.sources.map { it.id } }
                .toSet()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun hiding(): Boolean = preferences.hideAdult.get()

    /** Only the +18 sources show (the reverse mode). */
    fun onlyAdult(): Boolean = preferences.onlyAdult.get()

    /** Whether the source with [id] and [name] is kept out right now. */
    fun hides(id: Long, name: String): Boolean =
        keepsOut(adult = id in adultSourceIds.value || isAdultName(name), hide = hiding(), only = onlyAdult())

    fun hides(extension: Extension): Boolean =
        keepsOut(adult = extension.contentWarning == ContentWarning.NSFW || isAdultName(extension.name), hide = hiding(), only = onlyAdult())

    /** Changes whenever either switch changes, for lists that must be built again. */
    fun modeChanges(): Flow<Pair<Boolean, Boolean>> =
        combine(preferences.hideAdult.changes(), preferences.onlyAdult.changes()) { hide, only -> hide to only }

    /** A test that follows the switches and the installed extensions, for lists built from flows. */
    fun rule(): Flow<(Long, String) -> Boolean> =
        combine(modeChanges(), adultSourceIds) { (hide, only), ids ->
            { id: Long, name: String -> keepsOut(adult = id in ids || isAdultName(name), hide = hide, only = only) }
        }

    companion object {
        /** Whether a source is kept out: the reverse mode keeps out what is not +18, else hiding keeps out what is. */
        fun keepsOut(adult: Boolean, hide: Boolean, only: Boolean): Boolean = if (only) !adult else hide && adult

        private val ADULT_WORDS = listOf(
            "hentai", "porn", "nsfw", "18+", "+18", "adult", "manhwa18", "pornhwa", "doujin", "smut", "erotic", "xxx",
            "lewd", "rule34", "hitomi", "pururin", "tsumino", "nhentai", "hentaifox", "hentai2read", "manytoon",
            "omegascans", "hiperdex", "toonily", "manhwaclan", "هنتاي", "اباحي", "إباحي", "للكبار",
        )

        fun isAdultName(name: String): Boolean {
            val n = name.lowercase()
            return ADULT_WORDS.any { n.contains(it) } || Regex("\\b18\\s?\\+").containsMatchIn(n)
        }
    }
}
