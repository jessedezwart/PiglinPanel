package nl.jessedezwart.piglinpanel.fs

import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile

/**
 * File operations against a server's root directory. All paths are relative to the profile's root;
 * implementations must reject paths that escape it.
 */
interface ServerFileSystem {
    /** Lists the direct children of [relativePath]; empty if the directory does not exist. */
    suspend fun list(relativePath: String): List<FileEntry>

    suspend fun readBytes(relativePath: String): ByteArray

    /** Writes [bytes], creating parent directories as needed. */
    suspend fun writeBytes(relativePath: String, bytes: ByteArray)

    /** Deletes the file if it exists. */
    suspend fun delete(relativePath: String)

    /** Moves/renames, replacing an existing target and creating parent directories as needed. */
    suspend fun move(sourceRelativePath: String, targetRelativePath: String)
}

/** Resolves the [ServerFileSystem] implementation for a profile (local vs SSH). */
fun interface ServerFileSystemProvider {
    fun fileSystem(profile: ServerProfile): ServerFileSystem
}
