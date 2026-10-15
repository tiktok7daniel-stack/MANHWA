package eu.kanade.translation.translator

import android.app.Application
import eu.kanade.translation.search.AiSearchAssistant
import eu.kanade.translation.team.AiRouter
import eu.kanade.translation.team.TeamStore
import mihon.app.di.appGraph
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private fun teamStore(): TeamStore? = runCatching { Injekt.get<Application>().appGraph.teamStore }.getOrNull()

/**
 * MANHWA: one AI call for the app's other features (search, chat, home, descriptions, library), through the
 * team's router: it waits behind translation, skips keys low on quota, uses a model that suits [lane], and moves
 * to the next key when one is busy or fails. Throws [AiSearchAssistant.NotConfiguredException] with no usable key.
 */
suspend fun <T> appAsk(
    lane: AiRouter.Lane,
    prefer: (TeamStore.Member) -> Boolean = { true },
    block: suspend (AiTranslator) -> T,
): T {
    val store = teamStore() ?: throw AiSearchAssistant.NotConfiguredException()
    return try {
        store.router.ask(lane, prefer = prefer, block = block)
    } catch (e: AiRouter.NoKeyException) {
        throw AiSearchAssistant.NotConfiguredException()
    }
}

/** Whether the team has a key to work with. */
fun appAiConfigured(): Boolean = teamStore()?.active()?.isNotEmpty() == true

/** Keys whose provider can search the web (Gemini, Claude, OpenRouter). */
fun TeamStore.Member.canSearchWeb(): Boolean =
    providerEnum == AiTranslator.Provider.GEMINI || providerEnum == AiTranslator.Provider.CLAUDE ||
        providerEnum == AiTranslator.Provider.OPENROUTER
