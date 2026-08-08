package nl.jessedezwart.piglinpanel.search

import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ModService

/**
 * Installs a mod found via [ModSearchService] plus its required dependencies, resolved recursively
 * within the same source (Modrinth/CurseForge dependency ids don't cross sources). Optional
 * dependencies and already-installed mods are skipped; a failure resolving or downloading one item
 * doesn't abort the rest.
 */
class ModInstallService(
    private val modService: ModService,
    private val sources: () -> List<ModSearchSource>,
) {
    companion object {
        /** Guards against pathological/cyclic dependency graphs. */
        private const val MAX_GRAPH_NODES = 25
        private const val DISABLED_SUFFIX = ".disabled"
    }

    private data class PendingItem(val source: ModSource, val projectId: String, val versionId: String?, val title: String)

    suspend fun install(
        profile: ServerProfile,
        folder: ModFolder,
        hit: ModSearchHit,
        loader: String?,
        mcVersion: String?,
        onProgress: ((InstallProgress) -> Unit)? = null,
    ): InstallResult {
        val sourceForHit = sourceFor(hit.source)
            ?: return InstallResult(emptyList(), emptyList(), listOf("${hit.source} is not available"))

        val existingMods = modService.scan(profile, checkUpdates = true)
        val installedProjectIds = existingMods.mapNotNull { it.update?.projectId }.toMutableSet()
        val installedFileNames = existingMods.map { normalizeFileName(it.fileName) }.toMutableSet()

        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        val queue = ArrayDeque<PendingItem>()
        queue.add(PendingItem(hit.source, hit.projectId, versionId = null, title = hit.title))

        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            val key = "${item.source}:${item.projectId}"
            if (!visited.add(key)) continue
            if (visited.size > MAX_GRAPH_NODES) {
                failures += "Dependency graph for ${hit.title} is too large; stopped after $MAX_GRAPH_NODES mods"
                break
            }

            if (item.projectId in installedProjectIds) {
                skipped += item.title
                continue
            }

            val source = sourceFor(item.source)
            if (source == null) {
                failures += "${item.title}: ${item.source} is not available"
                continue
            }

            try {
                onProgress?.invoke(InstallProgress("resolving", item.title, installed.size + skipped.size, visited.size))
                val version = if (item.versionId != null) {
                    source.version(item.projectId, item.versionId)
                } else {
                    source.latestCompatibleVersion(item.projectId, loader, mcVersion)
                }
                if (version == null) {
                    failures += "${item.title}: no compatible version for the server's loader/Minecraft version"
                    continue
                }

                if (normalizeFileName(version.fileName) in installedFileNames) {
                    skipped += version.fileName
                } else {
                    onProgress?.invoke(InstallProgress("downloading", item.title, installed.size + skipped.size, visited.size))
                    val bytes = source.downloadFile(version)
                    onProgress?.invoke(InstallProgress("writing", item.title, installed.size + skipped.size, visited.size))
                    modService.upload(profile, folder, version.fileName, bytes)
                    installed += version.fileName
                    installedFileNames += normalizeFileName(version.fileName)
                }

                for (dependency in version.dependencies) {
                    if (!dependency.required) continue
                    queue.add(PendingItem(dependency.source, dependency.projectId, dependency.versionId, dependency.projectId))
                }
            } catch (e: Exception) {
                failures += "${item.title}: ${e.message ?: e::class.simpleName}"
            }
        }

        return InstallResult(installed, skipped, failures)
    }

    private fun sourceFor(source: ModSource): ModSearchSource? =
        sources().firstOrNull { it.source == source && it.available }

    private fun normalizeFileName(fileName: String): String =
        fileName.removeSuffix(DISABLED_SUFFIX).lowercase()
}
