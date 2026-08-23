package nl.jessedezwart.piglinpanel.mods

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.search.BulkCompatibilitySource
import nl.jessedezwart.piglinpanel.search.ModSearchSource
import nl.jessedezwart.piglinpanel.search.ModSource
import nl.jessedezwart.piglinpanel.search.ModVersionInfo

/** Whether a mod has a version for some other Minecraft version / loader than the one installed. */
enum class CompatibilityStatus {
    /** A matching version exists and could be installed. */
    COMPATIBLE,

    /** The repository knows the mod but publishes nothing for the target. */
    INCOMPATIBLE,

    /** The question could not be answered — no repository match, no API key, or a failed lookup. */
    UNKNOWN,
}

data class ModCompatibility(
    val key: String,
    val displayName: String,
    val status: CompatibilityStatus,
    /** The repository that answered, or null when nothing could be asked. */
    val source: ModSource?,
    /** The newest version matching the target, when one exists. */
    val version: ModVersionInfo?,
    /** Why the answer is [CompatibilityStatus.UNKNOWN]; null for a definite answer. */
    val detail: String? = null,
)

data class CompatibilityReport(
    val loader: String?,
    val mcVersion: String,
    val results: List<ModCompatibility>,
) {
    fun count(status: CompatibilityStatus): Int = results.count { it.status == status }
}

/** Live progress for the compatibility check, since it can take a request per unmatched mod. */
data class CompatibilityProgress(val done: Int, val total: Int, val displayName: String?)

/** How the pack would fare on one candidate Minecraft version. */
data class VersionSummary(
    val mcVersion: String,
    val compatible: Int,
    /** Mods with nothing published for this version, by display name. */
    val missing: List<String>,
) {
    val incompatible: Int get() = missing.size
}

/**
 * The pack measured against several candidate Minecraft versions at once. [unknown] holds the mods
 * no repository could answer for — the same set for every version, since the gap is in what is known
 * about the mod rather than in the version.
 */
data class CompatibilitySummary(
    val loader: String?,
    /** Mods that were answered for, i.e. everything enabled except [unknown]. */
    val answered: Int,
    val unknown: List<ModCompatibility>,
    val versions: List<VersionSummary>,
)

/**
 * Answers "would my mods survive a move to Minecraft X?" without touching the server: every enabled
 * mod is looked up in the repository that identified it, filtered by the target loader and game
 * version.
 *
 * Modrinth mods are resolved in one bulk request by file hash; whatever that leaves unanswered — the
 * mods with no version for the target, and everything CurseForge identified — costs one request per
 * mod, run a handful at a time so a large pack does not hammer either API.
 */
class ModCompatibilityService(private val sources: () -> List<ModSearchSource>) {
    companion object {
        /** Concurrent per-project lookups. Kept low: both APIs rate-limit per minute. */
        private const val PARALLELISM = 6
    }

    /**
     * Checks the enabled version of each mod in [groups]. Mods with no enabled jar are skipped —
     * they are not part of what the server loads today, so they are not part of the move.
     */
    suspend fun check(
        groups: List<ModGroup>,
        loader: String?,
        mcVersion: String,
        progress: ((CompatibilityProgress) -> Unit)? = null,
    ): CompatibilityReport {
        val targets = groups.filter { it.active != null }
        val available = sources().filter { it.available }.associateBy { it.source }
        val total = targets.size

        // Everything Modrinth already identified, answered in a single request where it can be.
        val modrinth = available[ModSource.MODRINTH] as? BulkCompatibilitySource
        val hashes = targets
            .filter { it.update?.source == ModSource.MODRINTH }
            .mapNotNull { it.active?.sha1 }
            .distinct()
        val bulk: Map<String, ModVersionInfo> = if (modrinth != null && hashes.isNotEmpty()) {
            try {
                modrinth.findCompatibleVersions(hashes, loader, mcVersion)
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        var done = 0
        val results = ArrayList<ModCompatibility>(targets.size)
        // The bulk hits are free; only what it could not answer goes out one request at a time.
        val pending = ArrayList<ModGroup>()
        for (group in targets) {
            val hit = group.active?.sha1?.let(bulk::get)
            if (hit != null) {
                results += ModCompatibility(
                    key = group.key,
                    displayName = group.displayName,
                    status = CompatibilityStatus.COMPATIBLE,
                    source = ModSource.MODRINTH,
                    version = hit,
                )
                done++
            } else {
                pending += group
            }
        }
        progress?.invoke(CompatibilityProgress(done, total, null))

        for (chunk in pending.chunked(PARALLELISM)) {
            val resolved = coroutineScope {
                chunk.map { group -> async { resolve(group, available, loader, mcVersion) } }.awaitAll()
            }
            results += resolved
            done += chunk.size
            progress?.invoke(CompatibilityProgress(done, total, chunk.last().displayName))
        }

        return CompatibilityReport(
            loader = loader,
            mcVersion = mcVersion,
            results = results.sortedWith(compareBy({ sortRank(it.status) }, { it.displayName.lowercase() })),
        )
    }

    /**
     * Measures the pack against every version in [mcVersions] at once, to answer "how far forward
     * can this pack actually go?" rather than one version at a time.
     *
     * Each mod is asked once for the full set of Minecraft versions it publishes for, so the sweep
     * costs one request per mod no matter how many candidate versions are in play — checking twenty
     * versions is no more expensive than checking one.
     */
    suspend fun summarize(
        groups: List<ModGroup>,
        loader: String?,
        mcVersions: List<String>,
        progress: ((CompatibilityProgress) -> Unit)? = null,
    ): CompatibilitySummary {
        val targets = groups.filter { it.active != null }
        val available = sources().filter { it.available }.associateBy { it.source }
        val total = targets.size

        val unknown = mutableListOf<ModCompatibility>()

        /** Display name to the versions that mod publishes for; a list, since names can collide. */
        val supported = mutableListOf<Pair<String, Set<String>>>()
        var done = 0

        for (chunk in targets.chunked(PARALLELISM)) {
            val resolved = coroutineScope {
                chunk.map { group -> async { group to supportedVersions(group, available, loader) } }.awaitAll()
            }
            for ((group, outcome) in resolved) {
                when (outcome) {
                    is Supported.Versions -> supported += group.displayName to outcome.versions
                    is Supported.Unknown -> unknown += outcome.entry
                }
            }
            done += chunk.size
            progress?.invoke(CompatibilityProgress(done, total, chunk.last().displayName))
        }

        return CompatibilitySummary(
            loader = loader,
            answered = supported.size,
            unknown = unknown.sortedBy { it.displayName.lowercase() },
            versions = mcVersions.map { mcVersion ->
                val missing = supported.filter { mcVersion !in it.second }
                    .map { it.first }
                    .sortedBy { it.lowercase() }
                VersionSummary(
                    mcVersion = mcVersion,
                    compatible = supported.size - missing.size,
                    missing = missing,
                )
            },
        )
    }

    /** Either the versions a mod publishes for, or why that could not be established. */
    private sealed interface Supported {
        data class Versions(val versions: Set<String>) : Supported
        data class Unknown(val entry: ModCompatibility) : Supported
    }

    /** One lookup per mod; a dead lookup becomes an unknown entry rather than sinking the sweep. */
    private suspend fun supportedVersions(
        group: ModGroup,
        available: Map<ModSource, ModSearchSource>,
        loader: String?,
    ): Supported {
        fun unknown(detail: String, source: ModSource? = null) = Supported.Unknown(
            ModCompatibility(
                key = group.key,
                displayName = group.displayName,
                status = CompatibilityStatus.UNKNOWN,
                source = source,
                version = null,
                detail = detail,
            ),
        )

        val update = group.update
        val projectId = update?.projectId
            ?: return unknown("not matched to Modrinth or CurseForge; scan with update checking first")
        val source = available[update.source]
            ?: return unknown("${update.source} is unavailable; check its API key", update.source)
        return try {
            Supported.Versions(source.supportedGameVersions(projectId, loader))
        } catch (e: Exception) {
            unknown("lookup failed: ${e.message}", update.source)
        }
    }

    /** What the user is checking for comes first: the mods that would not make the move. */
    private fun sortRank(status: CompatibilityStatus): Int = when (status) {
        CompatibilityStatus.INCOMPATIBLE -> 0
        CompatibilityStatus.UNKNOWN -> 1
        CompatibilityStatus.COMPATIBLE -> 2
    }

    private suspend fun resolve(
        group: ModGroup,
        available: Map<ModSource, ModSearchSource>,
        loader: String?,
        mcVersion: String,
    ): ModCompatibility {
        fun unknown(detail: String, source: ModSource? = null) = ModCompatibility(
            key = group.key,
            displayName = group.displayName,
            status = CompatibilityStatus.UNKNOWN,
            source = source,
            version = null,
            detail = detail,
        )

        val update = group.update
        val projectId = update?.projectId
            ?: return unknown("not matched to Modrinth or CurseForge; scan with update checking first")
        val source = available[update.source]
            ?: return unknown("${update.source} is unavailable; check its API key", update.source)

        val version = try {
            source.latestCompatibleVersion(projectId, loader, mcVersion)
        } catch (e: Exception) {
            return unknown("lookup failed: ${e.message}", update.source)
        }
        return ModCompatibility(
            key = group.key,
            displayName = group.displayName,
            status = if (version != null) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE,
            source = update.source,
            version = version,
        )
    }
}
