package nl.jessedezwart.piglinpanel.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource
import nl.jessedezwart.piglinpanel.mods.ModHasher
import nl.jessedezwart.piglinpanel.mods.ModService

// SHA-1 of bytes [1, 2, 3]
private const val HASH_123 = "7037807198c22a7d2b0807371d763779a84fdfcf"

private class FakeModUpdateSource(private val updates: Map<String, ModUpdate> = emptyMap()) : ModUpdateSource {
    override suspend fun findSuggestedUpdates(sha1s: List<String>, loader: String?, gameVersion: String?): Map<String, ModUpdate> =
        updates.filterKeys { it in sha1s }

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray = byteArrayOf()
}

private fun hit(source: ModSource, id: String, title: String = id) =
    ModSearchHit(source, id, title, "", null, null, 0)

private fun version(
    source: ModSource,
    projectId: String,
    versionId: String,
    fileName: String,
    deps: List<ModDependency> = emptyList(),
) = ModVersionInfo(source, projectId, versionId, versionId, fileName, "http://localhost/$fileName", deps)

class ModInstallServiceTest {
    private val fs = FakeServerFileSystem()
    private val folder = ModFolder("mods", "mods", "mods")
    private val profile = ServerProfile(modFolders = listOf(folder))

    private fun modService(updateSource: ModUpdateSource = FakeModUpdateSource()) =
        ModService({ fs }, updateSource, ModHasher())

    @Test
    fun installsRootAndRequiredDependencySkippingOptional() = runTest {
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["main"] = version(
                ModSource.MODRINTH, "main", "v1", "main.jar",
                deps = listOf(
                    ModDependency(ModSource.MODRINTH, "required-dep", null, required = true),
                    ModDependency(ModSource.MODRINTH, "optional-dep", null, required = false),
                ),
            )
            versions["required-dep"] = version(ModSource.MODRINTH, "required-dep", "v1", "required.jar")
            downloads["main.jar"] = byteArrayOf(1)
            downloads["required.jar"] = byteArrayOf(2)
        }
        val install = ModInstallService(modService(), { listOf(modrinth) })

        val result = install.install(profile, folder, hit(ModSource.MODRINTH, "main"), loader = "fabric", mcVersion = "1.21.1")

        assertEquals(setOf("main.jar", "required.jar"), result.installed.toSet())
        assertTrue(result.skipped.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertEquals(byteArrayOf(1).toList(), fs.files["mods/main.jar"]!!.toList())
        assertEquals(byteArrayOf(2).toList(), fs.files["mods/required.jar"]!!.toList())
    }

    @Test
    fun skipsAlreadyInstalledDependencyByProjectId() = runTest {
        fs.put("mods/already.jar", byteArrayOf(1, 2, 3))
        val updateSource = FakeModUpdateSource(
            mapOf(HASH_123 to ModUpdate("required-dep", "Required", "v1", "v1", "1.0", null, null, null, null, null, null, 0)),
        )
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["main"] = version(
                ModSource.MODRINTH, "main", "v1", "main.jar",
                deps = listOf(ModDependency(ModSource.MODRINTH, "required-dep", null, required = true)),
            )
            downloads["main.jar"] = byteArrayOf(9)
        }
        val install = ModInstallService(modService(updateSource), { listOf(modrinth) })

        val result = install.install(profile, folder, hit(ModSource.MODRINTH, "main"), loader = null, mcVersion = null)

        assertEquals(listOf("main.jar"), result.installed)
        assertEquals(listOf("required-dep"), result.skipped)
    }

    @Test
    fun skipsAlreadyInstalledFileByFilenameEvenWhenDisabled() = runTest {
        fs.put("mods/dep.jar.disabled", byteArrayOf(1, 2, 3))
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["main"] = version(
                ModSource.MODRINTH, "main", "v1", "main.jar",
                deps = listOf(ModDependency(ModSource.MODRINTH, "dep", null, required = true)),
            )
            versions["dep"] = version(ModSource.MODRINTH, "dep", "v1", "dep.jar")
            downloads["main.jar"] = byteArrayOf(9)
        }
        val install = ModInstallService(modService(), { listOf(modrinth) })

        val result = install.install(profile, folder, hit(ModSource.MODRINTH, "main"), loader = null, mcVersion = null)

        assertEquals(listOf("main.jar"), result.installed)
        assertEquals(listOf("dep.jar"), result.skipped)
    }

    @Test
    fun dependencyFollowsTheFolderItsDependentIsAlreadyIn() = runTest {
        val server = ModFolder("server", "Server", "mods")
        val client = ModFolder("client", "Client", "client-mods")
        val twoFolders = ServerProfile(modFolders = listOf(server, client))
        fs.put("client-mods/main.jar", byteArrayOf(9))
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["main"] = version(
                ModSource.MODRINTH, "main", "v1", "main.jar",
                deps = listOf(ModDependency(ModSource.MODRINTH, "dep", null, required = true)),
            )
            versions["dep"] = version(ModSource.MODRINTH, "dep", "v1", "dep.jar")
            downloads["dep.jar"] = byteArrayOf(2)
        }
        val install = ModInstallService(modService(), { listOf(modrinth) })

        // Installed from the server folder, but the mod itself lives in the client one.
        val result = install.install(twoFolders, server, hit(ModSource.MODRINTH, "main"), loader = null, mcVersion = null)

        assertEquals(listOf("main.jar"), result.skipped)
        assertEquals(listOf("dep.jar"), result.installed)
        assertEquals(byteArrayOf(2).toList(), fs.files["client-mods/dep.jar"]!!.toList())
        assertTrue("mods/dep.jar" !in fs.files)
    }

    @Test
    fun cyclicDependenciesTerminate() = runTest {
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["a"] = version(ModSource.MODRINTH, "a", "v1", "a.jar", deps = listOf(ModDependency(ModSource.MODRINTH, "b", null, true)))
            versions["b"] = version(ModSource.MODRINTH, "b", "v1", "b.jar", deps = listOf(ModDependency(ModSource.MODRINTH, "a", null, true)))
            downloads["a.jar"] = byteArrayOf(1)
            downloads["b.jar"] = byteArrayOf(2)
        }
        val install = ModInstallService(modService(), { listOf(modrinth) })

        val result = install.install(profile, folder, hit(ModSource.MODRINTH, "a"), loader = null, mcVersion = null)

        assertEquals(setOf("a.jar", "b.jar"), result.installed.toSet())
    }

    @Test
    fun unresolvableDependencyBecomesFailureButOthersStillInstall() = runTest {
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["main"] = version(
                ModSource.MODRINTH, "main", "v1", "main.jar",
                deps = listOf(
                    ModDependency(ModSource.MODRINTH, "missing-dep", null, required = true),
                    ModDependency(ModSource.MODRINTH, "good-dep", null, required = true),
                ),
            )
            versions["good-dep"] = version(ModSource.MODRINTH, "good-dep", "v1", "good.jar")
            downloads["main.jar"] = byteArrayOf(1)
            downloads["good.jar"] = byteArrayOf(2)
        }
        val install = ModInstallService(modService(), { listOf(modrinth) })

        val result = install.install(profile, folder, hit(ModSource.MODRINTH, "main"), loader = null, mcVersion = null)

        assertEquals(setOf("main.jar", "good.jar"), result.installed.toSet())
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.first().contains("missing-dep"))
    }
}
