package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.model.ModFile

/**
 * The dependency relationships between the mods actually installed on a server, built from what the
 * jars declare rather than from a repository, so it covers mods no repository has indexed.
 *
 * Files whose metadata could not be read take no part: they neither require nor provide anything.
 * That makes every answer here a *lower bound* — a jar this cannot parse may still be depended on,
 * so an empty [dependentsOf] means "nothing known", not "nothing".
 */
class ModDependencyGraph private constructor(
    private val enabled: List<ModFile>,
    private val disabled: List<ModFile>,
) {
    companion object {
        fun of(mods: List<ModFile>): ModDependencyGraph =
            ModDependencyGraph(
                enabled = mods.filter { !it.archived && it.metadata != null },
                disabled = mods.filter { it.archived && it.metadata != null },
            )
    }

    /** Safe because [of] admits only files that have metadata. */
    private val ModFile.declared: ModJarMetadata get() = metadata!!

    /** Every id each file answers to, indexed for provider lookups. */
    private fun providersAmong(files: List<ModFile>, id: String): List<ModFile> =
        files.filter { id in it.declared.ids() }

    /** The enabled mods that declare a hard dependency on something [file] provides. */
    fun dependentsOf(file: ModFile): List<ModFile> {
        val ids = file.metadata?.ids()?.toSet() ?: return emptyList()
        return enabled.filter { it !== file && it.declared.requires.any { id -> id in ids } }
    }

    /**
     * The enabled mods that would be left without one of their required dependencies if [file] were
     * disabled or deleted — that is, [dependentsOf] minus any whose dependency is also satisfied by
     * a second enabled jar. This is what is worth interrupting the user for.
     */
    fun wouldBreakIfRemoved(file: ModFile): List<ModFile> {
        val ids = file.metadata?.ids()?.toSet() ?: return emptyList()
        return dependentsOf(file).filter { dependent ->
            dependent.metadata!!.requires
                .filter { it in ids }
                .any { id -> providersAmong(enabled, id).none { it !== file } }
        }
    }

    /** Required ids of [file] that nothing enabled provides — the mod is broken as it stands. */
    fun missingDependenciesOf(file: ModFile): List<String> {
        val metadata = file.metadata ?: return emptyList()
        return metadata.requires.filter { id -> providersAmong(enabled, id).isEmpty() }
    }

    /**
     * The disabled jars that would have to be enabled for [file] to load, resolved transitively:
     * enabling a dependency can itself pull in further dependencies.
     *
     * Ids that nothing installed provides are not reported here — see [missingDependenciesOf];
     * those need a download, not a rename.
     */
    fun disabledDependenciesOf(file: ModFile): List<ModFile> {
        val resolved = LinkedHashMap<String, ModFile>()
        val satisfied = enabled.flatMap { it.declared.ids() }.toMutableSet()
        val queue = ArrayDeque(file.metadata?.requires.orEmpty())
        val seen = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id) || id in satisfied) continue
            // Prefer a jar whose own id matches, over one that merely `provides` the id.
            // [file] itself is excluded: the caller is enabling it, and a dependency cycle back to
            // it would otherwise list it as its own dependency.
            val candidate = providersAmong(disabled, id)
                .filter { it !== file }
                .minByOrNull { if (it.declared.modId == id) 0 else 1 }
                ?: continue
            if (resolved.put(candidate.relativePath, candidate) == null) {
                satisfied += candidate.declared.ids()
                queue.addAll(candidate.declared.requires)
            }
        }
        return resolved.values.toList()
    }
}
