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

    /** Overridable so a test can make the answer depend on the requested loader/game version. */
    var latestCompatible: (projectId: String, loader: String?, mcVersion: String?) -> ModVersionInfo? =
        { projectId, _, _ -> versions[projectId] }
    val latestCompatibleCalls = mutableListOf<String>()

    override suspend fun latestCompatibleVersion(projectId: String, loader: String?, mcVersion: String?): ModVersionInfo? {
        latestCompatibleCalls += projectId
        return latestCompatible(projectId, loader, mcVersion)
    }

    /** Minecraft versions per project id, for the compatibility sweep. */
    val supportedVersions = LinkedHashMap<String, Set<String>>()
    var supportedVersionsError: Throwable? = null
    val supportedVersionsCalls = mutableListOf<String>()

    override suspend fun supportedGameVersions(projectId: String, loader: String?): Set<String> {
        supportedVersionsCalls += projectId
        supportedVersionsError?.let { throw it }
        return supportedVersions[projectId].orEmpty()
    }

    override suspend fun version(projectId: String, versionId: String): ModVersionInfo? =
        versions.values.firstOrNull { it.projectId == projectId && it.versionId == versionId }

    override suspend fun downloadFile(version: ModVersionInfo): ByteArray =
        downloads[version.fileName] ?: error("No fake download registered for ${version.fileName}")
}
