package nl.jessedezwart.piglinpanel.mods

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.curseforge.CurseForgeFingerprint
import nl.jessedezwart.piglinpanel.fs.ServerFileSystem
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.ByteString.Companion.toByteString

/**
 * What one read of a mod jar yields: the identifiers it is looked up by — SHA-1 on Modrinth,
 * murmur2 fingerprint on CurseForge — and what the jar declares about itself. [metadata] is null
 * for jars whose manifest could not be read; see [ModJarMetadataReader].
 */
data class ModFileHashes(
    val sha1: String,
    val fingerprint: Long,
    val metadata: ModJarMetadata? = null,
)

/**
 * Computes lookup hashes of mod files, cached by path + size + modification time so unchanged files
 * are never re-read. Both hashes come from a single read since remote reads dominate the cost.
 *
 * When a [store] is supplied the cache also outlives the process, so a restart does not mean
 * re-reading every jar on the server; see [ModHashStore].
 */
class ModHasher(private val store: ModHashStore? = null) {
    private companion object {
        const val MAX_ENTRIES = 1000
        const val DISABLED_SUFFIX = ".disabled"
    }

    private val cache = SuspendCache<String, ModFileHashes>(maxEntries = MAX_ENTRIES)

    /** Guards the one-shot restore and the count of hashes still to be written back. */
    private val stateLock = Mutex()
    private var restored = false
    private var unsaved = 0

    suspend fun resolveHashes(
        profile: ServerProfile,
        fileSystem: ServerFileSystem,
        entry: FileEntry,
    ): ModFileHashes {
        restore()
        return cache.getOrPut(cacheKey(profile, entry)) {
            val bytes = fileSystem.readBytes(entry.path)
            val hashes = ModFileHashes(
                sha1 = bytes.toByteString().sha1().hex(),
                fingerprint = CurseForgeFingerprint.of(bytes),
                metadata = ModJarMetadataReader.read(bytes, profile.modLoader),
            )
            stateLock.withLock { unsaved++ }
            hashes
        }
    }

    /**
     * What is already known about [entry] without reading it. Lets a quick scan reuse whatever a
     * previous full scan left behind — the file's mod metadata in particular — while keeping its
     * promise not to touch the server.
     */
    suspend fun cachedHashes(profile: ServerProfile, entry: FileEntry): ModFileHashes? {
        restore()
        return cache.getIfPresent(cacheKey(profile, entry))
    }

    /**
     * Enabling/disabling only renames the file, so the `.disabled` suffix is left out of the key:
     * the bytes are the same either way, and a toggle should not cost a re-read.
     *
     * The loader is part of the key because the metadata read from a merged jar depends on it.
     */
    private fun cacheKey(profile: ServerProfile, entry: FileEntry): String {
        val path = entry.path.removeSuffix(DISABLED_SUFFIX)
        return "${profile.id}:${profile.modLoader.orEmpty()}:$path:${entry.size}:${entry.modifiedAt?.toEpochMilliseconds() ?: 0}"
    }

    suspend fun resolveSha1(
        profile: ServerProfile,
        fileSystem: ServerFileSystem,
        entry: FileEntry,
    ): String = resolveHashes(profile, fileSystem, entry).sha1

    /** Writes newly computed hashes back to the [store]. A no-op when everything came from cache. */
    suspend fun flush() {
        val store = store ?: return
        val pending = stateLock.withLock {
            val count = unsaved
            unsaved = 0
            count
        }
        if (pending > 0) store.save(cache.snapshot())
    }

    /** Loads the persisted hashes on first use. Callers past the first wait for it to finish. */
    private suspend fun restore() {
        val store = store ?: return
        stateLock.withLock {
            if (restored) return
            restored = true
            cache.putAll(store.load())
        }
    }
}
