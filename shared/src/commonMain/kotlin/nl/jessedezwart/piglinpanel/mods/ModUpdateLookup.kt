package nl.jessedezwart.piglinpanel.mods

import kotlin.time.Duration.Companion.hours
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.curseforge.ModFingerprintUpdateSource
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.modrinth.ModUpdateSource
import nl.jessedezwart.piglinpanel.search.ModSource
import okio.IOException

/**
 * The loader and Minecraft version updates are resolved against: the same jar has a different
 * newest version depending on which Minecraft version it is being asked about.
 */
internal fun ServerProfile.updateTarget(): String = "${modLoader.orEmpty()}:${mcVersion.orEmpty()}"

/**
 * Asks the repositories what each scanned jar is and what it updates to, remembering the answers
 * for a day so a rescan does not repeat the requests.
 */
internal class ModUpdateLookup(
    private val modrinthClient: ModUpdateSource,
    private val curseForgeClient: ModFingerprintUpdateSource?,
) {
    private val updatesCache = SuspendCache<String, ModUpdate>(ttl = 24.hours, maxEntries = 1000)

    /**
     * Looks each mod up on Modrinth by SHA-1 and, in the same pass, on CurseForge by fingerprint.
     * Both lookups are batched, so checking both costs a fixed handful of requests. A Modrinth
     * match wins where both repositories know a file; CurseForge covers the rest.
     */
    suspend fun applySuggestedUpdates(
        profile: ServerProfile,
        mods: MutableList<ModFile>,
        lookupHashesByPath: Map<String, ModFileHashes>,
        forceRefresh: Boolean,
    ) {
        val loader = profile.modLoader
        val gameVersion = profile.mcVersion
        val target = profile.updateTarget()
        val resolved = LinkedHashMap<String, ModUpdate>()

        val missingSha1s = mutableListOf<String>()
        for (sha1 in lookupHashesByPath.values.map { it.sha1 }.distinct()) {
            val cached = if (forceRefresh) null else updatesCache.getIfPresent(modrinthCacheKey(target, sha1))
            if (cached != null) resolved[sha1] = cached else missingSha1s += sha1
        }
        if (missingSha1s.isNotEmpty()) {
            val fetched = try {
                modrinthClient.findSuggestedUpdates(missingSha1s, loader, gameVersion)
            } catch (e: IOException) {
                emptyMap()
            }
            for (sha1 in missingSha1s) {
                val update = fetched[sha1] ?: continue
                resolved[sha1] = update
                updatesCache.put(modrinthCacheKey(target, sha1), update)
            }
        }

        // Every file is offered to CurseForge too, not only the ones Modrinth missed: the
        // fingerprint lookup is a single batched request either way, and which repositories carry
        // a mod is worth showing in its own right.
        val curseForgeResolved = LinkedHashMap<Long, ModUpdate>()
        val fingerprints = lookupHashesByPath.values.map { it.fingerprint }.distinct()
        val curseForge = curseForgeClient
        if (curseForge != null && curseForge.available && fingerprints.isNotEmpty()) {
            val missingFingerprints = mutableListOf<Long>()
            for (fingerprint in fingerprints) {
                val cached = if (forceRefresh) {
                    null
                } else {
                    updatesCache.getIfPresent(curseForgeCacheKey(target, fingerprint))
                }
                if (cached != null) curseForgeResolved[fingerprint] = cached else missingFingerprints += fingerprint
            }
            if (missingFingerprints.isNotEmpty()) {
                val fetched = try {
                    curseForge.findSuggestedUpdates(missingFingerprints, loader, gameVersion)
                } catch (e: IOException) {
                    emptyMap()
                }
                for (fingerprint in missingFingerprints) {
                    val update = fetched[fingerprint] ?: continue
                    curseForgeResolved[fingerprint] = update
                    updatesCache.put(curseForgeCacheKey(target, fingerprint), update)
                }
            }
        }

        for (i in mods.indices) {
            val mod = mods[i]
            val hashes = lookupHashesByPath[mod.relativePath] ?: continue
            val modrinth = resolved[hashes.sha1]
            val curse = curseForgeResolved[hashes.fingerprint]
            // Modrinth's match is the one that gets applied; CurseForge's is recorded either way.
            // Either repository calling it a library is enough; they categorise independently.
            val applied = (modrinth ?: curse)?.let { it.copy(library = it.library || curse?.library == true) }
            mods[i] = mod.copy(
                update = applied ?: mod.update,
                // Neither answering usually means the lookup failed, so what was known stays.
                sources = if (modrinth == null && curse == null) {
                    mod.sources
                } else {
                    buildSet {
                        if (modrinth != null) add(ModSource.MODRINTH)
                        if (curse != null) add(ModSource.CURSEFORGE)
                    }
                },
            )
        }
    }

    private fun modrinthCacheKey(target: String, sha1: String): String = "modrinth:$target:$sha1"

    private fun curseForgeCacheKey(target: String, fingerprint: Long): String = "curseforge:$target:$fingerprint"
}
