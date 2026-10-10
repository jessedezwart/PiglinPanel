package nl.jessedezwart.piglinpanel.mods

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import nl.jessedezwart.piglinpanel.curseforge.ModFingerprintUpdateSource
import nl.jessedezwart.piglinpanel.fs.ServerFileSystem
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource
import nl.jessedezwart.piglinpanel.mods.ModJarNames.enableName
import nl.jessedezwart.piglinpanel.mods.ModJarNames.joinPath
import nl.jessedezwart.piglinpanel.mods.ModJarNames.nextArchiveName
import nl.jessedezwart.piglinpanel.mods.ModJarNames.parentPath
import nl.jessedezwart.piglinpanel.mods.ModJarNames.uniqueName
import nl.jessedezwart.piglinpanel.search.ModSource
import okio.IOException

/** Live scan progress for UI indicators. */
data class ScanProgress(val phase: String, val done: Int, val total: Int, val fileName: String?)

/**
 * What one applied update left on disk, and everything needed to put it back.
 *
 * Collected per update rather than per batch so a revert can undo exactly what landed, including
 * the paths the uniquifier had to pick when a name was taken.
 */
data class AppliedUpdate(
    val displayName: String,
    /** Where the version that was replaced now sits, disabled. */
    val archivedPath: String,
    /** The jar written in its place. */
    val installedPath: String,
)

class ModService(
    private val fileSystems: ServerFileSystemProvider,
    private val modrinthClient: ModUpdateSource,
    private val modHasher: ModHasher,
    private val curseForgeClient: ModFingerprintUpdateSource? = null,
    private val modListStore: ModListStore? = null,
) {
    private val updateLookup = ModUpdateLookup(modrinthClient, curseForgeClient)

    /**
     * The mod list as the last [scan] of [profile] left it, without contacting the server. Something
     * to show while the folders are listed; a jar that has since been removed is still in it.
     */
    suspend fun lastKnown(profile: ServerProfile): List<ModFile> =
        modListStore?.load(profile.id, profile.updateTarget()).orEmpty()

    /**
     * Lists the mod folders of [profile]. With [checkUpdates] every new or changed jar is read and
     * the repositories are asked about all of them; without it nothing is read, and a jar keeps what
     * earlier scans learned about it for as long as its name, size and modification time still match.
     */
    suspend fun scan(
        profile: ServerProfile,
        checkUpdates: Boolean,
        forceRefresh: Boolean = false,
        progress: ((ScanProgress) -> Unit)? = null,
    ): List<ModFile> {
        val fileSystem = fileSystems.fileSystem(profile)

        val candidates = buildList {
            for (folder in profile.modFolders) {
                for (entry in fileSystem.list(folder.path)) {
                    if (entry.directory || !ModJarNames.isModJar(entry.name)) continue
                    add(folder to entry)
                }
            }
        }

        val total = candidates.size
        val progressLock = Mutex()
        var hashed = 0

        // Reading a jar costs a round trip and then a pass over every byte of it, and the folder
        // holds hundreds. Done one at a time that is the slowest thing the app does, so several
        // run at once; how many is the transport's call, since only it knows what a burst costs.
        val permits = Semaphore(fileSystem.readConcurrency)
        val resolved: List<ModFileHashes?> = coroutineScope {
            candidates.map { (_, entry) ->
                async {
                    val hashes = permits.withPermit {
                        // A quick scan reads nothing, but it can still reuse what an earlier scan
                        // learned, so dependency warnings do not disappear the moment you use it.
                        if (checkUpdates) {
                            try {
                                modHasher.resolveHashes(profile, fileSystem, entry)
                            } catch (e: IOException) {
                                null
                            }
                        } else {
                            modHasher.cachedHashes(profile, entry)
                        }
                    }
                    if (progress != null) {
                        // Counted as each finishes rather than by position, so the bar still moves
                        // forward one step at a time while the reads themselves land out of order.
                        // Reported under the lock too, or two finishing at once could deliver their
                        // counts the wrong way round and step the bar backwards.
                        progressLock.withLock {
                            progress(ScanProgress("hashing", ++hashed, total, entry.name))
                        }
                    }
                    hashes
                }
            }.awaitAll()
        }

        // What the repositories said about each jar last time, by content hash. It fills the list in
        // when no lookup is made, and stands in for a lookup that fails; a jar that changed has a
        // new hash (or, unread, none) and so starts from nothing.
        val known = lastKnown(profile).filter { it.sha1 != null }.associateBy { it.sha1 }

        val mods = ArrayList<ModFile>(candidates.size)
        val lookupHashesByPath = LinkedHashMap<String, ModFileHashes>()
        for ((index, candidate) in candidates.withIndex()) {
            val (folder, entry) = candidate
            val hashes = resolved[index]
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
                archived = entry.name.endsWith(ModJarNames.DISABLED_SUFFIX),
                sha1 = sha1,
                update = known[sha1]?.update,
                metadata = hashes?.metadata,
                modifiedAt = entry.modifiedAt,
                sources = known[sha1]?.sources.orEmpty(),
            )
        }

        if (checkUpdates) {
            // Persist before the lookups, so a failure there does not throw away the expensive part.
            modHasher.flush()
        }

        if (checkUpdates && lookupHashesByPath.isNotEmpty()) {
            progress?.invoke(ScanProgress("lookup", 0, lookupHashesByPath.size, null))
            updateLookup.applySuggestedUpdates(profile, mods, lookupHashesByPath, forceRefresh)
        }
        modListStore?.save(profile.id, profile.updateTarget(), mods)
        return mods
    }

    suspend fun upload(profile: ServerProfile, folder: ModFolder, fileName: String, bytes: ByteArray) {
        fileSystems.fileSystem(profile).writeBytes(joinPath(folder.path, fileName), bytes)
    }

    suspend fun delete(profile: ServerProfile, modFile: ModFile) {
        fileSystems.fileSystem(profile).delete(modFile.relativePath)
    }

    /**
     * Activates an archived (`.jar.disabled`) version, disabling every other enabled version of the
     * same mod in the same folder first.
     *
     * Which files count as the same mod is decided here from [mods] — the last scan — rather than
     * being named by the caller: a caller that has not resolved a mod's identity, which a quick
     * scan never does, would otherwise leave the old jar enabled alongside the new one. Folders are
     * kept apart on purpose, since a profile that lists a server folder and a client modpack folder
     * holds those copies deliberately.
     */
    suspend fun activate(profile: ServerProfile, mods: List<ModFile>, target: ModFile) {
        val fileSystem = fileSystems.fileSystem(profile)
        val taken = HashMap<String, MutableSet<String>>()
        for (other in mods) {
            if (other.archived || other.relativePath == target.relativePath) continue
            if (other.folderId != target.folderId || !ModGrouping.sameMod(other, target)) continue
            moveUnique(fileSystem, other.relativePath, nextArchiveName(other.relativePath), taken)
        }
        moveUnique(fileSystem, target.relativePath, enableName(target.relativePath), taken)
    }

    suspend fun disablePath(profile: ServerProfile, relativePath: String) {
        val fileSystem = fileSystems.fileSystem(profile)
        moveUnique(fileSystem, relativePath, nextArchiveName(relativePath), HashMap())
    }

    suspend fun enablePath(profile: ServerProfile, relativePath: String) {
        val fileSystem = fileSystems.fileSystem(profile)
        moveUnique(fileSystem, relativePath, enableName(relativePath), HashMap())
    }

    suspend fun applyUpdate(profile: ServerProfile, modFile: ModFile): AppliedUpdate =
        applyUpdate(profile, modFile, downloadUpdate(modFile))

    /**
     * Downloads the jar [modFile]'s update would install, without touching the server. Separated
     * from [applyUpdate] so a batch can fetch everything first and only then start renaming: a
     * download that fails after half the folder has been rewritten is the expensive kind of
     * failure, and this is what makes it impossible.
     */
    suspend fun downloadUpdate(modFile: ModFile): ByteArray {
        val update = modFile.update
        if (update == null || !update.available()) {
            throw IOException("No update is available for ${modFile.fileName}")
        }
        return when (update.source) {
            ModSource.MODRINTH -> modrinthClient.downloadUpdate(update)
            ModSource.CURSEFORGE -> curseForgeClient?.downloadUpdate(update)
                ?: throw IOException("CurseForge is not configured; cannot update ${modFile.fileName}")
        }
    }

    /** Archives [modFile] and writes [bytes] in its place, using the update's own file name. */
    suspend fun applyUpdate(profile: ServerProfile, modFile: ModFile, bytes: ByteArray): AppliedUpdate {
        val update = modFile.update
        if (update?.fileName == null) {
            throw IOException("No update is available for ${modFile.fileName}")
        }
        val fileSystem = fileSystems.fileSystem(profile)
        // The archive is named around whatever is already there, so an update that keeps the jar's
        // file name archives the version it replaces instead of overwriting an older archive.
        val taken = HashMap<String, MutableSet<String>>()
        val archived = moveUnique(fileSystem, modFile.relativePath, nextArchiveName(modFile.relativePath), taken)
        val target = freeName(fileSystem, joinPath(modFile.folderPath, update.fileName), taken)
        fileSystem.writeBytes(target, bytes)
        return AppliedUpdate(
            displayName = update.projectName?.takeIf { it.isNotBlank() } ?: modFile.fileName,
            archivedPath = archived,
            installedPath = target,
        )
    }

    /**
     * Puts back the version an update replaced: the new jar goes, the archived one is enabled again
     * under its old name.
     *
     * Reverting is only ever renames and one delete because [applyUpdate] archives rather than
     * overwrites, which is what makes undoing a whole batch cheap enough to offer.
     */
    suspend fun revertUpdate(profile: ServerProfile, applied: AppliedUpdate) {
        val fileSystem = fileSystems.fileSystem(profile)
        // Deleted first, so the archived jar can take back the name it may have been using.
        try {
            fileSystem.delete(applied.installedPath)
        } catch (e: IOException) {
            // Already gone, by another revert or by hand; the rename below is still worth trying.
        }
        moveUnique(fileSystem, applied.archivedPath, enableName(applied.archivedPath), HashMap())
    }

    /** Release notes of the version [update] would install, from whichever repository resolved it. */
    suspend fun changelog(update: ModUpdate): String? = when (update.source) {
        ModSource.MODRINTH -> modrinthClient.changelog(update)
        ModSource.CURSEFORGE -> curseForgeClient?.changelog(update) ?: update.changelog
    }

    /**
     * Moves [from] to [desired], or to the nearest free name when something else already sits
     * there. [taken] caches the names each folder holds across a sequence of moves; pass a fresh
     * map per operation.
     */
    private suspend fun moveUnique(
        fileSystem: ServerFileSystem,
        from: String,
        desired: String,
        taken: MutableMap<String, MutableSet<String>>,
    ): String {
        if (from == desired) return from
        val target = freeName(fileSystem, desired, taken)
        fileSystem.move(from, target)
        taken[parentPath(from)]?.remove(from)
        return target
    }

    /** [desired] if its folder has nothing by that name, otherwise a tagged variant that is free. */
    private suspend fun freeName(
        fileSystem: ServerFileSystem,
        desired: String,
        taken: MutableMap<String, MutableSet<String>>,
    ): String {
        val parent = parentPath(desired)
        val names = taken.getOrElse(parent) {
            fileSystem.list(parent).mapTo(mutableSetOf()) { it.path }.also { taken[parent] = it }
        }
        val unique = uniqueName(desired, names)
        names += unique
        return unique
    }
}
