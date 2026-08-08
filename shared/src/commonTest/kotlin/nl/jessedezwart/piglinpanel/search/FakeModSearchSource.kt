package nl.jessedezwart.piglinpanel.search

/** In-memory [ModSearchSource] for tests. */
class FakeModSearchSource(
    override val source: ModSource,
    override val available: Boolean = true,
) : ModSearchSource {
    var searchResult: ModSearchPage = ModSearchPage(emptyList(), 0, 0)
    var searchError: Throwable? = null
    val versions = LinkedHashMap<String, ModVersionInfo>()
    val downloads = LinkedHashMap<String, ByteArray>()
    var searchCalls = 0

    override suspend fun search(query: String, loader: String?, mcVersion: String?, offset: Int, limit: Int): ModSearchPage {
        searchCalls++
        searchError?.let { throw it }
        return searchResult
    }

    override suspend fun latestCompatibleVersion(projectId: String, loader: String?, mcVersion: String?): ModVersionInfo? =
        versions[projectId]

    override suspend fun version(projectId: String, versionId: String): ModVersionInfo? =
        versions.values.firstOrNull { it.projectId == projectId && it.versionId == versionId }

    override suspend fun downloadFile(version: ModVersionInfo): ByteArray =
        downloads[version.fileName] ?: error("No fake download registered for ${version.fileName}")
}
