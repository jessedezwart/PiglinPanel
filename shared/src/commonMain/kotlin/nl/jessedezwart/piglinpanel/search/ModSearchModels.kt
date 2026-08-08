package nl.jessedezwart.piglinpanel.search

/** Which mod repository a search hit or install originated from. */
enum class ModSource { MODRINTH, CURSEFORGE }

data class ModSearchHit(
    val source: ModSource,
    val projectId: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val author: String?,
    val downloads: Long,
)

data class ModSearchPage(
    val hits: List<ModSearchHit>,
    val totalHits: Int,
    val offset: Int,
) {
    val hasMore: Boolean
        get() = offset + hits.size < totalHits
}

/** A dependency declared by a [ModVersionInfo]; always resolved against the same [source]. */
data class ModDependency(
    val source: ModSource,
    val projectId: String,
    /** A pinned version id, when the parent version depends on an exact dependency version. */
    val versionId: String?,
    val required: Boolean,
)

data class ModVersionInfo(
    val source: ModSource,
    val projectId: String,
    val versionId: String,
    val versionName: String?,
    val fileName: String,
    val downloadUrl: String,
    val dependencies: List<ModDependency> = emptyList(),
)

/** Progress callback payload for [nl.jessedezwart.piglinpanel.search.ModInstallService]. */
data class InstallProgress(val phase: String, val itemTitle: String, val done: Int, val total: Int)

data class InstallResult(
    val installed: List<String>,
    val skipped: List<String>,
    val failures: List<String>,
)
