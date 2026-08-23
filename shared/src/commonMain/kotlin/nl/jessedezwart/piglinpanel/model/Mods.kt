package nl.jessedezwart.piglinpanel.model

import kotlin.math.roundToLong
import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.mods.ModJarMetadata
import nl.jessedezwart.piglinpanel.search.ModSource

data class ModFile(
    val folderId: String,
    val folderName: String,
    val folderPath: String,
    val relativePath: String,
    val fileName: String,
    val size: Long,
    val archived: Boolean,
    val sha1: String?,
    val update: ModUpdate?,
    /** What the jar declares about itself; null when it has not been read or has no manifest. */
    val metadata: ModJarMetadata? = null,
)

/**
 * A mod and all of its on-disk versions grouped together. The [active] file is the enabled `.jar`;
 * [archived] holds the disabled (`.jar.disabled`) versions that can be re-activated. [update] is
 * the suggested update for the active version, if any.
 */
data class ModGroup(
    val key: String,
    val displayName: String,
    val iconUrl: String?,
    val folderName: String,
    val active: ModFile?,
    val archived: List<ModFile>,
    val update: ModUpdate?,
    /** Names of enabled mods that declare a hard dependency on this one; empty when nothing does. */
    val requiredBy: List<String> = emptyList(),
    /** Required mod ids that no enabled jar provides — this mod will not load as things stand. */
    val missingDependencies: List<String> = emptyList(),
    /**
     * Names of dependencies that are installed but disabled. Separate from [missingDependencies]
     * because the fix is a click, not a download — but still the user's click to make.
     */
    val disabledDependencies: List<String> = emptyList(),
)

data class ModUpdate(
    val projectId: String?,
    val projectName: String?,
    val currentVersionId: String?,
    val latestVersionId: String?,
    val latestVersionName: String?,
    val lastUpdatedAt: Instant?,
    val downloadUrl: String?,
    val fileName: String?,
    val iconUrl: String?,
    val clientSide: String?,
    val serverSide: String?,
    val downloads: Long,
    /** Which repository resolved this update; determines who downloads [downloadUrl]. */
    val source: ModSource = ModSource.MODRINTH,
) {
    fun available(): Boolean =
        latestVersionId != null && latestVersionId != currentVersionId && downloadUrl != null

    fun formattedDownloads(): String =
        when {
            downloads >= 1_000_000 -> {
                val tenthsOfMillions = (downloads / 100_000.0).roundToLong()
                "${tenthsOfMillions / 10}.${tenthsOfMillions % 10}M"
            }
            downloads >= 1_000 -> "${(downloads / 1_000.0).roundToLong()}K"
            else -> downloads.toString()
        }
}
