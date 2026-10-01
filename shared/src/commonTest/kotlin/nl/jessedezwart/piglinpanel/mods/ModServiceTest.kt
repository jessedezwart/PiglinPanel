package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.curseforge.CurseForgeFingerprint
import nl.jessedezwart.piglinpanel.curseforge.ModFingerprintUpdateSource
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource
import nl.jessedezwart.piglinpanel.search.ModSource
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

// SHA-1 of bytes [1, 2, 3]
private const val HASH_123 = "7037807198c22a7d2b0807371d763779a84fdfcf"

// CurseForge fingerprint of the same bytes
private val FINGERPRINT_123 = CurseForgeFingerprint.of(byteArrayOf(1, 2, 3))

private class FakeModrinth(
    private val updates: Map<String, ModUpdate> = emptyMap(),
) : ModUpdateSource {
    var lookupCalls = 0
        private set

    override suspend fun findSuggestedUpdates(sha1s: List<String>, loader: String?, gameVersion: String?): Map<String, ModUpdate> {
        lookupCalls++
        return updates.filterKeys { it in sha1s }
    }

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray = byteArrayOf(9, 9)
}

private class FakeCurseForge(
    private val updates: Map<Long, ModUpdate> = emptyMap(),
    override val available: Boolean = true,
) : ModFingerprintUpdateSource {
    var lookupCalls = 0
        private set
    var requestedFingerprints: List<Long> = emptyList()
        private set

    override suspend fun findSuggestedUpdates(fingerprints: List<Long>, loader: String?, gameVersion: String?): Map<Long, ModUpdate> {
        lookupCalls++
        requestedFingerprints = fingerprints
        return updates.filterKeys { it in fingerprints }
    }

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray = byteArrayOf(7, 7, 7)
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

private fun modFile(
    path: String,
    archived: Boolean,
    folderId: String = "mods",
    update: ModUpdate? = null,
) = ModFile(
    folderId = folderId,
    folderName = folderId,
    folderPath = path.substringBeforeLast('/', ""),
    relativePath = path,
    fileName = path.substringAfterLast('/'),
    size = 1,
    archived = archived,
    sha1 = null,
    update = update,
)

class ModServiceTest {
    private val fs = FakeServerFileSystem()

    private fun service(
        modrinth: ModUpdateSource = FakeModrinth(),
        curseForge: ModFingerprintUpdateSource? = null,
    ) = ModService({ fs }, modrinth, ModHasher(), curseForge)

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
    fun fallsBackToCurseForgeForModsModrinthDoesNotKnow() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val curseForgeUpdate = update("cf-3.0").copy(source = ModSource.CURSEFORGE)
        val curseForge = FakeCurseForge(mapOf(FINGERPRINT_123 to curseForgeUpdate))

        val mods = service(FakeModrinth(), curseForge).scan(ServerProfile(), checkUpdates = true)

        assertEquals("cf-3.0", mods.single().update?.latestVersionName)
        assertEquals(ModSource.CURSEFORGE, mods.single().update?.source)
    }

    @Test
    fun modrinthMatchWinsButCurseForgeIsStillRecordedAsASource() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val curseForge = FakeCurseForge(mapOf(FINGERPRINT_123 to update("cf-3.0")))

        val mods = service(FakeModrinth(mapOf(HASH_123 to update())), curseForge)
            .scan(ServerProfile(), checkUpdates = true)

        assertEquals("2.0.0", mods.single().update?.latestVersionName)
        assertEquals(setOf(ModSource.MODRINTH, ModSource.CURSEFORGE), mods.single().sources)
    }

    @Test
    fun skipsCurseForgeLookupWhenNoApiKeyConfigured() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val curseForge = FakeCurseForge(available = false)

        service(FakeModrinth(), curseForge).scan(ServerProfile(), checkUpdates = true)

        assertEquals(0, curseForge.lookupCalls)
    }

    @Test
    fun cachesCurseForgeUpdatesAndSkipsSecondLookup() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val curseForge = FakeCurseForge(mapOf(FINGERPRINT_123 to update().copy(source = ModSource.CURSEFORGE)))
        val service = service(FakeModrinth(), curseForge)

        service.scan(ServerProfile(), checkUpdates = true)
        service.scan(ServerProfile(), checkUpdates = true)

        assertEquals(1, curseForge.lookupCalls)
    }

    @Test
    fun applyUpdateDownloadsCurseForgeUpdatesFromCurseForge() = runTest {
        fs.put("mods/old.jar", byteArrayOf(1, 2, 3))
        val curseForgeUpdate = update().copy(source = ModSource.CURSEFORGE)
        val mod = ModFile("mods", "mods", "mods", "mods/old.jar", "old.jar", 3, false, HASH_123, curseForgeUpdate)

        service(FakeModrinth(), FakeCurseForge()).applyUpdate(ServerProfile(), mod)

        assertTrue("mods/old.jar.disabled" in fs.files)
        assertEquals(3, fs.files["mods/mod.jar"]?.size)
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
    fun listingAfterRestartKeepsWhatTheLastScanLearnedWithoutReadingOrLookingUp() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val profile = ServerProfile()
        val disk = FakeFileSystem()
        fun restarted(modrinth: ModUpdateSource) = ModService(
            { fs },
            modrinth,
            ModHasher(ModHashStore(disk, "/config/mod-hashes.json".toPath())),
            modListStore = ModListStore(disk, "/config/mod-list.json".toPath()),
        )
        restarted(FakeModrinth(mapOf(HASH_123 to update()))).scan(profile, checkUpdates = true)

        val offline = FakeModrinth()
        val service = restarted(offline)
        assertEquals("2.0.0", service.lastKnown(profile).single().update?.latestVersionName)
        val mods = service.scan(profile, checkUpdates = false)

        assertEquals("2.0.0", mods.single().update?.latestVersionName)
        assertEquals(setOf(ModSource.MODRINTH), mods.single().sources)
        assertEquals(1, fs.readCount)
        assertEquals(0, offline.lookupCalls)
    }

    @Test
    fun listingDropsRemovedJarsAndForgetsWhatWasKnownAboutChangedOnes() = runTest {
        fs.put("mods/kept.jar", byteArrayOf(1, 2, 3))
        fs.put("mods/removed.jar", byteArrayOf(4, 5, 6))
        val profile = ServerProfile()
        val service = ModService(
            { fs },
            FakeModrinth(mapOf(HASH_123 to update())),
            ModHasher(),
            modListStore = ModListStore(FakeFileSystem(), "/config/mod-list.json".toPath()),
        )
        service.scan(profile, checkUpdates = true)

        fs.delete("mods/removed.jar")
        fs.put("mods/kept.jar", byteArrayOf(7, 8, 9, 10), modified = Instant.fromEpochMilliseconds(9_000))
        val mods = service.scan(profile, checkUpdates = false)

        assertEquals(listOf("kept.jar"), mods.map { it.fileName })
        assertEquals(null, mods.single().update)
        assertEquals(listOf("kept.jar"), service.lastKnown(profile).map { it.fileName })
    }

    @Test
    fun lastKnownListIsNotReusedForAnotherMinecraftVersion() = runTest {
        fs.put("mods/server.jar", byteArrayOf(1, 2, 3))
        val service = ModService(
            { fs },
            FakeModrinth(mapOf(HASH_123 to update())),
            ModHasher(),
            modListStore = ModListStore(FakeFileSystem(), "/config/mod-list.json".toPath()),
        )
        val profile = ServerProfile(mcVersion = "1.21.1")
        service.scan(profile, checkUpdates = true)

        assertTrue(service.lastKnown(profile.copy(mcVersion = "1.21.4")).isEmpty())
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
        val project = update().copy(projectId = "create")
        val active = modFile("mods/create-5.1.jar", archived = false, update = project)
        val archived = modFile("mods/create-5.0.jar.disabled", archived = true, update = project)

        service().activate(ServerProfile(), listOf(active, archived), archived)

        assertTrue("mods/create-5.0.jar" in fs.files)
        assertTrue("mods/create-5.1.jar.disabled" in fs.files)
        assertFalse("mods/create-5.0.jar.disabled" in fs.files)
        assertFalse("mods/create-5.1.jar" in fs.files)
    }

    @Test
    fun activateKeepsBothVersionsWhenTheyShareAFileName() = runTest {
        fs.put("mods/mod.jar", byteArrayOf(1))
        fs.put("mods/mod.jar.disabled", byteArrayOf(2))
        val active = modFile("mods/mod.jar", archived = false)
        val archived = modFile("mods/mod.jar.disabled", archived = true)

        service().activate(ServerProfile(), listOf(active, archived), archived)

        assertEquals(listOf<Byte>(2), fs.files["mods/mod.jar"]?.toList())
        assertEquals(listOf<Byte>(1), fs.files["mods/mod~1.jar.disabled"]?.toList())
        assertEquals(2, fs.files.size)
    }

    @Test
    fun activateDisablesTheOtherVersionEvenWhenTheScanKnowsNothingAboutIt() = runTest {
        // A quick scan resolves neither a project nor a mod id, so the caller cannot say which file
        // is the active one; the file names still identify the pair.
        fs.put("mods/mod.jar", byteArrayOf(1))
        fs.put("mods/mod.jar.disabled", byteArrayOf(2))
        val active = modFile("mods/mod.jar", archived = false)
        val archived = modFile("mods/mod.jar.disabled", archived = true)

        service().activate(ServerProfile(), listOf(active, archived), archived)

        assertFalse(fs.files.keys.count { !it.endsWith(".disabled") } > 1)
    }

    @Test
    fun activateLeavesTheSameModInAnotherFolderAlone() = runTest {
        fs.put("mods/mod.jar", byteArrayOf(1))
        fs.put("client/mod.jar", byteArrayOf(1))
        fs.put("mods/mod-old.jar.disabled", byteArrayOf(2))
        val project = update().copy(projectId = "p")
        val active = modFile("mods/mod.jar", archived = false, folderId = "mods", update = project)
        val client = modFile("client/mod.jar", archived = false, folderId = "client", update = project)
        val archived = modFile("mods/mod-old.jar.disabled", archived = true, folderId = "mods", update = project)

        service().activate(ServerProfile(), listOf(active, client, archived), archived)

        assertTrue("client/mod.jar" in fs.files)
        assertTrue("mods/mod.jar.disabled" in fs.files)
        assertTrue("mods/mod-old.jar" in fs.files)
    }

    @Test
    fun applyUpdateArchivesOldFileAndWritesNew() = runTest {
        fs.put("mods/old.jar", byteArrayOf(1, 2, 3))
        val mod = ModFile("mods", "mods", "mods", "mods/old.jar", "old.jar", 3, false, HASH_123, update())

        service(FakeModrinth()).applyUpdate(ServerProfile(), mod)

        assertTrue("mods/old.jar.disabled" in fs.files)
        assertEquals(2, fs.files["mods/mod.jar"]?.size)
    }

    @Test
    fun revertPutsBackTheVersionAnUpdateReplaced() = runTest {
        fs.put("mods/old.jar", byteArrayOf(1, 2, 3))
        val mod = ModFile("mods", "mods", "mods", "mods/old.jar", "old.jar", 3, false, HASH_123, update())
        val service = service(FakeModrinth())

        val applied = service.applyUpdate(ServerProfile(), mod)
        service.revertUpdate(ServerProfile(), applied)

        assertTrue("mods/mod.jar" !in fs.files)
        assertTrue("mods/old.jar.disabled" !in fs.files)
        assertEquals(listOf<Byte>(1, 2, 3), fs.files["mods/old.jar"]?.toList())
    }

    @Test
    fun revertReusesTheNameTheUpdateTookWhenItKeptTheFileName() = runTest {
        // The update ships a jar called the same thing, so the old one was archived under
        // `mod.jar.disabled` and the new one took `mod.jar`. Reverting has to free that name first.
        fs.put("mods/mod.jar", byteArrayOf(1, 2, 3))
        val mod = ModFile("mods", "mods", "mods", "mods/mod.jar", "mod.jar", 3, false, HASH_123, update())
        val service = service(FakeModrinth())

        val applied = service.applyUpdate(ServerProfile(), mod)
        assertEquals("mods/mod.jar.disabled", applied.archivedPath)
        service.revertUpdate(ServerProfile(), applied)

        assertEquals(listOf<Byte>(1, 2, 3), fs.files["mods/mod.jar"]?.toList())
        assertTrue("mods/mod.jar.disabled" !in fs.files)
    }

    @Test
    fun theJarsOwnSideWinsOverTheRepositorys() {
        // Modrinth calls Auth Me client-supported and server-supported; the jar says client-only,
        // and the loader is the one that enforces it.
        val both = update().copy(clientSide = "required", serverSide = "required")
        val mod = modFile("mods/authme.jar", archived = false, update = both)
            .copy(metadata = ModJarMetadata(modId = "authme", side = ModSide.CLIENT))

        assertEquals(ModSide.CLIENT, mod.side)
    }

    @Test
    fun theRepositoryAnswersWhenTheJarSaysNothing() {
        val clientOnly = update().copy(clientSide = "required", serverSide = "unsupported")
        val mod = modFile("mods/betterf3.jar", archived = false, update = clientOnly)
            .copy(metadata = ModJarMetadata(modId = "betterf3"))

        assertEquals(ModSide.CLIENT, mod.side)
    }

    @Test
    fun flagsLibrariesThatNoEnabledModUses() {
        val library = update().copy(library = true)
        val used = modFile("mods/architectury.jar", archived = false, update = library.copy(projectId = "arch"))
            .copy(metadata = ModJarMetadata(modId = "architectury"))
        val optionallyUsed = modFile("mods/jei.jar", archived = false, update = library.copy(projectId = "jei"))
            .copy(metadata = ModJarMetadata(modId = "jei"))
        val orphan = modFile("mods/geckolib.jar", archived = false, update = library.copy(projectId = "gecko"))
            .copy(metadata = ModJarMetadata(modId = "geckolib"))
        // Only a disabled mod needs it, which does not count: a disabled jar loads nothing.
        val disabledUser = modFile("mods/mobs.jar.disabled", archived = true)
            .copy(metadata = ModJarMetadata(modId = "mobs", requires = listOf(ModDependencyRef("geckolib"))))
        val unreadable = modFile("mods/mystery-lib.jar", archived = false, update = library.copy(projectId = "mystery"))
        val user = modFile("mods/create.jar", archived = false)
            .copy(metadata = ModJarMetadata(modId = "create", requires = listOf(ModDependencyRef("architectury")), optional = listOf(ModDependencyRef("jei"))))

        val unused = service().group(listOf(used, optionallyUsed, orphan, disabledUser, unreadable, user))
            .filter { it.unusedLibrary }

        assertEquals(listOf("geckolib.jar"), unused.map { it.active?.fileName })
    }

    @Test
    fun aJarsLibraryBadgeCountsWithoutARepositoryMatch() {
        val mod = modFile("mods/cloth.jar", archived = false)
            .copy(metadata = ModJarMetadata(modId = "cloth-config", library = true))

        assertTrue(service().group(listOf(mod)).single().unusedLibrary)
    }

    @Test
    fun aModNeitherTheJarNorTheRepositoryPlacesStaysUnknown() {
        val mod = modFile("mods/mystery.jar", archived = false)

        assertEquals(ModSide.UNKNOWN, mod.side)
    }

    @Test
    fun readsSeveralJarsAtOnceUpToWhatTheTransportAllows() = runTest(timeout = 10.seconds) {
        repeat(8) { fs.put("mods/mod$it.jar", byteArrayOf(it.toByte())) }
        fs.readConcurrency = 4
        // Every read parks until four of them are open together, so a scan that read one file at
        // a time could never finish this: the overlap is proven by the test completing at all.
        val fourAreOpen = CompletableDeferred<Unit>()
        var waiting = 0
        val gate = Mutex()
        fs.onRead = {
            val open = gate.withLock { ++waiting }
            if (open % 4 == 0) fourAreOpen.complete(Unit)
            fourAreOpen.await()
        }

        service().scan(ServerProfile(), checkUpdates = true)

        assertEquals(4, fs.peakConcurrentReads)
    }

    @Test
    fun countsHashingProgressOneStepAtATimeThoughReadsFinishOutOfOrder() = runTest {
        repeat(5) { fs.put("mods/mod$it.jar", byteArrayOf(it.toByte())) }
        fs.readConcurrency = 3
        val steps = mutableListOf<Int>()

        service().scan(ServerProfile(), checkUpdates = true) { p ->
            if (p.phase == "hashing") steps += p.done
        }

        assertEquals((1..5).toList(), steps)
        assertEquals(5, steps.size)
    }
}
