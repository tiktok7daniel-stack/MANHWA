package eu.kanade.translation.local

import android.content.Context
import mihon.app.di.appGraph
import java.io.File

/**
 * MANHWA phone ensemble: every model on the phone plays, each in the role it is best at, together with the keys.
 * - the drafter (the quickest model) drafts every line of the chapter at once,
 * - the polisher (the main, strongest model) rereads the lines the phone keeps, while the keys improve the rest
 *   at the same time,
 * - the judge (a third model when there is one, else another than the polisher) picks between two translations
 *   that disagree, so the deciding voice is not the one that wrote either line.
 * With one model, it plays every role. The roles are set again by themselves whenever a model is added, removed
 * or chosen as the main one or the judge.
 */
object PhoneEnsemble {

    data class Lineup(val drafter: File, val polisher: File, val judge: File, val all: List<File>) {
        /** More than one model really plays. */
        val ensemble: Boolean get() = all.size > 1
    }

    /** The lineup now, or null when there is no model on the phone (or the on-device AI is off). */
    fun lineup(context: Context): Lineup? {
        val main = LocalAiEngine.modelFile(context) ?: return null
        val prefs = context.appGraph.translationPreferences
        val catalog = runCatching {
            ModelCatalog.models.mapNotNull { m ->
                (ModelCatalog.status(context, m) as? ModelCatalog.Status.Ready)?.file?.let { f ->
                    EnsembleRules.Candidate(f.absolutePath, m.strength, f.length())
                }
            }
        }.getOrDefault(emptyList())
        val known = catalog.associateBy { it.path }
        // An imported model that is not on the shelf counts as a middle one
        val candidates = (catalog + listOfNotNull(
            main.takeIf { it.absolutePath !in known }?.let { EnsembleRules.Candidate(it.absolutePath, 3, it.length()) },
        )).distinctBy { it.path }
        val roles = EnsembleRules.assign(
            candidates,
            mainPath = main.absolutePath,
            judgePath = prefs.translationLocalJudgePath().get(),
            budget = EnsembleRules.budgetBytes(LocalModelRam.totalBytes(context)),
        ) ?: return null
        return Lineup(File(roles.drafter), File(roles.polisher), File(roles.judge), roles.all.map(::File))
    }

    /** A model's name for the screen, from the shelf when it is one of its models. */
    fun nameOf(context: Context, file: File): String =
        ModelCatalog.models.firstOrNull { m -> (ModelCatalog.status(context, m) as? ModelCatalog.Status.Ready)?.file?.absolutePath == file.absolutePath }?.name
            ?: file.nameWithoutExtension
}

/** The pure rules of the phone ensemble (tested without a phone). */
object EnsembleRules {

    data class Candidate(val path: String, val strength: Int, val bytes: Long)

    data class Roles(val drafter: String, val polisher: String, val judge: String, val all: List<String>)

    /** The part of the phone's memory the models may hold together (the rest stays for the app and the system). */
    fun budgetBytes(totalRam: Long): Long = if (totalRam <= 0) 4L * GB else totalRam * 45 / 100

    /** A model of [next] bytes can be loaded next to [loaded] within [budget]. */
    fun fits(loaded: List<Long>, next: Long, budget: Long): Boolean = loaded.sum() + next <= budget

    /**
     * Sets the roles: the polisher is the reader's main model (else the strongest); the drafter is the quickest
     * other model that can stay loaded with it (else the polisher drafts too); the judge is the model the reader
     * chose, else the strongest one that wrote nothing, else one that is not the polisher.
     */
    fun assign(candidates: List<Candidate>, mainPath: String, judgePath: String, budget: Long): Roles? {
        if (candidates.isEmpty()) return null
        val polisher = candidates.firstOrNull { it.path == mainPath }
            ?: candidates.maxWith(compareBy<Candidate> { it.strength }.thenBy { -it.bytes })
        val others = candidates.filter { it.path != polisher.path }
        // The drafter stays loaded next to the polisher, so both work at the same time
        val drafter = others.filter { it.strength >= MIN_DRAFT_STRENGTH && fits(listOf(polisher.bytes), it.bytes, budget) }
            .minByOrNull { it.bytes } ?: polisher
        val chosenJudge = candidates.firstOrNull { judgePath.isNotBlank() && it.path == judgePath }
        val judge = chosenJudge
            ?: others.filter { it.path != drafter.path }.maxByOrNull { it.strength }
            ?: others.maxByOrNull { it.strength }
            ?: polisher
        val all = listOf(drafter, polisher, judge).map { it.path }.distinct()
        return Roles(drafter.path, polisher.path, judge.path, all)
    }

    private const val GB = 1024L * 1024 * 1024
    private const val MIN_DRAFT_STRENGTH = 2
}
