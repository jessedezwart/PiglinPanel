package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.search.BulkCompatibilitySource
import nl.jessedezwart.piglinpanel.search.FakeModSearchSource
import nl.jessedezwart.piglinpanel.search.ModSearchSource
import nl.jessedezwart.piglinpanel.search.ModSource
import nl.jessedezwart.piglinpanel.search.ModVersionInfo

/** A [FakeModSearchSource] that can also answer the bulk hash lookup, as Modrinth does. */
private class FakeBulkSource(
    private val delegate: FakeModSearchSource,
) : ModSearchSource by delegate, BulkCompatibilitySource {
    val compatibleByHash = LinkedHashMap<String, ModVersionInfo>()
    var bulkError: Throwable? = null
    var bulkCalls = 0

    override suspend fun findCompatibleVersions(
        sha1s: List<String>,
        loader: String?,
        gameVersion: String,
    ): Map<String, ModVersionInfo> {
        bulkCalls++
        bulkError?.let { throw it }
        return compatibleByHash.filterKeys { it in sha1s }
    }
}

class ModCompatibilityServiceTest {
    private fun version(source: ModSource, projectId: String, name: String) = ModVersionInfo(
        source = source,
        projectId = projectId,
        versionId = "$projectId-v",
        versionName = name,
        fileName = "$projectId.jar",
        downloadUrl = "https://example.invalid/$projectId.jar",
    )

    private fun group(
        name: String,
        sha1: String?,
        source: ModSource? = ModSource.MODRINTH,
        projectId: String? = name,
        active: Boolean = true,
    ): ModGroup {
        val file = ModFile(
            folderId = "f",
            folderName = "mods",
            folderPath = "mods",
            relativePath = "mods/$name.jar",
            fileName = "$name.jar",
            size = 1,
            archived = false,
            sha1 = sha1,
            update = null,
        )
        return ModGroup(
            key = name,
            displayName = name,
            iconUrl = null,
            folderName = "mods",
            active = file.takeIf { active },
            archived = emptyList(),
            update = if (source == null) {
                null
            } else {
                ModUpdate(
                    projectId = projectId,
                    projectName = name,
                    currentVersionId = "cur",
                    latestVersionId = "cur",
                    latestVersionName = "cur",
                    lastUpdatedAt = null,
                    downloadUrl = null,
                    fileName = null,
                    iconUrl = null,
                    clientSide = null,
                    serverSide = null,
                    downloads = 0,
                    source = source,
                )
            },
        )
    }

    private fun statuses(report: CompatibilityReport) =
        report.results.associate { it.displayName to it.status }

    @Test
    fun bulkHashLookupAnswersModrinthModsWithoutPerProjectRequests() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH)
        val modrinth = FakeBulkSource(modrinthFake).apply {
            compatibleByHash["h-sodium"] = version(ModSource.MODRINTH, "sodium", "0.6.0")
        }
        val service = ModCompatibilityService { listOf(modrinth) }

        val report = service.check(listOf(group("sodium", "h-sodium")), "neoforge", "1.21.4")

        assertEquals(CompatibilityStatus.COMPATIBLE, report.results.single().status)
        assertEquals("0.6.0", report.results.single().version?.versionName)
        assertEquals(1, modrinth.bulkCalls)
        assertTrue(modrinthFake.latestCompatibleCalls.isEmpty())
    }

    @Test
    fun modMissingFromBulkIsConfirmedIncompatiblePerProject() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH).apply {
            latestCompatible = { _, _, _ -> null }
        }
        val modrinth = FakeBulkSource(modrinthFake)
        val service = ModCompatibilityService { listOf(modrinth) }

        val report = service.check(listOf(group("create", "h-create")), "neoforge", "1.21.4")

        assertEquals(CompatibilityStatus.INCOMPATIBLE, report.results.single().status)
        assertEquals(listOf("create"), modrinthFake.latestCompatibleCalls)
    }

    @Test
    fun curseForgeModsAreAskedOfCurseForge() = runTest {
        val modrinth = FakeBulkSource(FakeModSearchSource(ModSource.MODRINTH))
        val curseForgeFake = FakeModSearchSource(ModSource.CURSEFORGE).apply {
            versions["1234"] = version(ModSource.CURSEFORGE, "1234", "2.0")
        }
        val service = ModCompatibilityService { listOf(modrinth, curseForgeFake) }

        val report = service.check(
            listOf(group("jei", "h-jei", source = ModSource.CURSEFORGE, projectId = "1234")),
            "neoforge",
            "1.21.4",
        )

        assertEquals(CompatibilityStatus.COMPATIBLE, report.results.single().status)
        assertEquals(ModSource.CURSEFORGE, report.results.single().source)
        assertEquals(listOf("1234"), curseForgeFake.latestCompatibleCalls)
    }

    @Test
    fun unmatchedModAndUnavailableSourceAreUnknownRatherThanIncompatible() = runTest {
        val modrinth = FakeBulkSource(FakeModSearchSource(ModSource.MODRINTH))
        val curseForge = FakeModSearchSource(ModSource.CURSEFORGE, available = false)
        val service = ModCompatibilityService { listOf(modrinth, curseForge) }

        val report = service.check(
            listOf(
                group("homemade", "h-home", source = null),
                group("jei", "h-jei", source = ModSource.CURSEFORGE, projectId = "1234"),
            ),
            "neoforge",
            "1.21.4",
        )

        assertEquals(
            mapOf("homemade" to CompatibilityStatus.UNKNOWN, "jei" to CompatibilityStatus.UNKNOWN),
            statuses(report),
        )
        assertTrue(report.results.all { it.detail != null })
    }

    @Test
    fun bulkFailureFallsBackToPerProjectLookups() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH).apply {
            versions["sodium"] = version(ModSource.MODRINTH, "sodium", "0.6.0")
        }
        val modrinth = FakeBulkSource(modrinthFake).apply { bulkError = RuntimeException("boom") }
        val service = ModCompatibilityService { listOf(modrinth) }

        val report = service.check(listOf(group("sodium", "h-sodium")), "neoforge", "1.21.4")

        assertEquals(CompatibilityStatus.COMPATIBLE, report.results.single().status)
        assertEquals(listOf("sodium"), modrinthFake.latestCompatibleCalls)
    }

    @Test
    fun disabledModsAreSkippedAndResultsLeadWithTheProblems() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH).apply {
            latestCompatible = { projectId, _, _ -> versions[projectId] }
            versions["sodium"] = version(ModSource.MODRINTH, "sodium", "0.6.0")
        }
        val modrinth = FakeBulkSource(modrinthFake)
        val service = ModCompatibilityService { listOf(modrinth) }

        val report = service.check(
            listOf(
                group("sodium", "h-sodium"),
                group("create", "h-create"),
                group("unmatched", "h-unmatched", source = null),
                group("turnedoff", "h-off", active = false),
            ),
            "neoforge",
            "1.21.4",
        )

        assertEquals(listOf("create", "unmatched", "sodium"), report.results.map { it.displayName })
        assertEquals(1, report.count(CompatibilityStatus.INCOMPATIBLE))
        assertEquals(1, report.count(CompatibilityStatus.UNKNOWN))
        assertEquals(1, report.count(CompatibilityStatus.COMPATIBLE))
    }

    @Test
    fun summaryCountsMissingModsPerVersionAndAsksEachModOnce() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH).apply {
            supportedVersions["sodium"] = setOf("1.21.4", "1.21.5", "1.21.6")
            supportedVersions["create"] = setOf("1.21.4", "1.21.5")
            supportedVersions["oldmod"] = setOf("1.21.4")
        }
        val modrinth = FakeBulkSource(modrinthFake)
        val service = ModCompatibilityService { listOf(modrinth) }

        val summary = service.summarize(
            groups = listOf(group("sodium", "h1"), group("create", "h2"), group("oldmod", "h3")),
            loader = "neoforge",
            mcVersions = listOf("1.21.5", "1.21.6"),
        )

        assertEquals(3, summary.answered)
        assertEquals(listOf("1.21.5", "1.21.6"), summary.versions.map { it.mcVersion })
        assertEquals(listOf("oldmod"), summary.versions[0].missing)
        assertEquals(2, summary.versions[0].compatible)
        assertEquals(listOf("create", "oldmod"), summary.versions[1].missing)
        assertEquals(1, summary.versions[1].compatible)
        // The whole sweep costs one lookup per mod, however many versions are asked about.
        assertEquals(listOf("sodium", "create", "oldmod"), modrinthFake.supportedVersionsCalls)
    }

    @Test
    fun summaryKeepsUnanswerableModsOutOfTheCountsAndNamesThem() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH).apply {
            supportedVersions["sodium"] = setOf("1.21.5")
        }
        val modrinth = FakeBulkSource(modrinthFake)
        val curseForge = FakeModSearchSource(ModSource.CURSEFORGE, available = false)
        val service = ModCompatibilityService { listOf(modrinth, curseForge) }

        val summary = service.summarize(
            groups = listOf(
                group("sodium", "h1"),
                group("homemade", "h2", source = null),
                group("jei", "h3", source = ModSource.CURSEFORGE, projectId = "1234"),
            ),
            loader = "neoforge",
            mcVersions = listOf("1.21.5"),
        )

        assertEquals(1, summary.answered)
        assertEquals(listOf("homemade", "jei"), summary.unknown.map { it.displayName })
        assertEquals(1, summary.versions.single().compatible)
        assertTrue(summary.versions.single().missing.isEmpty())
    }

    @Test
    fun summaryTreatsAFailedLookupAsUnknownRatherThanMissing() = runTest {
        val modrinthFake = FakeModSearchSource(ModSource.MODRINTH).apply {
            supportedVersionsError = RuntimeException("rate limited")
        }
        val modrinth = FakeBulkSource(modrinthFake)
        val service = ModCompatibilityService { listOf(modrinth) }

        val summary = service.summarize(listOf(group("sodium", "h1")), "neoforge", listOf("1.21.5"))

        assertEquals(0, summary.answered)
        assertEquals(listOf("sodium"), summary.unknown.map { it.displayName })
        assertTrue(summary.versions.single().missing.isEmpty())
    }

    @Test
    fun progressReportsEveryCheckedMod() = runTest {
        val modrinth = FakeBulkSource(FakeModSearchSource(ModSource.MODRINTH)).apply {
            compatibleByHash["h-a"] = version(ModSource.MODRINTH, "a", "1")
        }
        val service = ModCompatibilityService { listOf(modrinth) }
        val seen = mutableListOf<CompatibilityProgress>()

        service.check(listOf(group("a", "h-a"), group("b", "h-b")), "neoforge", "1.21.4") { seen += it }

        assertEquals(2, seen.last().done)
        assertEquals(2, seen.last().total)
    }
}
