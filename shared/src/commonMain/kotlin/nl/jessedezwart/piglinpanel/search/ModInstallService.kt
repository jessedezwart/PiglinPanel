package nl.jessedezwart.piglinpanel.search

import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ModService

/**
 * Installs a mod found via [ModSearchService] plus its required dependencies, resolved recursively
 * within the same source (Modrinth/CurseForge dependency ids don't cross sources). Optional
 * dependencies and already-installed mods are skipped; a failure resolving or downloading one item
 * doesn't abort the rest.
 *
 * A dependency lands in the same folder as the mod that pulled it in, rather than in the folder the
 * install started from: a profile with a server and a client folder needs the library next to the
 * mod that loads it, and a mod already on disk stays where it is instead of dragging its
 * dependencies to the other side.
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

    /** [folder] is where this item's jar goes: the folder of whatever pulled it in. */
    private data class PendingItem(
        val source: ModSource,
        val projectId: String,
        val versionId: String?,
        val title: String,
        val folder: ModFolder,
    )

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

        // Where each mod already on disk lives, so anything it needs joins it there. A jar in a
        // folder the profile no longer lists has nowhere to anchor to and is left out.
        val foldersById = profile.modFolders.associateBy { it.id }
        val folderByProjectId = HashMap<String, ModFolder>()
        val folderByFileName = HashMap<String, ModFolder>()
        for (mod in existingMods) {
            val modFolder = foldersById[mod.folderId] ?: continue
            mod.update?.projectId?.let { folderByProjectId.getOrPut(it) { modFolder } }
            folderByFileName.getOrPut(normalizeFileName(mod.fileName)) { modFolder }
        }

        val installed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        val queue = ArrayDeque<PendingItem>()
        queue.add(
            PendingItem(
                source = hit.source,
                projectId = hit.projectId,
                versionId = null,
                title = hit.title,
                // Reinstalling over a copy that lives elsewhere keeps that copy's folder, so the
                // rest of the graph follows the mod instead of the folder the user was browsing in.
                folder = folderByProjectId[hit.projectId] ?: folder,
            ),
        )

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

                val fileKey = normalizeFileName(version.fileName)
                // This jar's home: where a copy of it already sits, otherwise alongside its dependent.
                val targetFolder = folderByFileName[fileKey] ?: item.folder
                if (fileKey in installedFileNames) {
                    skipped += version.fileName
                } else {
                    onProgress?.invoke(InstallProgress("downloading", item.title, installed.size + skipped.size, visited.size))
                    val bytes = source.downloadFile(version)
                    onProgress?.invoke(InstallProgress("writing", item.title, installed.size + skipped.size, visited.size))
                    modService.upload(profile, targetFolder, version.fileName, bytes)
                    installed += version.fileName
                    installedFileNames += fileKey
                    folderByFileName[fileKey] = targetFolder
                }

                for (dependency in version.dependencies) {
                    if (!dependency.required) continue
                    queue.add(
                        PendingItem(
                            source = dependency.source,
                            projectId = dependency.projectId,
                            versionId = dependency.versionId,
                            title = dependency.projectId,
                            folder = folderByProjectId[dependency.projectId] ?: targetFolder,
                        ),
                    )
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
