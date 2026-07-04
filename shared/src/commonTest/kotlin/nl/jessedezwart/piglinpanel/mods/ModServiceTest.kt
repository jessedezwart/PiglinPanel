package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource

// SHA-1 of bytes [1, 2, 3]
private const val HASH_123 = "7037807198c22a7d2b0807371d763779a84fdfcf"

private class FakeModrinth(
    private val updates: Map<String, ModUpdate> = emptyMap(),
) : ModUpdateSource {
    var lookupCalls = 0
        private set

    override suspend fun findSuggestedUpdates(sha1s: List<String>): Map<String, ModUpdate> {
        lookupCalls++
        return updates.filterKeys { it in sha1s }
    }

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray = byteArrayOf(9, 9)
}

private fun update(latestName: String = "2.0.0") = ModUpdate(
    projectId = "project",
    projectName = "Project",
    currentVersionId = "old",
    latestVersionId = "new",
    latestVersionName = latestName,
    lastUpdatedAt = Instant.parse("2026-06-10T12:00:00Z"),
    downloadUrl = "https://example.com/mod.jar",
    fileName = "mod.jar",
    iconUrl = null,
    clientSide = null,
    serverSide = null,
    downloads = 0,
)

class ModServiceTest {
    private val fs = FakeServerFileSystem()

    private fun service(modrinth: ModUpdateSource = FakeModrinth()) =
        ModService({ fs }, modrinth, ModHasher())

    @Test
    fun scansMultipleModFoldersAndSeparatesArchivedFiles() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        fs.put("client-mods/client.jar.disabled", byteArrayOf(4, 5, 6))
        fs.put("mods/readme.txt", byteArrayOf(0))
        val profile = ServerProfile(
            modFolders = listOf(
                ModFolder("server", "Server", "mods"),
                ModFolder("client", "Client", "client-mods"),
            ),
        )

        val mods = service().scan(profile, checkUpdates = false)

        assertEquals(2, mods.size)
        assertEquals(setOf("mods", "client-mods"), mods.map { it.folderPath }.toSet())
        assertEquals(listOf("client.jar.disabled"), mods.filter { it.archived }.map { it.fileName })
    }

    @Test
    fun attachesSuggestedModrinthUpdatesWhenAvailable() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val profile = ServerProfile()

        val mods = service(FakeModrinth(mapOf(HASH_123 to update()))).scan(profile, checkUpdates = true)

        assertEquals("2.0.0", mods.single().update?.latestVersionName)
    }

    @Test
    fun reusesCachedHashWhenFileMetadataStillMatches() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val profile = ServerProfile()
        val service = service()

        service.scan(profile, checkUpdates = true)
        service.scan(profile, checkUpdates = true)

        assertEquals(1, fs.readCount)
    }

    @Test
    fun cachesSuggestedUpdatesAndSkipsSecondLookup() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val profile = ServerProfile()
        val modrinth = FakeModrinth(mapOf(HASH_123 to update()))
        val service = service(modrinth)

        service.scan(profile, checkUpdates = true)
        service.scan(profile, checkUpdates = true)

        assertEquals(1, modrinth.lookupCalls)
    }

    @Test
    fun forceRefreshBypassesUpdateCache() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val profile = ServerProfile()
        val modrinth = FakeModrinth(mapOf(HASH_123 to update()))
        val service = service(modrinth)

        service.scan(profile, checkUpdates = true)
        service.scan(profile, checkUpdates = true, forceRefresh = true)

        assertEquals(2, modrinth.lookupCalls)
    }

    @Test
    fun reportsScanProgress() = runTest {
        fs.put("mods/a.jar", byteArrayOf(1, 2, 3))
        fs.put("mods/b.jar", byteArrayOf(1, 2, 3))
        val progress = mutableListOf<ScanProgress>()

        service().scan(ServerProfile(), checkUpdates = true) { progress += it }

        assertEquals(listOf("hashing", "hashing", "lookup"), progress.map { it.phase })
        assertEquals(2, progress[1].done)
    }

    @Test
    fun archiveNameAddsDisabledSuffix() {
        assertEquals("mods/example.jar.disabled", ModService.nextArchiveName("mods/example.jar"))
        assertEquals("mods/example.jar.disabled", ModService.nextArchiveName("mods/example.jar.disabled"))
    }

    @Test
    fun enableNameStripsDisabledSuffix() {
        assertEquals("mods/example.jar", ModService.enableName("mods/example.jar.disabled"))
        assertEquals("mods/example.jar", ModService.enableName("mods/example.jar"))
    }

    @Test
    fun groupsVersionsByProjectWithActiveSurfaced() {
        val projectUpdate = update("5.1").copy(projectId = "create", projectName = "Create")
        val active = ModFile("mods", "mods", "mods", "mods/create-5.1.jar", "create-5.1.jar", 20, false, "h1", projectUpdate)
        val archived = ModFile("mods", "mods", "mods", "mods/create-5.0.jar.disabled", "create-5.0.jar.disabled", 19, true, "h2", projectUpdate)

        val groups = service().group(listOf(active, archived))

        val group = groups.single()
        assertEquals("create-5.1.jar", group.active?.fileName)
        assertEquals(listOf("create-5.0.jar.disabled"), group.archived.map { it.fileName })
        assertEquals("Create", group.displayName)
    }

    @Test
    fun activateEnablesArchivedAndDisablesCurrent() = runTest {
        fs.put("mods/create-5.1.jar", byteArrayOf(1))
        fs.put("mods/create-5.0.jar.disabled", byteArrayOf(2))

        service().activate(ServerProfile(), "mods/create-5.0.jar.disabled", "mods/create-5.1.jar")

        assertTrue("mods/create-5.0.jar" in fs.files)
        assertTrue("mods/create-5.1.jar.disabled" in fs.files)
        assertFalse("mods/create-5.0.jar.disabled" in fs.files)
        assertFalse("mods/create-5.1.jar" in fs.files)
    }

    @Test
    fun applyUpdateArchivesOldFileAndWritesNew() = runTest {
        fs.put("mods/old.jar", byteArrayOf(1, 2, 3))
        val mod = ModFile("mods", "mods", "mods", "mods/old.jar", "old.jar", 3, false, HASH_123, update())

        service(FakeModrinth()).applyUpdate(ServerProfile(), mod)

        assertTrue("mods/old.jar.disabled" in fs.files)
        assertEquals(2, fs.files["mods/mod.jar"]?.size)
    }
}
