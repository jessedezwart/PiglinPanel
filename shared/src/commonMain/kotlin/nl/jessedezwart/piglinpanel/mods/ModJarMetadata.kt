package nl.jessedezwart.piglinpanel.mods

import kotlinx.serialization.Serializable

/**
 * One entry of a jar's dependency list: the id it names, and the versions of it that will do.
 *
 * The range is kept verbatim rather than parsed here, because each loader writes its own syntax
 * and only [ModVersionRange] needs to tell them apart. A null range means the manifest named the
 * id without qualifying it, which every loader reads as "any version".
 */
@Serializable
data class ModDependencyRef(
    val id: String,
    val range: String? = null,
)

/**
 * What a mod jar says about itself: the id the loader knows it by, and the ids it needs present.
 *
 * This comes from the jar's own manifest rather than from Modrinth/CurseForge, because the loader
 * enforces exactly these ids — including for mods that no repository has ever indexed.
 */
@Serializable
data class ModJarMetadata(
    /** The loader-visible mod id, e.g. `fabric-api`. */
    val modId: String,
    /** Human-readable name, when the manifest carries one. */
    val name: String? = null,
    val version: String? = null,
    /** Ids this jar satisfies besides [modId], e.g. Fabric API's `fabric`. */
    val provides: List<String> = emptyList(),
    /** Ids the loader refuses to start without, with the versions of each that satisfy it. */
    val requires: List<ModDependencyRef> = emptyList(),
    /** Ids this mod can use but does not need. */
    val optional: List<ModDependencyRef> = emptyList(),
    /** Where the jar says it runs; [ModSide.UNKNOWN] when it says nothing. */
    val side: ModSide = ModSide.UNKNOWN,
    /**
     * Whether the jar calls itself a library, via Mod Menu's `library` badge. Only Fabric and Quilt
     * have a place to say so; false means "not said", not "not a library".
     */
    val library: Boolean = false,
) {
    /** Every id this jar answers to. */
    fun ids(): List<String> = listOf(modId) + provides
}

/** A manifest's path inside a jar, and the parser for it. */
private typealias Manifest = Pair<String, (ByteArray) -> ModJarMetadata?>

/**
 * Extracts [ModJarMetadata] from a mod jar's bytes, trying each loader's manifest in turn.
 *
 * Returns null when the jar carries no manifest this understands — a jar that is not a mod, or one
 * built for a loader without support here. Callers must treat null as "unknown", never as
 * "no dependencies": acting on absent metadata would mean warning about nothing and auto-enabling
 * nothing, which is the safe direction.
 */
object ModJarMetadataReader {
    /**
     * How deep to look for bundled jars. Mods ship their libraries inside themselves (Fabric's
     * `META-INF/jars`, NeoForge's `META-INF/jarjar`), and those libraries can bundle jars of their
     * own — the `fabric` modules inside Forgified Fabric API sit one level further down. Two levels
     * covers what is seen in practice without turning a scan into a full archive walk.
     */
    private const val MAX_NESTING = 2

    /**
     * The ids a jar answers to include the ones it bundles: a dependency that ships inside another
     * mod's jar is present as far as the loader is concerned, so reporting it missing would be a
     * lie — and the commonest one, since libraries are usually shipped this way.
     *
     * [loader] is the server's mod loader (`fabric`, `quilt`, `forge`, `neoforge`), when known. A
     * merged jar declares different dependencies per loader — Collective's `fabric.mod.json` needs
     * `fabric-api`, its `neoforge.mods.toml` does not — and only the running loader's are real.
     */
    fun read(jarBytes: ByteArray, loader: String? = null): ModJarMetadata? {
        val manifests = manifestsFor(loader)
        val declared = readDeclared(jarBytes, manifests) ?: return null
        val known = declared.ids().toMutableSet()
        val bundled = bundledIds(jarBytes, manifests, MAX_NESTING).filter { known.add(it) }
        return declared.copy(
            provides = declared.provides + bundled,
            side = sideAcrossManifests(jarBytes, manifests, declared.side),
        )
    }

    private val FABRIC: Manifest = "fabric.mod.json" to ModJsonManifests::parseFabric
    private val QUILT: Manifest = "quilt.mod.json" to ModJsonManifests::parseQuilt
    private val NEOFORGE: Manifest = "META-INF/neoforge.mods.toml" to ModsToml::parse
    private val FORGE: Manifest = "META-INF/mods.toml" to ModsToml::parse

    /**
     * Each loader's manifest, in the order a jar's identity is taken from them: the running loader's
     * own first, then its close relatives, then the rest. The others stay as fallbacks because a
     * jar with only a foreign manifest can still load — Quilt runs Fabric mods, and Connector runs
     * them on NeoForge.
     */
    private fun manifestsFor(loader: String?): List<Manifest> = when (loader?.lowercase()) {
        "neoforge" -> listOf(NEOFORGE, FORGE, FABRIC, QUILT)
        "forge" -> listOf(FORGE, NEOFORGE, FABRIC, QUILT)
        "quilt" -> listOf(QUILT, FABRIC, NEOFORGE, FORGE)
        else -> listOf(FABRIC, QUILT, NEOFORGE, FORGE)
    }

    private fun readDeclared(jarBytes: ByteArray, manifests: List<Manifest>): ModJarMetadata? =
        manifests.firstNotNullOfOrNull { (entry, parse) -> readZipEntry(jarBytes, entry)?.let(parse) }

    /**
     * The side to believe for a jar shipping more than one loader's manifest.
     *
     * A merged fabric+neoforge jar carries both, and in practice only one of them is filled in
     * honestly: Build Guide calls itself client-side only in its `neoforge.mods.toml` while its
     * `fabric.mod.json` still says `"environment": "*"`. A definite CLIENT or SERVER anywhere in the
     * jar therefore beats a BOTH that another manifest left at its default.
     */
    private fun sideAcrossManifests(jarBytes: ByteArray, manifests: List<Manifest>, primary: ModSide): ModSide {
        if (primary.isNoteworthy()) return primary
        val sides = manifests.mapNotNull { (entry, parse) -> readZipEntry(jarBytes, entry)?.let(parse)?.side }
        return sides.firstOrNull { it.isNoteworthy() } ?: primary
    }

    /**
     * Ids declared by the jars bundled inside [jarBytes], recursively. What those jars themselves
     * require is deliberately ignored: a bundled jar travels with whatever it needs.
     */
    private fun bundledIds(jarBytes: ByteArray, manifests: List<Manifest>, depth: Int): List<String> {
        if (depth <= 0) return emptyList()
        return readZipEntries(jarBytes, ::isBundledJar)
            .flatMap { nested ->
                readDeclared(nested, manifests)?.ids().orEmpty() + bundledIds(nested, manifests, depth - 1)
            }
            .distinct()
    }

    private fun isBundledJar(entryName: String): Boolean =
        entryName.endsWith(".jar", ignoreCase = true) &&
            (
                entryName.startsWith("META-INF/jars/", ignoreCase = true) ||
                    entryName.startsWith("META-INF/jarjar/", ignoreCase = true)
                )
}
