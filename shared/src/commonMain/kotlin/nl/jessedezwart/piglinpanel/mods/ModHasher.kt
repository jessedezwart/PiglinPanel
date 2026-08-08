package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.curseforge.CurseForgeFingerprint
import nl.jessedezwart.piglinpanel.fs.ServerFileSystem
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.ByteString.Companion.toByteString

/** The identifiers a mod file is looked up by: SHA-1 on Modrinth, murmur2 fingerprint on CurseForge. */
data class ModFileHashes(val sha1: String, val fingerprint: Long)

/**
 * Computes lookup hashes of mod files, cached by path + size + modification time so unchanged files
 * are never re-read. Both hashes come from a single read since remote reads dominate the cost.
 */
class ModHasher {
    private val cache = SuspendCache<String, ModFileHashes>(maxEntries = 500)

    suspend fun resolveHashes(
        profile: ServerProfile,
        fileSystem: ServerFileSystem,
        entry: FileEntry,
    ): ModFileHashes {
        val key = "${profile.id}:${entry.path}:${entry.size}:${entry.modifiedAt?.toEpochMilliseconds() ?: 0}"
        return cache.getOrPut(key) {
            val bytes = fileSystem.readBytes(entry.path)
            ModFileHashes(
                sha1 = bytes.toByteString().sha1().hex(),
                fingerprint = CurseForgeFingerprint.of(bytes),
            )
        }
    }

    suspend fun resolveSha1(
        profile: ServerProfile,
        fileSystem: ServerFileSystem,
        entry: FileEntry,
    ): String = resolveHashes(profile, fileSystem, entry).sha1
}
