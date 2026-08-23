package nl.jessedezwart.piglinpanel.search

/** A searchable mod repository (Modrinth, CurseForge, ...); fakeable in tests. */
interface ModSearchSource {
    val source: ModSource

    /** False when the source is unusable, e.g. a required API key is missing. */
    val available: Boolean

    suspend fun search(query: String, loader: String?, mcVersion: String?, offset: Int, limit: Int): ModSearchPage

    /** The newest compatible version for a project, preferring stable releases, or null if none. */
    suspend fun latestCompatibleVersion(projectId: String, loader: String?, mcVersion: String?): ModVersionInfo?

    /**
     * Every Minecraft version this project publishes a file for under [loader] (any loader when
     * null). One request answers the whole sweep, which is why a summary across many candidate
     * versions costs no more than checking a single one.
     */
    suspend fun supportedGameVersions(projectId: String, loader: String?): Set<String>

    suspend fun version(projectId: String, versionId: String): ModVersionInfo?

    suspend fun downloadFile(version: ModVersionInfo): ByteArray
}

/**
 * A repository that can answer "is there a version for this loader/game version?" for many files in
 * one request, keyed by file SHA-1. Optional: a source that does not implement it is asked one
 * project at a time instead.
 */
interface BulkCompatibilitySource {
    /**
     * The newest version matching [loader] (any loader when null) and [gameVersion] for each hash.
     * Hashes with no matching version — and hashes the repository does not know — are absent.
     */
    suspend fun findCompatibleVersions(
        sha1s: List<String>,
        loader: String?,
        gameVersion: String,
    ): Map<String, ModVersionInfo>
}
