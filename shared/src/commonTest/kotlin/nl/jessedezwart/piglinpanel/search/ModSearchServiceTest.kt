package nl.jessedezwart.piglinpanel.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ModSearchServiceTest {
    private fun hit(source: ModSource, id: String, downloads: Long) = ModSearchHit(
        source = source,
        projectId = id,
        title = id,
        description = "",
        iconUrl = null,
        author = null,
        downloads = downloads,
    )

    @Test
    fun mergesAndSortsHitsAcrossSourcesByDownloads() = runTest {
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            searchResult = ModSearchPage(listOf(hit(ModSource.MODRINTH, "m1", 100)), totalHits = 1, offset = 0)
        }
        val curseforge = FakeModSearchSource(ModSource.CURSEFORGE).apply {
            searchResult = ModSearchPage(listOf(hit(ModSource.CURSEFORGE, "c1", 500)), totalHits = 1, offset = 0)
        }
        val service = ModSearchService { listOf(modrinth, curseforge) }

        val result = service.search("sodium", null, null, 0, 20)

        assertEquals(listOf("c1", "m1"), result.hits.map { it.projectId })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun skipsUnavailableSourceWithWarningAndNoSearchCall() = runTest {
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            searchResult = ModSearchPage(listOf(hit(ModSource.MODRINTH, "m1", 100)), totalHits = 1, offset = 0)
        }
        val curseforge = FakeModSearchSource(ModSource.CURSEFORGE, available = false)
        val service = ModSearchService { listOf(modrinth, curseforge) }

        val result = service.search("sodium", null, null, 0, 20)

        assertEquals(1, result.hits.size)
        assertEquals(0, curseforge.searchCalls)
        assertTrue(result.warnings.any { it.contains("CURSEFORGE") })
    }

    @Test
    fun failingSourceBecomesWarningNotTotalFailure() = runTest {
        val modrinth = FakeModSearchSource(ModSource.MODRINTH).apply {
            searchResult = ModSearchPage(listOf(hit(ModSource.MODRINTH, "m1", 100)), totalHits = 1, offset = 0)
        }
        val curseforge = FakeModSearchSource(ModSource.CURSEFORGE).apply {
            searchError = RuntimeException("boom")
        }
        val service = ModSearchService { listOf(modrinth, curseforge) }

        val result = service.search("sodium", null, null, 0, 20)

        assertEquals(1, result.hits.size)
        assertTrue(result.warnings.any { it.contains("boom") })
    }
}
