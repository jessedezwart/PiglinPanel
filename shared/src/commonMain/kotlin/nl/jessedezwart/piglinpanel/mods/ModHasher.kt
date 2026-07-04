package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.fs.ServerFileSystem
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.ByteString.Companion.toByteString

/**
 * Computes SHA-1 hashes of mod files, cached by path + size + modification time so unchanged files
 * are never re-read.
 */
class ModHasher {
    private val cache = SuspendCache<String, String>(maxEntries = 500)

    suspend fun resolveSha1(
        profile: ServerProfile,
        fileSystem: ServerFileSystem,
        entry: FileEntry,
    ): String {
        val key = "${profile.id}:${entry.path}:${entry.size}:${entry.modifiedAt?.toEpochMilliseconds() ?: 0}"
        return cache.getOrPut(key) {
            fileSystem.readBytes(entry.path).toByteString().sha1().hex()
        }
    }
}
