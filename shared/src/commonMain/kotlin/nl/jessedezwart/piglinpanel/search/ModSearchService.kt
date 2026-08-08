package nl.jessedezwart.piglinpanel.search

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class CombinedSearchResult(
    val hits: List<ModSearchHit>,
    val hasMore: Boolean,
    /** One message per source that failed or is unavailable; the search still returns partial results. */
    val warnings: List<String>,
)

/**
 * Fans a search out across every available [ModSearchSource] in parallel. Unavailable sources
 * (e.g. CurseForge without an API key) are silently skipped; sources whose request fails surface a
 * warning instead of failing the whole search. Results are not deduplicated across sources — mod
 * identity isn't reliably comparable between Modrinth and CurseForge — so hits are simply merged and
 * sorted by downloads, each carrying its own [ModSearchHit.source] badge.
 */
class ModSearchService(private val sources: () -> List<ModSearchSource>) {
    suspend fun search(
        query: String,
        loader: String?,
        mcVersion: String?,
        offset: Int,
        limit: Int,
    ): CombinedSearchResult = coroutineScope {
        val allSources = sources()
        val unavailable = allSources.filterNot { it.available }
            .map { "${it.source} is unavailable" }
        val available = allSources.filter { it.available }

        val outcomes = available
            .map { src -> src to async { runCatching { src.search(query, loader, mcVersion, offset, limit) } } }
            .map { (src, deferred) -> src to deferred.await() }

        val warnings = unavailable + outcomes.mapNotNull { (src, result) ->
            result.exceptionOrNull()?.let { "${src.source} search failed: ${it.message}" }
        }
        val pages = outcomes.mapNotNull { (_, result) -> result.getOrNull() }

        CombinedSearchResult(
            hits = pages.flatMap { it.hits }.sortedByDescending { it.downloads },
            hasMore = pages.any { it.hasMore },
            warnings = warnings,
        )
    }
}
