package nl.jessedezwart.piglinpanel.modrinth

import nl.jessedezwart.piglinpanel.model.ModUpdate

/** The subset of the Modrinth API that mod scanning/updating needs; fakeable in tests. */
interface ModUpdateSource {
    /**
     * [loader] and [gameVersion] pin what counts as an update for the whole pack. Left out, each
     * file is matched against the loaders and game versions its *own* current version was
     * published for, which lets one mod drift onto a different Minecraft version than the mods
     * beside it — and a pack split across two Minecraft versions is a broken pack.
     */
    suspend fun findSuggestedUpdates(
        sha1s: List<String>,
        loader: String? = null,
        gameVersion: String? = null,
    ): Map<String, ModUpdate>

    suspend fun downloadUpdate(update: ModUpdate): ByteArray

    /** Release notes of [update]'s latest version, or null when the repository has none. */
    suspend fun changelog(update: ModUpdate): String? = update.changelog
}
