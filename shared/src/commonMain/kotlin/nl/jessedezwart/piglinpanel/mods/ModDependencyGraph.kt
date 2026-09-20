package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.model.ModFile

/**
 * A dependency that is installed and enabled, but at a version the dependent will not accept.
 *
 * Kept apart from a missing dependency because the fix differs: this one needs the *other* mod
 * moved to a different version, not a download of something absent.
 */
data class VersionConflict(
    /** The id as the dependent names it. */
    val id: String,
    /** The range the dependent declares, verbatim, in whichever loader's syntax it wrote it. */
    val range: String,
    /** The version of the enabled jar providing [id]. */
    val installed: String,
) {
    /** `fabric-api >=0.100.0 (have 0.92.2)`, for a table cell or a warning line. */
    override fun toString(): String = "$id $range (have $installed)"
}

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
        return enabled.filter { it !== file && it.declared.requires.any { dep -> dep.id in ids } }
    }

    /**
     * Whether any other enabled mod declares [file] as a dependency, required or optional. False for
     * a file without metadata, which cannot be matched against anything — check that separately.
     */
    fun isUsed(file: ModFile): Boolean {
        val ids = file.metadata?.ids()?.toSet() ?: return false
        return enabled.any { other ->
            other !== file && (other.declared.requires + other.declared.optional).any { it.id in ids }
        }
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
                .map { it.id }
                .filter { it in ids }
                .any { id -> providersAmong(enabled, id).none { it !== file } }
        }
    }

    /** Required ids of [file] that nothing enabled provides — the mod is broken as it stands. */
    fun missingDependenciesOf(file: ModFile): List<String> {
        val metadata = file.metadata ?: return emptyList()
        return metadata.requires.map { it.id }.filter { id -> providersAmong(enabled, id).isEmpty() }
    }

    /**
     * Required dependencies of [file] that are enabled but at a version its range rules out — the
     * failure a batch of updates produces, where every id is present and the loader still refuses.
     *
     * Only a provider whose *own* [ModJarMetadata.modId] matches is version-checked. An id that is
     * merely `provides`-ed, which is how a bundled library shows up, carries the host jar's version
     * rather than the library's, and comparing a range against the wrong version would invent
     * conflicts. Unreadable ranges and jars that declare no version count as satisfied; see
     * [ModVersionRange].
     */
    fun unsatisfiedDependenciesOf(file: ModFile): List<VersionConflict> {
        val metadata = file.metadata ?: return emptyList()
        return metadata.requires.mapNotNull { dependency ->
            val range = dependency.range ?: return@mapNotNull null
            val namedProviders = providersAmong(enabled, dependency.id)
                .filter { it !== file && it.declared.modId == dependency.id }
            if (namedProviders.isEmpty()) return@mapNotNull null
            if (namedProviders.any { ModVersionRange.satisfies(it.declared.version, range) }) return@mapNotNull null
            // Every provider fell short, so any of them names the version to report; the newest is
            // the one the user would have to move away from.
            val installed = namedProviders.mapNotNull { it.declared.version }
                .maxWithOrNull(ModVersionRange::compare)
                ?: return@mapNotNull null
            VersionConflict(dependency.id, range, installed)
        }
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
        val queue = ArrayDeque(file.metadata?.requires.orEmpty().map { it.id })
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
                queue.addAll(candidate.declared.requires.map { it.id })
            }
        }
        return resolved.values.toList()
    }
}
