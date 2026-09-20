package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ServerProfile

/** Live progress while a plan is being put together, since every candidate is downloaded first. */
data class PlanProgress(val done: Int, val total: Int, val displayName: String?)

/**
 * Why one mod cannot be updated as part of this batch, in the words the table shows.
 *
 * [affected] is the mod that would break, which is not always the mod being updated: bumping a
 * library breaks whatever declared a range the new version falls outside of.
 */
data class UpdateProblem(val affected: String, val detail: String) {
    override fun toString(): String = "$affected: $detail"
}

/** One candidate update, once the planner has fetched its jar and seen what it declares. */
data class PlannedUpdate(
    val file: ModFile,
    val displayName: String,
    /** The downloaded jar, ready for [ModService.applyUpdate]; null when the download failed. */
    val bytes: ByteArray?,
    /** What the new jar declares, or null when it carries no manifest this reads. */
    val metadata: ModJarMetadata?,
    /** Why it could not be fetched at all. Null when it was. */
    val failure: String? = null,
    /** What this update would break. Empty when it is safe to apply. */
    val problems: List<UpdateProblem> = emptyList(),
) {
    val safe: Boolean get() = failure == null && bytes != null && problems.isEmpty()

    // A ByteArray field makes the generated equals/hashCode compare references, which is both
    // surprising and useless here; identity is what the planner and the UI actually key on.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = file.relativePath.hashCode()
}

/**
 * A whole batch of updates, decided before anything on the server has been touched.
 */
data class UpdatePlan(val entries: List<PlannedUpdate>) {
    val safe: List<PlannedUpdate> get() = entries.filter { it.safe }
    val blocked: List<PlannedUpdate> get() = entries.filter { it.failure == null && it.problems.isNotEmpty() }
    val failed: List<PlannedUpdate> get() = entries.filter { it.failure != null }
}

/**
 * Works out what a set of updates would do to the dependency chain before any of it is applied.
 *
 * Updates are resolved one mod at a time — each jar is looked up by its own hash — so nothing in
 * that path can see that the new Sodium wants a newer Fabric API than the one the batch leaves in
 * place, or that bumping a library past a range strands a mod that was fine a minute ago. Applying
 * twenty of them and reading the server log afterwards is the current way to find out.
 *
 * So every candidate is downloaded first and read with [ModJarMetadataReader] — the same parser
 * the installed jars go through, so the ids line up and the version ranges are the real ones
 * rather than a repository's summary of them. The pack is then rebuilt hypothetically, with each
 * candidate swapped in, and compared against how it looks today. Only problems the batch would
 * *introduce* are reported: a mod that is already missing a dependency stays that way, and saying
 * so here would blame the update for it.
 *
 * The downloads are kept in the plan, so applying it costs no second fetch.
 */
class ModUpdatePlanner(private val modService: ModService) {
    /**
     * [candidates] are the enabled files whose [ModFile.update] should be applied; [mods] is the
     * whole last scan, which is what the projection is built from.
     */
    suspend fun plan(
        profile: ServerProfile,
        mods: List<ModFile>,
        candidates: List<ModFile>,
        displayNames: Map<String, String> = emptyMap(),
        progress: ((PlanProgress) -> Unit)? = null,
    ): UpdatePlan {
        fun nameOf(file: ModFile): String =
            displayNames[file.relativePath]
                ?: file.update?.projectName?.takeIf { it.isNotBlank() }
                ?: file.metadata?.name?.takeIf { it.isNotBlank() }
                ?: file.metadata?.modId
                ?: file.fileName

        val fetched = LinkedHashMap<String, Pair<ByteArray?, String?>>()
        val newMetadata = HashMap<String, ModJarMetadata?>()
        candidates.forEachIndexed { index, file ->
            progress?.invoke(PlanProgress(index, candidates.size, nameOf(file)))
            try {
                val bytes = modService.downloadUpdate(file)
                fetched[file.relativePath] = bytes to null
                newMetadata[file.relativePath] = ModJarMetadataReader.read(bytes, profile.modLoader)
            } catch (e: Exception) {
                fetched[file.relativePath] = null to (e.message ?: e::class.simpleName ?: "download failed")
            }
        }
        progress?.invoke(PlanProgress(candidates.size, candidates.size, null))

        // Only what was actually fetched takes part in the projection; a mod whose download failed
        // is not going to be applied, so the pack should be judged as if it stays where it is.
        val applicable = candidates.filter { fetched[it.relativePath]?.first != null }
        val projected = mods.map { mod ->
            if (applicable.none { it.relativePath == mod.relativePath }) mod else mod.copy(metadata = newMetadata[mod.relativePath])
        }

        val before = problemsByPath(mods)
        val after = problemsByPath(projected)
        val introduced = after.mapNotNull { (path, problems) ->
            val fresh = problems - before[path].orEmpty()
            if (fresh.isEmpty()) null else path to fresh
        }.toMap()

        // Which candidate to hang each new problem on. A problem on a mod being updated is its
        // own; a problem elsewhere belongs to whichever candidate provides the id it names, since
        // that is the jar whose version moved.
        val byPath = applicable.associateBy { it.relativePath }
        val blame = HashMap<String, MutableList<UpdateProblem>>()
        for ((path, problems) in introduced) {
            val affected = projected.first { it.relativePath == path }
            for (problem in problems) {
                val culprit = when {
                    path in byPath -> path
                    else -> applicable.firstOrNull { candidate ->
                        val ids = (newMetadata[candidate.relativePath]?.ids().orEmpty() +
                            candidate.metadata?.ids().orEmpty()).toSet()
                        problem.id in ids
                    }?.relativePath
                }
                val detail = UpdateProblem(nameOf(affected), problem.detail)
                // A problem no candidate can be blamed for is still worth showing, so it is
                // attached to every candidate rather than dropped: the user picks what to deselect.
                if (culprit != null) {
                    blame.getOrPut(culprit) { mutableListOf() }.add(detail)
                } else {
                    applicable.forEach { blame.getOrPut(it.relativePath) { mutableListOf() }.add(detail) }
                }
            }
        }

        return UpdatePlan(
            candidates.map { file ->
                val (bytes, failure) = fetched[file.relativePath] ?: (null to "not fetched")
                PlannedUpdate(
                    file = file,
                    displayName = nameOf(file),
                    bytes = bytes,
                    metadata = newMetadata[file.relativePath],
                    failure = failure,
                    problems = blame[file.relativePath].orEmpty().distinct(),
                )
            },
        )
    }

    /** One problem a projection turned up, keyed so the same problem before and after cancels out. */
    private data class Problem(val id: String, val detail: String)

    private fun problemsByPath(mods: List<ModFile>): Map<String, Set<Problem>> {
        val graph = ModDependencyGraph.of(mods)
        return mods.filter { !it.archived && it.metadata != null }.associate { file ->
            val missing = graph.missingDependenciesOf(file).map { Problem(it, "missing $it") }
            val conflicts = graph.unsatisfiedDependenciesOf(file).map { Problem(it.id, "needs $it") }
            file.relativePath to (missing + conflicts).toSet()
        }
    }
}
