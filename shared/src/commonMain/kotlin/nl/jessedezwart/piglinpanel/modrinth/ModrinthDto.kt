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
)

@Serializable
data class ModrinthVersion(
    val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val name: String? = null,
    val loaders: List<String>? = null,
    @SerialName("game_versions") val gameVersions: List<String>? = null,
    val files: List<ModrinthFile>? = null,
    @SerialName("date_published") val datePublished: String? = null,
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
