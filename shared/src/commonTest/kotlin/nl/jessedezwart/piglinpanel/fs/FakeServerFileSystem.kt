package nl.jessedezwart.piglinpanel.fs

import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.model.FileEntry
import okio.IOException

/** In-memory [ServerFileSystem] for tests: a flat map of relative path → bytes. */
class FakeServerFileSystem : ServerFileSystem {
    val files = LinkedHashMap<String, ByteArray>()
    val modifiedAt = HashMap<String, Instant>()
    var readCount = 0
        private set

    fun put(path: String, bytes: ByteArray, modified: Instant = Instant.fromEpochMilliseconds(1_000)) {
        files[path] = bytes
        modifiedAt[path] = modified
    }

    override suspend fun list(relativePath: String): List<FileEntry> {
        val prefix = if (relativePath.isBlank()) "" else "$relativePath/"
        val directChildren = LinkedHashMap<String, Boolean>() // name → isDirectory
        for (path in files.keys) {
            if (!path.startsWith(prefix) || path == relativePath) continue
            val remainder = path.removePrefix(prefix)
            val name = remainder.substringBefore('/')
            directChildren[name] = remainder.contains('/') || (directChildren[name] ?: false)
        }
        return directChildren.map { (name, directory) ->
            val childPath = prefix + name
            FileEntry(
                path = childPath,
                name = name,
                directory = directory,
                size = if (directory) 0 else files[childPath]?.size?.toLong() ?: 0,
                modifiedAt = modifiedAt[childPath],
            )
        }.sortedWith(compareBy({ !it.directory }, { it.name }))
    }

    override suspend fun readBytes(relativePath: String): ByteArray {
        readCount++
        return files[relativePath] ?: throw IOException("No such file: $relativePath")
    }

    override suspend fun writeBytes(relativePath: String, bytes: ByteArray) {
        put(relativePath, bytes)
    }

    override suspend fun delete(relativePath: String) {
        files.remove(relativePath)
        modifiedAt.remove(relativePath)
    }

    override suspend fun move(sourceRelativePath: String, targetRelativePath: String) {
        val bytes = files.remove(sourceRelativePath)
            ?: throw IOException("No such file: $sourceRelativePath")
        files[targetRelativePath] = bytes
        modifiedAt[targetRelativePath] = modifiedAt.remove(sourceRelativePath)
            ?: Instant.fromEpochMilliseconds(1_000)
    }
}
