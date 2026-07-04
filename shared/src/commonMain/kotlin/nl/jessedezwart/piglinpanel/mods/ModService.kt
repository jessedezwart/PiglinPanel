package nl.jessedezwart.piglinpanel.mods

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource
import okio.IOException

/** Live scan progress for UI indicators. */
data class ScanProgress(val phase: String, val done: Int, val total: Int, val fileName: String?)

class ModService(
    private val fileSystems: ServerFileSystemProvider,
    private val modrinthClient: ModUpdateSource,
    private val modHasher: ModHasher,
) {
    companion object {
        private const val DISABLED_SUFFIX = ".disabled"

        internal fun nextArchiveName(relativePath: String): String =
            if (relativePath.endsWith(DISABLED_SUFFIX)) relativePath else relativePath + DISABLED_SUFFIX

        internal fun enableName(relativePath: String): String =
            if (relativePath.endsWith(DISABLED_SUFFIX)) {
                relativePath.dropLast(DISABLED_SUFFIX.length)
            } else {
                relativePath
            }

        private fun joinPath(folderPath: String, fileName: String): String = when {
            folderPath.isBlank() -> fileName
            folderPath.endsWith("/") -> folderPath + fileName
            else -> "$folderPath/$fileName"
        }
    }

    private val updatesCache = SuspendCache<String, ModUpdate>(ttl = 24.hours, maxEntries = 500)

    suspend fun scan(
        profile: ServerProfile,
        checkUpdates: Boolean,
        forceRefresh: Boolean = false,
        progress: ((ScanProgress) -> Unit)? = null,
    ): List<ModFile> {
        val fileSystem = fileSystems.fileSystem(profile)

        data class Candidate(val folder: ModFolder, val path: String, val name: String, val size: Long, val sha1Key: String)

        val candidates = buildList {
            for (folder in profile.modFolders) {
                for (entry in fileSystem.list(folder.path)) {
                    if (entry.directory ||
                        (!entry.name.endsWith(".jar") && !entry.name.endsWith(".jar$DISABLED_SUFFIX"))
                    ) {
                        continue
                    }
                    add(folder to entry)
                }
            }
        }

        val mods = ArrayList<ModFile>(candidates.size)
        val lookupHashesByPath = LinkedHashMap<String, String>()
        val total = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            val (folder, entry) = candidate
            var sha1: String? = null
            if (checkUpdates) {
                sha1 = try {
                    modHasher.resolveSha1(profile, fileSystem, entry)
                } catch (e: IOException) {
                    null
                }
            }
            if (checkUpdates && sha1 != null) {
                lookupHashesByPath[entry.path] = sha1
            }
            mods += ModFile(
                folderId = folder.id,
                folderName = folder.name,
                folderPath = folder.path,
                relativePath = entry.path,
                fileName = entry.name,
                size = entry.size,
                archived = entry.name.endsWith(DISABLED_SUFFIX),
                sha1 = sha1,
                update = null,
            )
            progress?.invoke(ScanProgress("hashing", index + 1, total, entry.name))
        }

        if (checkUpdates && lookupHashesByPath.isNotEmpty()) {
            progress?.invoke(ScanProgress("lookup", 0, lookupHashesByPath.size, null))
            applySuggestedUpdates(mods, lookupHashesByPath, forceRefresh)
        }
        return mods
    }

    suspend fun upload(profile: ServerProfile, folder: ModFolder, fileName: String, bytes: ByteArray) {
        fileSystems.fileSystem(profile).writeBytes(joinPath(folder.path, fileName), bytes)
    }

    suspend fun delete(profile: ServerProfile, modFile: ModFile) {
        fileSystems.fileSystem(profile).delete(modFile.relativePath)
    }

    /**
     * Activates an archived (`.jar.disabled`) version by enabling it, after disabling the currently
     * active version of the same mod (if one is provided).
     */
    suspend fun activate(profile: ServerProfile, archivedPath: String, currentActivePath: String?) {
        if (!currentActivePath.isNullOrBlank()) {
            disablePath(profile, currentActivePath)
        }
        enablePath(profile, archivedPath)
    }

    suspend fun disablePath(profile: ServerProfile, relativePath: String) {
        val disabled = nextArchiveName(relativePath)
        if (disabled != relativePath) {
            fileSystems.fileSystem(profile).move(relativePath, disabled)
        }
    }

    suspend fun enablePath(profile: ServerProfile, relativePath: String) {
        val enabled = enableName(relativePath)
        if (enabled != relativePath) {
            fileSystems.fileSystem(profile).move(relativePath, enabled)
        }
    }

    suspend fun applyUpdate(profile: ServerProfile, modFile: ModFile) {
        val update = modFile.update
        if (update == null || !update.available()) {
            throw IOException("No update is available for ${modFile.fileName}")
        }
        val fileSystem = fileSystems.fileSystem(profile)
        val archivedName = nextArchiveName(modFile.relativePath)
        val bytes = modrinthClient.downloadUpdate(update)
        fileSystem.move(modFile.relativePath, archivedName)
        fileSystem.writeBytes(joinPath(modFile.folderPath, update.fileName!!), bytes)
    }

    /**
     * Collapses scanned files into one [ModGroup] per mod (keyed by Modrinth project when known,
     * otherwise by file path), surfacing the active version with its disabled versions sorted
     * newest first.
     */
    fun group(mods: List<ModFile>): List<ModGroup> {
        val byKey = LinkedHashMap<String, MutableList<ModFile>>()
        for (mod in mods) {
            byKey.getOrPut(groupKey(mod)) { mutableListOf() }.add(mod)
        }
        return byKey.map { (key, files) ->
            val active = files.firstOrNull { !it.archived }
            val representative = active ?: files.first()
            val archived = files.filter { it !== active }.sortedByDescending(::publishedAt)
            ModGroup(
                key = key,
                displayName = displayName(representative),
                iconUrl = files.firstNotNullOfOrNull { it.update?.iconUrl },
                folderName = representative.folderName,
                active = active,
                archived = archived,
                update = active?.update,
            )
        }
    }

    private fun groupKey(mod: ModFile): String =
        mod.update?.projectId?.let { "project:$it" } ?: "file:${mod.relativePath}"

    private fun displayName(mod: ModFile): String =
        mod.update?.projectName?.takeIf { it.isNotBlank() } ?: mod.fileName

    private fun publishedAt(mod: ModFile): Instant =
        mod.update?.lastUpdatedAt ?: Instant.fromEpochMilliseconds(0)

    private suspend fun applySuggestedUpdates(
        mods: MutableList<ModFile>,
        lookupHashesByPath: Map<String, String>,
        forceRefresh: Boolean,
    ) {
        val allHashes = lookupHashesByPath.values.distinct()

        val resolved = LinkedHashMap<String, ModUpdate>()
        val missing = mutableListOf<String>()
        for (hash in allHashes) {
            val cached = if (forceRefresh) null else updatesCache.getIfPresent(hash)
            if (cached != null) {
                resolved[hash] = cached
            } else {
                missing += hash
            }
        }

        if (missing.isNotEmpty()) {
            val fetched = try {
                modrinthClient.findSuggestedUpdates(missing)
            } catch (e: IOException) {
                emptyMap()
            }
            for (hash in missing) {
                val update = fetched[hash] ?: continue
                resolved[hash] = update
                updatesCache.put(hash, update)
            }
        }

        for (i in mods.indices) {
            val mod = mods[i]
            val hash = lookupHashesByPath[mod.relativePath] ?: continue
            mods[i] = mod.copy(update = resolved[hash] ?: mod.update)
        }
    }
}
