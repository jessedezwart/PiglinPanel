package nl.jessedezwart.piglinpanel.mods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.jessedezwart.piglinpanel.model.ModFile
import okio.FileSystem
import okio.Path

/**
 * Persists the result of the last scan of each server, so the mod list can be shown the moment the
 * app opens instead of after a scan — and so what the repositories said about each jar survives a
 * restart, which [ModHashStore] alone does not cover.
 *
 * The list is a starting point, not the truth: the caller still lists the mod folders and keeps
 * only what matches. Failures are swallowed, as in [ModHashStore]: losing this costs a scan.
 */
class ModListStore(
    private val fileSystem: FileSystem,
    private val path: Path,
) {
    private companion object {
        /** Bumped whenever the stored shape of a [ModFile] changes in a way old files cannot be read as. */
        const val FORMAT_VERSION = 1
    }

    /**
     * [target] is the loader and Minecraft version the updates were resolved against. A list stored
     * for another target is not returned: its update suggestions answer a different question.
     */
    @Serializable
    private data class Snapshot(val target: String, val mods: List<ModFile>)

    @Serializable
    private data class Document(val version: Int, val profiles: Map<String, Snapshot>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Guards the one-shot read and keeps writes from overtaking each other. */
    private val lock = Mutex()
    private var profiles: MutableMap<String, Snapshot>? = null

    suspend fun load(profileId: String, target: String): List<ModFile> = lock.withLock {
        restore()[profileId]?.takeIf { it.target == target }?.mods.orEmpty()
    }

    suspend fun save(profileId: String, target: String, mods: List<ModFile>) = lock.withLock {
        val snapshot = Snapshot(target, mods)
        val all = restore()
        if (all[profileId] == snapshot) return@withLock
        all[profileId] = snapshot
        write(Document(FORMAT_VERSION, all.toMap()))
    }

    private suspend fun restore(): MutableMap<String, Snapshot> {
        profiles?.let { return it }
        val restored = withContext(Dispatchers.Default) { read() }.toMutableMap()
        profiles = restored
        return restored
    }

    private fun read(): Map<String, Snapshot> {
        val text = try {
            if (!fileSystem.exists(path)) return emptyMap()
            fileSystem.read(path) { readUtf8() }
        } catch (e: okio.IOException) {
            return emptyMap()
        }
        val document = try {
            json.decodeFromString<Document>(text)
        } catch (e: Exception) {
            return emptyMap()
        }
        return if (document.version == FORMAT_VERSION) document.profiles else emptyMap()
    }

    private suspend fun write(document: Document) = withContext(Dispatchers.Default) {
        // Written beside the target and moved into place, so a crash mid-write cannot leave a
        // half-file that would fail to parse on the next run.
        val temporary = path.parent?.let { it / "${path.name}.tmp" } ?: return@withContext
        try {
            path.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(temporary) { writeUtf8(json.encodeToString(Document.serializer(), document)) }
            fileSystem.atomicMove(temporary, path)
        } catch (e: okio.IOException) {
            try {
                fileSystem.delete(temporary, mustExist = false)
            } catch (ignored: okio.IOException) {
                // Nothing more to do; the cache is best-effort.
            }
        }
    }
}
