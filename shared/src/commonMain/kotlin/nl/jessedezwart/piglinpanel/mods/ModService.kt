package nl.jessedezwart.piglinpanel.mods

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.curseforge.ModFingerprintUpdateSource
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource
import nl.jessedezwart.piglinpanel.search.ModSource
import okio.IOException

/** Live scan progress for UI indicators. */
data class ScanProgress(val phase: String, val done: Int, val total: Int, val fileName: String?)

class ModService(
    private val fileSystems: ServerFileSystemProvider,
    private val modrinthClient: ModUpdateSource,
    private val modHasher: ModHasher,
    private val curseForgeClient: ModFingerprintUpdateSource? = null,
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
        val lookupHashesByPath = LinkedHashMap<String, ModFileHashes>()
        val total = candidates.size
        for ((index, candidate) in candidates.withIndex()) {
            val (folder, entry) = candidate
            var hashes: ModFileHashes? = null
            if (checkUpdates) {
                hashes = try {
                    modHasher.resolveHashes(profile, fileSystem, entry)
                } catch (e: IOException) {
                    null
                }
            }
            if (hashes != null) {
                lookupHashesByPath[entry.path] = hashes
            }
            val sha1 = hashes?.sha1
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
        val bytes = when (update.source) {
            ModSource.MODRINTH -> modrinthClient.downloadUpdate(update)
            ModSource.CURSEFORGE -> curseForgeClient?.downloadUpdate(update)
                ?: throw IOException("CurseForge is not configured; cannot update ${modFile.fileName}")
        }
        fileSystem.move(modFile.relativePath, archivedName)
        fileSystem.writeBytes(joinPath(modFile.folderPath, update.fileName!!), bytes)
    }

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

    private fun groupKey(mod: ModFile): String {
        val update = mod.update
        val projectId = update?.projectId
        return if (projectId != null) "project:${update.source}:$projectId" else "file:${mod.relativePath}"
    }

    private fun displayName(mod: ModFile): String =
        mod.update?.projectName?.takeIf { it.isNotBlank() } ?: mod.fileName

    private fun publishedAt(mod: ModFile): Instant =
        mod.update?.lastUpdatedAt ?: Instant.fromEpochMilliseconds(0)

    /**
     * Looks each mod up on Modrinth by SHA-1 and, in the same pass, on CurseForge by fingerprint.
     * Both lookups are batched, so checking both costs a fixed handful of requests. A Modrinth
     * match wins where both repositories know a file; CurseForge covers the rest.
     */
    private suspend fun applySuggestedUpdates(
        mods: MutableList<ModFile>,
        lookupHashesByPath: Map<String, ModFileHashes>,
        forceRefresh: Boolean,
    ) {
        val resolved = LinkedHashMap<String, ModUpdate>()

        val missingSha1s = mutableListOf<String>()
        for (sha1 in lookupHashesByPath.values.map { it.sha1 }.distinct()) {
            val cached = if (forceRefresh) null else updatesCache.getIfPresent(modrinthCacheKey(sha1))
            if (cached != null) resolved[sha1] = cached else missingSha1s += sha1
        }
        if (missingSha1s.isNotEmpty()) {
            val fetched = try {
                modrinthClient.findSuggestedUpdates(missingSha1s)
            } catch (e: IOException) {
                emptyMap()
            }
            for (sha1 in missingSha1s) {
                val update = fetched[sha1] ?: continue
                resolved[sha1] = update
                updatesCache.put(modrinthCacheKey(sha1), update)
            }
        }

        // Fingerprints of the files Modrinth had nothing for.
        val curseForgeResolved = LinkedHashMap<Long, ModUpdate>()
        val unmatchedFingerprints = lookupHashesByPath.values
            .filter { it.sha1 !in resolved }
            .map { it.fingerprint }
            .distinct()
        val curseForge = curseForgeClient
        if (curseForge != null && curseForge.available && unmatchedFingerprints.isNotEmpty()) {
            val missingFingerprints = mutableListOf<Long>()
            for (fingerprint in unmatchedFingerprints) {
                val cached = if (forceRefresh) null else updatesCache.getIfPresent(curseForgeCacheKey(fingerprint))
                if (cached != null) curseForgeResolved[fingerprint] = cached else missingFingerprints += fingerprint
            }
            if (missingFingerprints.isNotEmpty()) {
                val fetched = try {
                    curseForge.findSuggestedUpdates(missingFingerprints)
                } catch (e: IOException) {
                    emptyMap()
                }
                for (fingerprint in missingFingerprints) {
                    val update = fetched[fingerprint] ?: continue
                    curseForgeResolved[fingerprint] = update
                    updatesCache.put(curseForgeCacheKey(fingerprint), update)
                }
            }
        }

        for (i in mods.indices) {
            val mod = mods[i]
            val hashes = lookupHashesByPath[mod.relativePath] ?: continue
            val update = resolved[hashes.sha1] ?: curseForgeResolved[hashes.fingerprint] ?: mod.update
            mods[i] = mod.copy(update = update)
        }
    }

    private fun modrinthCacheKey(sha1: String): String = "modrinth:$sha1"

    private fun curseForgeCacheKey(fingerprint: Long): String = "curseforge:$fingerprint"
}
