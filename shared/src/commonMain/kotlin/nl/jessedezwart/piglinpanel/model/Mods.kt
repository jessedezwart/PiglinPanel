package nl.jessedezwart.piglinpanel.model

import kotlin.math.roundToLong
import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.mods.ModJarMetadata
import nl.jessedezwart.piglinpanel.mods.ModSide
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
    /** Last modification time of the jar on the server; null when the listing carried none. */
    val modifiedAt: Instant? = null,
    /** The repositories that recognise this exact file. Empty until a scan has looked it up. */
    val sources: Set<ModSource> = emptySet(),
) {
    /**
     * Where this mod runs. The jar wins wherever it says anything: the loader enforces what the jar
     * declares, while a repository's support levels are a curated judgement about the same mod.
     */
    val side: ModSide
        get() = metadata?.side?.takeIf { it != ModSide.UNKNOWN }
            ?: ModSide.ofRepository(update?.clientSide, update?.serverSide)

    /** Whether the repository files this mod as a library, or the jar badges itself as one. */
    val library: Boolean
        get() = update?.library == true || metadata?.library == true
}

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
    /** Where this mod runs, resolved from the jar and then the repository; see [ModFile.side]. */
    val side: ModSide = ModSide.UNKNOWN,
    /** The repositories that recognise this mod's active file. */
    val sources: Set<ModSource> = emptySet(),
    /** Downloads reported by the repository for this project, or 0 when none knows it. */
    val downloads: Long = 0,
    /** Last modification time of the active jar on the server. */
    val modifiedAt: Instant? = null,
    /** Required mod ids that no enabled jar provides — this mod will not load as things stand. */
    val missingDependencies: List<String> = emptyList(),
    /**
     * Names of dependencies that are installed but disabled. Separate from [missingDependencies]
     * because the fix is a click, not a download — but still the user's click to make.
     */
    val disabledDependencies: List<String> = emptyList(),
    /** Whether this mod is a library; see [ModFile.library]. */
    val library: Boolean = false,
    /**
     * A library whose enabled jar no other enabled mod declares a use for, required or optional —
     * most likely left behind by a mod that has since been removed. Never set for a jar whose
     * manifest could not be read, since then nothing is known about who uses it.
     */
    val unusedLibrary: Boolean = false,
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
    /** Whether the repository files this project as a library. */
    val library: Boolean = false,
    /**
     * Release notes of the latest version, when the lookup that found it carried them. Modrinth's
     * does; CurseForge serves them from a separate endpoint, so theirs are fetched on demand.
     */
    val changelog: String? = null,
) {
    fun available(): Boolean =
        latestVersionId != null && latestVersionId != currentVersionId && downloadUrl != null

    fun formattedDownloads(): String = formatDownloads(downloads)
}

/** A download count shortened to fit a table cell: `1.2M`, `340K`, `87`. */
fun formatDownloads(downloads: Long): String =
    when {
        downloads >= 1_000_000 -> {
            val tenthsOfMillions = (downloads / 100_000.0).roundToLong()
            "${tenthsOfMillions / 10}.${tenthsOfMillions % 10}M"
        }
        downloads >= 1_000 -> "${(downloads / 1_000.0).roundToLong()}K"
        else -> downloads.toString()
    }

/** A file size shortened to fit a table cell: `1.4 MB`, `220 KB`, `87 B`. */
fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> {
            val tenths = (bytes / 104_857.6).roundToLong()
            "${tenths / 10}.${tenths % 10} MB"
        }
        bytes >= 1_024 -> "${(bytes / 1_024.0).roundToLong()} KB"
        else -> "$bytes B"
    }
