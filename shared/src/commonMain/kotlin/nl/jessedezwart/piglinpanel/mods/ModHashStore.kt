package nl.jessedezwart.piglinpanel.mods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Persists [ModHasher]'s results between runs.
 *
 * Hashing a mod folder means reading every jar in full, which over SFTP is the single most expensive
 * thing the app does — and the answer never changes for a file that has not been touched. The cache
 * keys already carry the file's size and modification time, so a stale entry simply misses; the file
 * needs no invalidation of its own and can be reused for as long as the mods sit untouched on disk.
 *
 * Failures are swallowed: this is a cache, and losing it costs time, not correctness.
 */
class ModHashStore(
    private val fileSystem: FileSystem,
    private val path: Path,
) {
    private companion object {
        /**
         * Bumped whenever the key format, either hash algorithm, or what is read out of a jar
         * changes, to discard stale files. Version 5 reads the side a jar declares it runs on,
         * which entries written before it lack — leaving them cached would leave every mod
         * unlabelled until its file changed.
         */
        const val FORMAT_VERSION = 5
    }

    @Serializable
    private data class Record(
        val sha1: String,
        val fingerprint: Long,
        val metadata: ModJarMetadata? = null,
    )

    @Serializable
    private data class Document(val version: Int, val entries: Map<String, Record>)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Map<String, ModFileHashes> = withContext(Dispatchers.Default) {
        val text = try {
            if (!fileSystem.exists(path)) return@withContext emptyMap()
            fileSystem.read(path) { readUtf8() }
        } catch (e: okio.IOException) {
            return@withContext emptyMap()
        }
        val document = try {
            json.decodeFromString<Document>(text)
        } catch (e: Exception) {
            return@withContext emptyMap()
        }
        if (document.version != FORMAT_VERSION) return@withContext emptyMap()
        document.entries.mapValues { (_, record) ->
            ModFileHashes(record.sha1, record.fingerprint, record.metadata)
        }
    }

    suspend fun save(hashes: Map<String, ModFileHashes>) = withContext(Dispatchers.Default) {
        val document = Document(
            version = FORMAT_VERSION,
            entries = hashes.mapValues { (_, value) -> Record(value.sha1, value.fingerprint, value.metadata) },
        )
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
