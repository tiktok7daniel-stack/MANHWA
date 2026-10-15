package eu.kanade.translation.translator

import android.app.Application
import mihon.app.di.appGraph
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * MANHWA: the AI behind the app's other features (search, chat, home, descriptions, library): the translation
 * team's lead, its strongest working key. Null when the team has no working key. The caller closes it.
 */
fun appAi(): AiTranslator? = runCatching { Injekt.get<Application>().appGraph.teamStore }.getOrNull()?.leadTranslator()

/** Whether [appAi] has a key to work with. */
fun appAiConfigured(): Boolean =
    runCatching { Injekt.get<Application>().appGraph.teamStore.active().isNotEmpty() }.getOrDefault(false)
