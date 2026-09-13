package nl.jessedezwart.piglinpanel.modrinth

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModrinthProject(
    val id: String? = null,
    val title: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("client_side") val clientSide: String? = null,
    @SerialName("server_side") val serverSide: String? = null,
    val downloads: Long = 0,
    val categories: List<String> = emptyList(),
) {
    /**
     * Whether the project is *only* a library. Authors hand the `library` tag out freely — Create
     * add-ons list it beside `technology` and `utility`, and Accessories carries it as an additional
     * category — so a project that is anything else as well is taken to be that.
     */
    fun isLibrary(): Boolean = categories == listOf("library")
}

@Serializable
data class ModrinthVersion(
    val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val name: String? = null,
    val loaders: List<String>? = null,
    @SerialName("game_versions") val gameVersions: List<String>? = null,
    val files: List<ModrinthFile>? = null,
    @SerialName("date_published") val datePublished: String? = null,
    @SerialName("version_type") val versionType: String? = null,
    val dependencies: List<ModrinthDependency>? = null,
) {
    fun publishedAtInstant(): Instant? {
        if (datePublished.isNullOrBlank()) return null
        return try {
            Instant.parse(datePublished)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun primaryFile(): ModrinthFile? {
        if (files.isNullOrEmpty()) return null
        return files.firstOrNull { it.primary } ?: files.first()
    }

    fun fileByName(fileName: String?): ModrinthFile? {
        if (fileName.isNullOrBlank() || files.isNullOrEmpty()) return null
        return files.firstOrNull { it.filename == fileName }
    }
}

@Serializable
data class ModrinthFile(
    val filename: String? = null,
    val url: String? = null,
    val primary: Boolean = false,
)

@Serializable
data class ModrinthDependency(
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("dependency_type") val dependencyType: String? = null,
)

@Serializable
data class ModrinthSearchResponse(
    val hits: List<ModrinthSearchHit> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    @SerialName("total_hits") val totalHits: Int = 0,
)

@Serializable
data class ModrinthSearchHit(
    @SerialName("project_id") val projectId: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val author: String? = null,
    val downloads: Long = 0,
)

@Serializable
data class ModrinthGameVersion(
    val version: String,
    @SerialName("version_type") val versionType: String? = null,
    val date: String? = null,
) {
    fun releasedAtInstant(): Instant? {
        if (date.isNullOrBlank()) return null
        return try {
            Instant.parse(date)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
