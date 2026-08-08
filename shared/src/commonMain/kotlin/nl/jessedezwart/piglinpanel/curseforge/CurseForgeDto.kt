package nl.jessedezwart.piglinpanel.curseforge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CurseForgeSearchResponse(
    val data: List<CurseForgeMod> = emptyList(),
    val pagination: CurseForgePagination = CurseForgePagination(),
)

@Serializable
data class CurseForgePagination(
    val index: Int = 0,
    val pageSize: Int = 0,
    val resultCount: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
data class CurseForgeMod(
    val id: Int? = null,
    val name: String? = null,
    val summary: String? = null,
    val logo: CurseForgeLogo? = null,
    val authors: List<CurseForgeAuthor> = emptyList(),
    val downloadCount: Long = 0,
    /** Populated by the bulk `/mods` endpoint; the newest file per game version + loader. */
    val latestFiles: List<CurseForgeFile> = emptyList(),
)

@Serializable
data class CurseForgeLogo(val thumbnailUrl: String? = null)

@Serializable
data class CurseForgeAuthor(val name: String? = null)

@Serializable
data class CurseForgeModsResponse(val data: List<CurseForgeMod> = emptyList())

@Serializable
data class CurseForgeFingerprintsResponse(
    val data: CurseForgeFingerprintMatches = CurseForgeFingerprintMatches(),
)

@Serializable
data class CurseForgeFingerprintMatches(
    val exactMatches: List<CurseForgeFingerprintMatch> = emptyList(),
)

@Serializable
data class CurseForgeFingerprintMatch(
    val id: Int? = null,
    val file: CurseForgeFile? = null,
)

@Serializable
data class CurseForgeFilesResponse(
    val data: List<CurseForgeFile> = emptyList(),
)

@Serializable
data class CurseForgeFileResponse(val data: CurseForgeFile? = null)

@Serializable
data class CurseForgeFile(
    val id: Int? = null,
    val modId: Int? = null,
    val fileName: String? = null,
    val displayName: String? = null,
    val downloadUrl: String? = null,
    val releaseType: Int? = null,
    val gameVersions: List<String> = emptyList(),
    val dependencies: List<CurseForgeFileDependency> = emptyList(),
    val fileDate: String? = null,
    val fileFingerprint: Long? = null,
)

/** Loader names CurseForge mixes into [CurseForgeFile.gameVersions] alongside Minecraft versions. */
internal val CURSEFORGE_LOADER_NAMES = setOf("forge", "fabric", "quilt", "neoforge")

@Serializable
data class CurseForgeFileDependency(
    val modId: Int? = null,
    val relationType: Int? = null,
)

/** Loader ids for the `modLoaderType` search/file filter. */
object CurseForgeLoaderType {
    const val FORGE = 1
    const val FABRIC = 4
    const val QUILT = 5
    const val NEOFORGE = 6

    fun fromName(loader: String?): Int? = when (loader?.lowercase()) {
        "forge" -> FORGE
        "fabric" -> FABRIC
        "quilt" -> QUILT
        "neoforge" -> NEOFORGE
        else -> null
    }
}

/** [CurseForgeFileDependency.relationType] values relevant to install resolution. */
object CurseForgeRelationType {
    const val REQUIRED_DEPENDENCY = 3
    const val OPTIONAL_DEPENDENCY = 2
}

/** [CurseForgeFile.releaseType] values. */
object CurseForgeReleaseType {
    const val RELEASE = 1
}
