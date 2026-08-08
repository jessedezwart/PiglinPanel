package nl.jessedezwart.piglinpanel.curseforge

import nl.jessedezwart.piglinpanel.model.ModUpdate

/**
 * Update lookups keyed by CurseForge file fingerprint, used as a fallback for mods Modrinth doesn't
 * know. Fakeable in tests.
 */
interface ModFingerprintUpdateSource {
    /** False when no API key is configured, in which case lookups are skipped entirely. */
    val available: Boolean

    suspend fun findSuggestedUpdates(fingerprints: List<Long>): Map<Long, ModUpdate>

    suspend fun downloadUpdate(update: ModUpdate): ByteArray
}
