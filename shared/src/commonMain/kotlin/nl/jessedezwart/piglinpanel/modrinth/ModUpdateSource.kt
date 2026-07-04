package nl.jessedezwart.piglinpanel.modrinth

import nl.jessedezwart.piglinpanel.model.ModUpdate

/** The subset of the Modrinth API that mod scanning/updating needs; fakeable in tests. */
interface ModUpdateSource {
    suspend fun findSuggestedUpdates(sha1s: List<String>): Map<String, ModUpdate>

    suspend fun downloadUpdate(update: ModUpdate): ByteArray
}
