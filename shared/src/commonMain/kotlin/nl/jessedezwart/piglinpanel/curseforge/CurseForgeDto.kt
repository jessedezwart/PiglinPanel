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
    val primaryCategoryId: Int? = null,
) {
    /**
     * Whether the mod's *primary* category is "API and Library". Secondary categories are handed out
     * as loosely as Modrinth's tags, so a mod merely also filed there is not taken to be a library.
     */
    fun isLibrary(): Boolean = primaryCategoryId == API_AND_LIBRARY_CATEGORY_ID
}

/** Minecraft mods' "API and Library" category on CurseForge. */
private const val API_AND_LIBRARY_CATEGORY_ID = 421

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

@Serializable
data class CurseForgeChangelogResponse(val data: String? = null)

/**
 * CurseForge changelogs are HTML, while the panel renders changelogs as Markdown (Modrinth's native
 * format). The common tags map onto their Markdown equivalents; every other tag is dropped and the
 * common entities decoded. Line breaks become Markdown hard breaks, since a bare newline would be
 * folded into the paragraph around it.
 */
internal fun htmlToMarkdown(html: String): String = html
    .replace(Regex("(?i)<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>")) { "[${it.groupValues[2]}](${it.groupValues[1]})" }
    .replace(Regex("(?i)<h([1-6])[^>]*>")) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
    .replace(Regex("(?i)</?(strong|b)>"), "**")
    .replace(Regex("(?i)</?(em|i)>"), "*")
    .replace(Regex("(?i)</?code>"), "`")
    .replace(Regex("(?i)<br\\s*/?>"), "  \n")
    .replace(Regex("(?i)<li[^>]*>"), "\n- ")
    .replace(Regex("(?i)</(p|div|h[1-6]|ul|ol)>"), "\n\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&amp;", "&")
    // Trailing spaces are kept only where they are a hard break, so stray indentation cannot turn
    // into one.
    .lines()
    .joinToString("\n") { if (it.endsWith("  ") && it.isNotBlank()) it.trimEnd() + "  " else it.trimEnd() }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

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
