package nl.jessedezwart.piglinpanel.mods

import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.curseforge.ModFingerprintUpdateSource
import nl.jessedezwart.piglinpanel.fs.ServerFileSystem
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
) {
    companion object {
        private const val DISABLED_SUFFIX = ".disabled"
        private const val JAR_SUFFIX = ".jar"

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

        private fun parentPath(relativePath: String): String = relativePath.substringBeforeLast('/', "")

        /**
         * [relativePath] when nothing occupies it, otherwise the same name with a `~1`, `~2`, ...
         * tag inserted before the `.jar`.
         *
         * Two versions of a mod can carry the same file name -- an update that keeps its name
         * leaves exactly that behind -- and a move replaces its target, so archiving one on top of
         * the other silently deletes a version the user asked to keep. The tag goes before the
         * extension so the result is still a `.jar` (or `.jar.disabled`) that a scan picks up.
         */
        internal fun uniqueName(relativePath: String, taken: Set<String>): String {
            if (relativePath !in taken) return relativePath
            val disabled = relativePath.endsWith(DISABLED_SUFFIX)
            val stem = enableName(relativePath).removeSuffix(JAR_SUFFIX)
            var counter = 1
            while (true) {
                val candidate = "$stem~$counter$JAR_SUFFIX" + if (disabled) DISABLED_SUFFIX else ""
                if (candidate !in taken) return candidate
                counter++
            }
        }
    }

    private val updatesCache = SuspendCache<String, ModUpdate>(ttl = 24.hours, maxEntries = 1000)

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
                archived = entry.name.endsWith(DISABLED_SUFFIX),
                sha1 = sha1,
                update = null,
                metadata = hashes?.metadata,
                modifiedAt = entry.modifiedAt,
            )
        }

        if (checkUpdates) {
            // Persist before the lookups, so a failure there does not throw away the expensive part.
            modHasher.flush()
        }

        if (checkUpdates && lookupHashesByPath.isNotEmpty()) {
            progress?.invoke(ScanProgress("lookup", 0, lookupHashesByPath.size, null))
            applySuggestedUpdates(profile, mods, lookupHashesByPath, forceRefresh)
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
            if (other.folderId != target.folderId || !sameMod(other, target)) continue
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

    /**
     * Whether two files are versions of the same mod. The repository project and the jar's own mod
     * id both identify it across renames; the file name catches the pair a quick scan leaves
     * unidentified, where an update that reused its name is exactly what put two versions there.
     */
    private fun sameMod(a: ModFile, b: ModFile): Boolean =
        groupKey(a) == groupKey(b) || enableName(a.relativePath) == enableName(b.relativePath)

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

    /** The enabled mods that would lose a required dependency if [target] were disabled or deleted. */
    fun dependentsBrokenBy(mods: List<ModFile>, target: ModFile): List<String> =
        ModDependencyGraph.of(mods).wouldBreakIfRemoved(target).map(::displayName).distinct()

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
            else -> "file:${enableName(mod.relativePath)}"
        }
    }

    private fun displayName(mod: ModFile): String =
        mod.update?.projectName?.takeIf { it.isNotBlank() }
            ?: mod.metadata?.name?.takeIf { it.isNotBlank() }
            ?: mod.fileName

    /**
     * Looks each mod up on Modrinth by SHA-1 and, in the same pass, on CurseForge by fingerprint.
     * Both lookups are batched, so checking both costs a fixed handful of requests. A Modrinth
     * match wins where both repositories know a file; CurseForge covers the rest.
     */
    private suspend fun applySuggestedUpdates(
        profile: ServerProfile,
        mods: MutableList<ModFile>,
        lookupHashesByPath: Map<String, ModFileHashes>,
        forceRefresh: Boolean,
    ) {
        val loader = profile.modLoader
        val gameVersion = profile.mcVersion
        // The target is part of the key: the same jar has a different newest version depending on
        // which Minecraft version it is being asked about.
        val target = "${loader.orEmpty()}:${gameVersion.orEmpty()}"
        val resolved = LinkedHashMap<String, ModUpdate>()

        val missingSha1s = mutableListOf<String>()
        for (sha1 in lookupHashesByPath.values.map { it.sha1 }.distinct()) {
            val cached = if (forceRefresh) null else updatesCache.getIfPresent(modrinthCacheKey(target, sha1))
            if (cached != null) resolved[sha1] = cached else missingSha1s += sha1
        }
        if (missingSha1s.isNotEmpty()) {
            val fetched = try {
                modrinthClient.findSuggestedUpdates(missingSha1s, loader, gameVersion)
            } catch (e: IOException) {
                emptyMap()
            }
            for (sha1 in missingSha1s) {
                val update = fetched[sha1] ?: continue
                resolved[sha1] = update
                updatesCache.put(modrinthCacheKey(target, sha1), update)
            }
        }

        // Every file is offered to CurseForge too, not only the ones Modrinth missed: the
        // fingerprint lookup is a single batched request either way, and which repositories carry
        // a mod is worth showing in its own right.
        val curseForgeResolved = LinkedHashMap<Long, ModUpdate>()
        val fingerprints = lookupHashesByPath.values.map { it.fingerprint }.distinct()
        val curseForge = curseForgeClient
        if (curseForge != null && curseForge.available && fingerprints.isNotEmpty()) {
            val missingFingerprints = mutableListOf<Long>()
            for (fingerprint in fingerprints) {
                val cached = if (forceRefresh) {
                    null
                } else {
                    updatesCache.getIfPresent(curseForgeCacheKey(target, fingerprint))
                }
                if (cached != null) curseForgeResolved[fingerprint] = cached else missingFingerprints += fingerprint
            }
            if (missingFingerprints.isNotEmpty()) {
                val fetched = try {
                    curseForge.findSuggestedUpdates(missingFingerprints, loader, gameVersion)
                } catch (e: IOException) {
                    emptyMap()
                }
                for (fingerprint in missingFingerprints) {
                    val update = fetched[fingerprint] ?: continue
                    curseForgeResolved[fingerprint] = update
                    updatesCache.put(curseForgeCacheKey(target, fingerprint), update)
                }
            }
        }

        for (i in mods.indices) {
            val mod = mods[i]
            val hashes = lookupHashesByPath[mod.relativePath] ?: continue
            val modrinth = resolved[hashes.sha1]
            val curse = curseForgeResolved[hashes.fingerprint]
            // Modrinth's match is the one that gets applied; CurseForge's is recorded either way.
            // Either repository calling it a library is enough; they categorise independently.
            val applied = (modrinth ?: curse)?.let { it.copy(library = it.library || curse?.library == true) }
            mods[i] = mod.copy(
                update = applied ?: mod.update,
                sources = buildSet {
                    if (modrinth != null) add(ModSource.MODRINTH)
                    if (curse != null) add(ModSource.CURSEFORGE)
                },
            )
        }
    }

    private fun modrinthCacheKey(target: String, sha1: String): String = "modrinth:$target:$sha1"

    private fun curseForgeCacheKey(target: String, fingerprint: Long): String = "curseforge:$target:$fingerprint"
}
