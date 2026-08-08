package nl.jessedezwart.piglinpanel.search

/** A searchable mod repository (Modrinth, CurseForge, ...); fakeable in tests. */
interface ModSearchSource {
    val source: ModSource

    /** False when the source is unusable, e.g. a required API key is missing. */
    val available: Boolean

    suspend fun search(query: String, loader: String?, mcVersion: String?, offset: Int, limit: Int): ModSearchPage

    /** The newest compatible version for a project, preferring stable releases, or null if none. */
    suspend fun latestCompatibleVersion(projectId: String, loader: String?, mcVersion: String?): ModVersionInfo?

    suspend fun version(projectId: String, versionId: String): ModVersionInfo?

    suspend fun downloadFile(version: ModVersionInfo): ByteArray
}
