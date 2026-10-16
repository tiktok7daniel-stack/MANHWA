package eu.kanade.translation.local

import android.content.Context
import mihon.app.di.appGraph
import java.io.File

/**
 * MANHWA phone ensemble: every model on the phone the reader keeps active plays, together with the keys.
 * One model is the main one (the reader's choice); the others are its helpers, ranked by strength:
 * - the main model rereads the lines the phone keeps, while the keys improve the rest at the same time,
 * - the quickest helper drafts every line of the chapter at once,
 * - every other helper is a judge: when two translations disagree, all the judges vote (the strongest
 *   breaks a tie), so the deciding voice is not the one that wrote either line.
 * With one model, it plays every role. The roles are set again by themselves whenever a model is added,
 * removed, switched on or off, or chosen as the main one.
 */
object PhoneEnsemble {

    data class Member(val file: File, val name: String, val strength: Int, val role: EnsembleRules.Role)

    data class Lineup(val drafter: File, val polisher: File, val judges: List<File>, val members: List<Member>) {
        /** More than one model really plays. */
        val ensemble: Boolean get() = members.size > 1
        val judge: File get() = judges.first()
    }

    /** The lineup now, or null when there is no model on the phone (or the on-device AI is off). */
    fun lineup(context: Context): Lineup? {
        val main = LocalAiEngine.modelFile(context) ?: return null
        val prefs = context.appGraph.translationPreferences
        val inactive = prefs.translationInactiveModels().get()
        val names = HashMap<String, String>()
        val catalog = runCatching {
            ModelCatalog.models.mapNotNull { m ->
                (ModelCatalog.status(context, m) as? ModelCatalog.Status.Ready)?.file?.let { f ->
                    names[f.absolutePath] = m.name
                    EnsembleRules.Candidate(f.absolutePath, m.strength, f.length())
                }
            }
        }.getOrDefault(emptyList())
        val known = catalog.associateBy { it.path }
        // An imported model that is not on the shelf counts as a middle one
        val candidates = (catalog + listOfNotNull(
            main.takeIf { it.absolutePath !in known }?.let { EnsembleRules.Candidate(it.absolutePath, 3, it.length()) },
        )).distinctBy { it.path }
            // The main model always plays; the others when the reader keeps them on
            .filter { it.path == main.absolutePath || it.path !in inactive }
            // Only as many as the reader chose (the main one first, then the strongest)
            .let { EnsembleRules.limit(it, main.absolutePath, prefs.translationEnsembleSize().get()) }
        val roles = EnsembleRules.assign(
            candidates,
            mainPath = main.absolutePath,
            judgePath = prefs.translationLocalJudgePath().get(),
            budget = EnsembleRules.budgetBytes(LocalModelRam.totalBytes(context)),
        ) ?: return null
        val members = roles.members.map { (path, role) ->
            val c = candidates.first { it.path == path }
            Member(File(path), names[path] ?: File(path).nameWithoutExtension, c.strength, role)
        }
        return Lineup(File(roles.drafter), File(roles.polisher), roles.judges.map(::File), members)
    }

    /** A model's name for the screen, from the shelf when it is one of its models. */
    fun nameOf(context: Context, file: File): String =
        ModelCatalog.models.firstOrNull { m -> (ModelCatalog.status(context, m) as? ModelCatalog.Status.Ready)?.file?.absolutePath == file.absolutePath }?.name
            ?: file.nameWithoutExtension
}

/** The pure rules of the phone ensemble (tested without a phone). */
object EnsembleRules {

    data class Candidate(val path: String, val strength: Int, val bytes: Long)

    /** What a model does in the lineup. */
    enum class Role { MAIN, DRAFTS, JUDGES, DRAFTS_AND_JUDGES }

    data class Roles(val drafter: String, val polisher: String, val judges: List<String>, val members: List<Pair<String, Role>>) {
        val judge: String get() = judges.first()
        val all: List<String> get() = members.map { it.first }
    }

    /** The part of the phone's memory the models may hold together (the rest stays for the app and the system). */
    fun budgetBytes(totalRam: Long): Long = if (totalRam <= 0) 4L * GB else totalRam * 45 / 100

    /** A model of [next] bytes can be loaded next to [loaded] within [budget]. */
    fun fits(loaded: List<Long>, next: Long, budget: Long): Boolean = loaded.sum() + next <= budget

    /**
     * Sets the roles of the active models: the main one is the reader's choice (else the strongest); the drafter
     * is the quickest helper that can stay loaded next to it (else the main drafts too); every other helper
     * judges, strongest first (the judge the reader chose leads); with a single helper it drafts and judges.
     */
    fun assign(candidates: List<Candidate>, mainPath: String, judgePath: String, budget: Long): Roles? {
        if (candidates.isEmpty()) return null
        val main = candidates.firstOrNull { it.path == mainPath }
            ?: candidates.maxWith(compareBy<Candidate> { it.strength }.thenBy { -it.bytes })
        val helpers = candidates.filter { it.path != main.path }
            .sortedWith(compareByDescending<Candidate> { it.strength }.thenBy { it.bytes })
        val drafter = helpers.filter { it.strength >= MIN_DRAFT_STRENGTH && fits(listOf(main.bytes), it.bytes, budget) }
            .minByOrNull { it.bytes } ?: main
        // The judge the reader chose always votes, even when it also drafts
        val voters = helpers.filter { it.path != drafter.path || it.path == judgePath }.ifEmpty { helpers }.ifEmpty { listOf(main) }
        val judges = voters.sortedByDescending { judgePath.isNotBlank() && it.path == judgePath }.map { it.path }.take(MAX_JUDGES)
        val members = buildList {
            add(main.path to Role.MAIN)
            helpers.forEach { h ->
                val drafts = h.path == drafter.path
                val judgesToo = h.path in judges
                when {
                    drafts && judgesToo -> add(h.path to Role.DRAFTS_AND_JUDGES)
                    drafts -> add(h.path to Role.DRAFTS)
                    judgesToo -> add(h.path to Role.JUDGES)
                }
            }
        }
        return Roles(drafter.path, main.path, judges, members)
    }

    /** The main model and the strongest others, [max] models at most (0 or less: all of them). */
    fun limit(candidates: List<Candidate>, mainPath: String, max: Int): List<Candidate> {
        if (max <= 0 || candidates.size <= max) return candidates
        val main = candidates.filter { it.path == mainPath }
        val others = candidates.filter { it.path != mainPath }.sortedWith(compareByDescending<Candidate> { it.strength }.thenBy { it.bytes })
        return (main + others).take(max)
    }

    /** The judges' answer: the most votes; a tie goes to the first (strongest) judge that voted. Null without votes. */
    fun vote(votes: List<Int?>): Int? {
        val given = votes.filterNotNull()
        if (given.isEmpty()) return null
        val counts = given.groupingBy { it }.eachCount()
        val best = counts.values.max()
        val leaders = counts.filterValues { it == best }.keys
        return if (leaders.size == 1) leaders.first() else given.first { it in leaders }
    }

    private const val GB = 1024L * 1024 * 1024
    private const val MIN_DRAFT_STRENGTH = 2
    private const val MAX_JUDGES = 4
}
