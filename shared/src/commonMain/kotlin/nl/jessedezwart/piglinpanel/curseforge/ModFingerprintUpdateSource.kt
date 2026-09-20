package nl.jessedezwart.piglinpanel.curseforge

import nl.jessedezwart.piglinpanel.model.ModUpdate

/**
 * Update lookups keyed by CurseForge file fingerprint, used as a fallback for mods Modrinth doesn't
 * know. Fakeable in tests.
 */
interface ModFingerprintUpdateSource {
    /** False when no API key is configured, in which case lookups are skipped entirely. */
    val available: Boolean

    /** [loader] and [gameVersion] pin the target; see [nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource]. */
    suspend fun findSuggestedUpdates(
        fingerprints: List<Long>,
        loader: String? = null,
        gameVersion: String? = null,
    ): Map<Long, ModUpdate>

    suspend fun downloadUpdate(update: ModUpdate): ByteArray

    /** Release notes of [update]'s latest version as Markdown, or null when there are none. */
    suspend fun changelog(update: ModUpdate): String? = update.changelog
}
