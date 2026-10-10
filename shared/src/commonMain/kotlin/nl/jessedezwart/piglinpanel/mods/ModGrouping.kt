package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup

/** Which scanned files are versions of the same mod, and what the mods need from one another. */
object ModGrouping {
    /**
     * Collapses scanned files into one [ModGroup] per mod (keyed by the resolved Modrinth/CurseForge
     * project when known, otherwise by file path), surfacing the active version with its disabled versions sorted
     * newest first.
     */
    fun group(mods: List<ModFile>): List<ModGroup> {
        val byKey = LinkedHashMap<String, MutableList<ModFile>>()
        for (mod in mods) {
            byKey.getOrPut(groupKey(mod)) { mutableListOf() }.add(mod)
        }
        val graph = ModDependencyGraph.of(mods)
        return byKey.map { (key, files) ->
            val active = files.firstOrNull { !it.archived }
            val representative = active ?: files.first()
            // Every other file of this mod. Usually the disabled versions, but a second *enabled*
            // jar lands here too — that is a broken state worth surfacing, so it sorts to the top
            // rather than being hidden.
            val archived = files.asSequence()
                .filter { it !== active }
                .sortedWith(compareBy<ModFile> { it.archived }.thenByDescending { it.fileName })
                .toList()
            ModGroup(
                key = key,
                displayName = displayName(representative),
                iconUrl = files.firstNotNullOfOrNull { it.update?.iconUrl },
                folderName = representative.folderName,
                active = active,
                archived = archived,
                update = active?.update,
                side = representative.side,
                sources = representative.sources,
                downloads = files.maxOf { it.update?.downloads ?: 0L },
                modifiedAt = representative.modifiedAt,
                missingDependencies = active?.let(graph::missingDependenciesOf).orEmpty(),
                versionConflicts = active?.let(graph::unsatisfiedDependenciesOf).orEmpty(),
                disabledDependencies = active
                    ?.let { graph.disabledDependenciesOf(it).map(::displayName) }
                    .orEmpty()
                    .distinct(),
                library = representative.library,
                unusedLibrary = active != null && active.library && active.metadata != null && !graph.isUsed(active),
            )
        }
    }

    /**
     * The disabled jars [target] needs in order to load, resolved transitively. Reported rather
     * than acted on: enabling a jar on the user's behalf changes what the server loads, and the
     * point of the dependency check is to tell them, not to decide for them.
     */
    fun disabledDependenciesOf(mods: List<ModFile>, target: ModFile): List<ModFile> =
        ModDependencyGraph.of(mods).disabledDependenciesOf(target)

    /** The hard dependencies of each of [targets], keyed by relative path; see [ModDependencyGraph.requiredDependenciesOf]. */
    fun requiredDependenciesOf(mods: List<ModFile>, targets: List<ModFile>): Map<String, List<RequiredDependency>> {
        val graph = ModDependencyGraph.of(mods)
        return targets.associate { it.relativePath to graph.requiredDependenciesOf(it) }
    }

    /** The enabled mods that would lose a required dependency if [target] were disabled or deleted. */
    fun dependentsBrokenBy(mods: List<ModFile>, target: ModFile): List<String> =
        ModDependencyGraph.of(mods).wouldBreakIfRemoved(target).map(::displayName).distinct()

    /**
     * Whether two files are versions of the same mod. The repository project and the jar's own mod
     * id both identify it across renames; the file name catches the pair a quick scan leaves
     * unidentified, where an update that reused its name is exactly what put two versions there.
     */
    internal fun sameMod(a: ModFile, b: ModFile): Boolean =
        groupKey(a) == groupKey(b) || ModJarNames.enableName(a.relativePath) == ModJarNames.enableName(b.relativePath)

    /**
     * Identity of the mod a file belongs to, so its versions collapse into one group. The jar's own
     * mod id backs up the repository match: it groups mods no repository indexes, and — unlike the
     * file path — it does not change when a version is enabled or disabled.
     */
    private fun groupKey(mod: ModFile): String {
        val update = mod.update
        val projectId = update?.projectId
        return when {
            projectId != null -> "project:${update.source}:$projectId"
            mod.metadata != null -> "mod:${mod.metadata.modId}"
            else -> "file:${ModJarNames.enableName(mod.relativePath)}"
        }
    }

    private fun displayName(mod: ModFile): String =
        mod.update?.projectName?.takeIf { it.isNotBlank() }
            ?: mod.metadata?.name?.takeIf { it.isNotBlank() }
            ?: mod.fileName
}
