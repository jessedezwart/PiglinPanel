package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ModHasherTest {
    private val diskFs = FakeFileSystem()
    private val cachePath = "/config/mod-hashes.json".toPath()
    private val serverFs = FakeServerFileSystem()
    private val profile = ServerProfile()

    private fun store() = ModHashStore(diskFs, cachePath)

    private suspend fun entryFor(path: String) = serverFs.list("mods").single { it.path == path }

    @Test
    fun reusesPersistedHashesAfterRestartInsteadOfRereadingTheFile() = runTest {
        serverFs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val entry = entryFor("mods/server.jar")

        val first = ModHasher(store())
        val computed = first.resolveHashes(profile, serverFs, entry)
        first.flush()
        assertEquals(1, serverFs.readCount)

        // A fresh hasher stands in for a restarted app: same store, no in-memory state.
        val restarted = ModHasher(store())
        assertEquals(computed, restarted.resolveHashes(profile, serverFs, entry))
        assertEquals(1, serverFs.readCount)
    }

    @Test
    fun rehashesWhenTheFileChangedOnDisk() = runTest {
        serverFs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val first = ModHasher(store())
        first.resolveHashes(profile, serverFs, entryFor("mods/server.jar"))
        first.flush()

        serverFs.put("mods/server.jar", byteArrayOf(4, 5, 6, 7), modified = Instant.fromEpochMilliseconds(9_000))

        val restarted = ModHasher(store())
        restarted.resolveHashes(profile, serverFs, entryFor("mods/server.jar"))

        assertEquals(2, serverFs.readCount)
    }

    @Test
    fun startsEmptyWhenTheCacheFileIsCorrupt() = runTest {
        serverFs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        diskFs.createDirectories(cachePath.parent!!)
        diskFs.write(cachePath) { writeUtf8("{ not json") }

        val hasher = ModHasher(store())
        hasher.resolveHashes(profile, serverFs, entryFor("mods/server.jar"))
        hasher.flush()

        assertEquals(1, serverFs.readCount)
        // The unreadable file is replaced, so the next run benefits again.
        val restarted = ModHasher(store())
        restarted.resolveHashes(profile, serverFs, entryFor("mods/server.jar"))
        assertEquals(1, serverFs.readCount)
    }

    @Test
    fun writesNothingWhenEveryHashCameFromCache() = runTest {
        serverFs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val hasher = ModHasher(store())
        hasher.resolveHashes(profile, serverFs, entryFor("mods/server.jar"))
        hasher.flush()
        val written = diskFs.metadata(cachePath).lastModifiedAtMillis

        hasher.resolveHashes(profile, serverFs, entryFor("mods/server.jar"))
        hasher.flush()

        assertEquals(written, diskFs.metadata(cachePath).lastModifiedAtMillis)
    }
}
