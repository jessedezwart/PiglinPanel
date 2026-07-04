package nl.jessedezwart.piglinpanel.fs

import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

class LocalServerFileSystem(
    profile: ServerProfile,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ServerFileSystem {
    private val root: Path = profile.rootPath.toPath(normalize = true)

    override suspend fun list(relativePath: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val path = resolve(relativePath)
        if (!fileSystem.exists(path)) {
            return@withContext emptyList()
        }
        fileSystem.list(path)
            .map { child -> entry(child) }
            .sortedWith(compareBy({ !it.directory }, { it.name }))
    }

    override suspend fun readBytes(relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        fileSystem.read(resolve(relativePath)) { readByteArray() }
    }

    override suspend fun writeBytes(relativePath: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val path = resolve(relativePath)
            path.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(path) { write(bytes) }
        }

    override suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        fileSystem.delete(resolve(relativePath), mustExist = false)
    }

    override suspend fun move(sourceRelativePath: String, targetRelativePath: String) =
        withContext(Dispatchers.IO) {
            val target = resolve(targetRelativePath)
            target.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.delete(target, mustExist = false)
            fileSystem.atomicMove(resolve(sourceRelativePath), target)
        }

    private fun entry(path: Path): FileEntry {
        val metadata = fileSystem.metadataOrNull(path)
        val directory = metadata?.isDirectory ?: false
        val relative = path.toString()
            .removePrefix(root.toString())
            .trimStart('\\', '/')
            .replace('\\', '/')
        return FileEntry(
            path = relative,
            name = path.name,
            directory = directory,
            size = if (directory) 0 else metadata?.size ?: 0,
            modifiedAt = metadata?.lastModifiedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
        )
    }

    private fun resolve(relativePath: String): Path {
        val resolved = (root / relativePath.ifBlank { "." }).normalized()
        var ancestor: Path? = resolved
        while (ancestor != null) {
            if (ancestor == root) {
                return resolved
            }
            ancestor = ancestor.parent
        }
        throw IOException("Path escapes server root: $relativePath")
    }
}
