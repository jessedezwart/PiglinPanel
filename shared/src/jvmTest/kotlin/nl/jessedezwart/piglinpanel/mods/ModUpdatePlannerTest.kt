package nl.jessedezwart.piglinpanel.mods

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource

/** Serves the jar bytes each update would install, keyed by the file name it carries. */
private class FakeDownloads(private val jars: Map<String, ByteArray>) : ModUpdateSource {
    override suspend fun findSuggestedUpdates(sha1s: List<String>, loader: String?, gameVersion: String?): Map<String, ModUpdate> = emptyMap()

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray =
        jars[update.fileName] ?: error("no jar staged for ${update.fileName}")
}

class ModUpdatePlannerTest {
    private val fs = FakeServerFileSystem()

    private fun jar(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun fabric(id: String, version: String, depends: String = ""): ByteArray =
        jar("""{ "id": "$id", "version": "$version" ${if (depends.isEmpty()) "" else ", \"depends\": { $depends }"} }""")

    private fun update(fileName: String, projectName: String) = ModUpdate(
        projectId = fileName,
        projectName = projectName,
        currentVersionId = "old",
        latestVersionId = "new",
        latestVersionName = "new",
        lastUpdatedAt = null,
        downloadUrl = "https://example.com/$fileName",
        fileName = fileName,
        iconUrl = null,
        clientSide = null,
        serverSide = null,
        downloads = 0,
    )

    private fun installed(
        modId: String,
        version: String,
        requires: List<ModDependencyRef> = emptyList(),
        updateTo: String? = null,
    ) = ModFile(
        folderId = "mods",
        folderName = "mods",
        folderPath = "mods",
        relativePath = "mods/$modId.jar",
        fileName = "$modId.jar",
        size = 1,
        archived = false,
        sha1 = null,
        update = updateTo?.let { update(it, modId) },
        metadata = ModJarMetadata(modId = modId, version = version, requires = requires),
    )

    private fun planner(jars: Map<String, ByteArray>) =
        ModUpdatePlanner(ModService({ fs }, FakeDownloads(jars), ModHasher()))

    @Test
    fun blocksAnUpdateThatWouldOutrunADependentsRange() = runTest {
        // Sodium accepts fabric-api below 0.100; the update pushes it past that.
        val api = installed("fabric-api", "0.92.2", updateTo = "fabric-api-0.110.jar")
        val sodium = installed("sodium", "0.5.8", requires = listOf(ModDependencyRef("fabric-api", "<0.100.0")))
        val jars = mapOf("fabric-api-0.110.jar" to fabric("fabric-api", "0.110.0"))

        val plan = planner(jars).plan(ServerProfile(), listOf(api, sodium), listOf(api))

        assertEquals(emptyList(), plan.safe)
        assertEquals(listOf("sodium: needs fabric-api <0.100.0 (have 0.110.0)"), plan.blocked.single().problems.map { it.toString() })
    }

    @Test
    fun blocksAnUpdateThatBringsADependencyNothingProvides() = runTest {
        val create = installed("create", "0.5.1", updateTo = "create-6.0.jar")
        val jars = mapOf("create-6.0.jar" to fabric("create", "6.0.0", """ "ponder": "*" """))

        val plan = planner(jars).plan(ServerProfile(), listOf(create), listOf(create))

        assertEquals(listOf("create: missing ponder"), plan.blocked.single().problems.map { it.toString() })
    }

    @Test
    fun letsASafeUpdateThroughAndKeepsItsBytes() = runTest {
        val api = installed("fabric-api", "0.92.2", updateTo = "fabric-api-0.110.jar")
        val sodium = installed("sodium", "0.5.8", requires = listOf(ModDependencyRef("fabric-api", ">=0.90.0")))
        val jars = mapOf("fabric-api-0.110.jar" to fabric("fabric-api", "0.110.0"))

        val plan = planner(jars).plan(ServerProfile(), listOf(api, sodium), listOf(api))

        val entry = plan.safe.single()
        assertEquals("fabric-api", entry.displayName)
        assertEquals("0.110.0", entry.metadata?.version)
        assertTrue(entry.bytes.contentEquals(jars.getValue("fabric-api-0.110.jar")))
    }

    @Test
    fun countsTheBatchAsAWholeRatherThanOneUpdateAtATime() = runTest {
        // Neither update is safe alone — Sodium's new version needs an API the old jar cannot
        // supply — but together they are, which is the case a per-mod check gets wrong.
        val api = installed("fabric-api", "0.92.2", updateTo = "fabric-api-0.110.jar")
        val sodium = installed("sodium", "0.5.8", updateTo = "sodium-0.6.jar")
        val jars = mapOf(
            "fabric-api-0.110.jar" to fabric("fabric-api", "0.110.0"),
            "sodium-0.6.jar" to fabric("sodium", "0.6.0", """ "fabric-api": ">=0.100.0" """),
        )

        val together = planner(jars).plan(ServerProfile(), listOf(api, sodium), listOf(api, sodium))
        assertEquals(2, together.safe.size)

        val sodiumAlone = planner(jars).plan(ServerProfile(), listOf(api, sodium), listOf(sodium))
        assertEquals(
            listOf("sodium: needs fabric-api >=0.100.0 (have 0.92.2)"),
            sodiumAlone.blocked.single().problems.map { it.toString() },
        )
    }

    @Test
    fun doesNotBlameAnUpdateForBreakageThatWasAlreadyThere() = runTest {
        // This mod is missing a dependency before and after; the update did not cause it.
        val broken = installed(
            "mobs",
            "1.0",
            requires = listOf(ModDependencyRef("geckolib")),
            updateTo = "mobs-1.1.jar",
        )
        val jars = mapOf("mobs-1.1.jar" to fabric("mobs", "1.1.0", """ "geckolib": "*" """))

        val plan = planner(jars).plan(ServerProfile(), listOf(broken), listOf(broken))

        assertEquals(1, plan.safe.size)
    }

    @Test
    fun reportsADownloadFailureWithoutJudgingTheRest() = runTest {
        val api = installed("fabric-api", "0.92.2", updateTo = "missing.jar")
        val sodium = installed("sodium", "0.5.8", updateTo = "sodium-0.6.jar")
        val jars = mapOf("sodium-0.6.jar" to fabric("sodium", "0.6.0"))

        val plan = planner(jars).plan(ServerProfile(), listOf(api, sodium), listOf(api, sodium))

        assertEquals(listOf("fabric-api"), plan.failed.map { it.displayName })
        assertEquals(listOf("sodium"), plan.safe.map { it.displayName })
    }
}
