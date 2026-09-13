package nl.jessedezwart.piglinpanel.mods

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    /** Ids the loader refuses to start without. */
    val requires: List<String> = emptyList(),
    /** Ids this mod can use but does not need. */
    val optional: List<String> = emptyList(),
    /** Where the jar says it runs; [ModSide.UNKNOWN] when it says nothing. */
    val side: ModSide = ModSide.UNKNOWN,
) {
    /** Every id this jar answers to. */
    fun ids(): List<String> = listOf(modId) + provides
}

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
     * Ids supplied by the loader or the game itself. They are never satisfied by a jar in the mods
     * folder, so treating them as dependencies would flag every mod as broken.
     */
    private val PROVIDED_BY_PLATFORM = setOf(
        "minecraft",
        "java",
        "fabricloader",
        "fabric-loader",
        "quilt_loader",
        "quiltloader",
        "quilt_base",
        "forge",
        "neoforge",
        "fml",
        "mcp",
    )

    /**
     * How deep to look for bundled jars. Mods ship their libraries inside themselves (Fabric's
     * `META-INF/jars`, NeoForge's `META-INF/jarjar`), and those libraries can bundle jars of their
     * own — the `fabric` modules inside Forgified Fabric API sit one level further down. Two levels
     * covers what is seen in practice without turning a scan into a full archive walk.
     */
    private const val MAX_NESTING = 2

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The ids a jar answers to include the ones it bundles: a dependency that ships inside another
     * mod's jar is present as far as the loader is concerned, so reporting it missing would be a
     * lie — and the commonest one, since libraries are usually shipped this way.
     */
    fun read(jarBytes: ByteArray): ModJarMetadata? {
        val declared = readDeclared(jarBytes) ?: return null
        val known = declared.ids().toMutableSet()
        val bundled = bundledIds(jarBytes, MAX_NESTING).filter { known.add(it) }
        return declared.copy(
            provides = declared.provides + bundled,
            side = sideAcrossManifests(jarBytes, declared.side),
        )
    }

    /** Each loader's manifest, in the order a jar's identity is taken from them. */
    private val MANIFESTS: List<Pair<String, (ByteArray) -> ModJarMetadata?>> = listOf(
        "fabric.mod.json" to ::parseFabric,
        "quilt.mod.json" to ::parseQuilt,
        "META-INF/neoforge.mods.toml" to ::parseModsToml,
        "META-INF/mods.toml" to ::parseModsToml,
    )

    private fun readDeclared(jarBytes: ByteArray): ModJarMetadata? =
        MANIFESTS.firstNotNullOfOrNull { (entry, parse) -> readZipEntry(jarBytes, entry)?.let(parse) }

    /**
     * The side to believe for a jar shipping more than one loader's manifest.
     *
     * A merged fabric+neoforge jar carries both, and in practice only one of them is filled in
     * honestly: Build Guide calls itself client-side only in its `neoforge.mods.toml` while its
     * `fabric.mod.json` still says `"environment": "*"`. A definite CLIENT or SERVER anywhere in the
     * jar therefore beats a BOTH that another manifest left at its default.
     */
    private fun sideAcrossManifests(jarBytes: ByteArray, primary: ModSide): ModSide {
        if (primary.isNoteworthy()) return primary
        val sides = MANIFESTS.mapNotNull { (entry, parse) -> readZipEntry(jarBytes, entry)?.let(parse)?.side }
        return sides.firstOrNull { it.isNoteworthy() } ?: primary
    }

    /**
     * Ids declared by the jars bundled inside [jarBytes], recursively. What those jars themselves
     * require is deliberately ignored: a bundled jar travels with whatever it needs.
     */
    private fun bundledIds(jarBytes: ByteArray, depth: Int): List<String> {
        if (depth <= 0) return emptyList()
        return readZipEntries(jarBytes, ::isBundledJar)
            .flatMap { nested -> readDeclared(nested)?.ids().orEmpty() + bundledIds(nested, depth - 1) }
            .distinct()
    }

    private fun isBundledJar(entryName: String): Boolean =
        entryName.endsWith(".jar", ignoreCase = true) &&
            (
                entryName.startsWith("META-INF/jars/", ignoreCase = true) ||
                    entryName.startsWith("META-INF/jarjar/", ignoreCase = true)
                )

    private fun ByteArray.text(): String = decodeToString()

    private fun sanitize(ids: Iterable<String>): List<String> =
        ids.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it !in PROVIDED_BY_PLATFORM }
            .distinct()

    /** `fabric.mod.json`: `depends`/`suggests` are objects keyed by mod id. */
    private fun parseFabric(bytes: ByteArray): ModJarMetadata? {
        val root = parseJsonObject(bytes) ?: return null
        val modId = root.string("id") ?: return null
        return ModJarMetadata(
            modId = modId.lowercase(),
            name = root.string("name"),
            version = root.string("version"),
            provides = sanitize(root.stringList("provides")),
            requires = sanitize(root.objectKeys("depends")),
            optional = sanitize(root.objectKeys("suggests") + root.objectKeys("recommends")),
            side = environment(root.string("environment")),
        )
    }

    /**
     * What a `mods.toml` amounts to, most specific claim first: the mod's own `side`, then a
     * `clientSideOnly`/`serverSideOnly` flag, then the side its platform dependency was scoped to.
     *
     * The order matters where a file states both, which happens: Distant Horizons carries a stale
     * `clientSideOnly = "true"` next to the `side = "BOTH"` that matches the server support it has
     * since gained.
     */
    private fun modSide(modsSide: String?, declared: ModSide, platform: ModSide): ModSide =
        when (modsSide?.uppercase()) {
            "CLIENT" -> ModSide.CLIENT
            "SERVER" -> ModSide.SERVER
            "BOTH" -> ModSide.BOTH
            else -> if (declared != ModSide.UNKNOWN) declared else platform
        }

    /** Fabric's and Quilt's `environment`: `client`, `server`/`dedicated_server`, or `*` for both. */
    private fun environment(value: String?): ModSide = when (value?.lowercase()) {
        "client" -> ModSide.CLIENT
        "server", "dedicated_server" -> ModSide.SERVER
        "*" -> ModSide.BOTH
        else -> ModSide.UNKNOWN
    }

    /** `quilt.mod.json`: everything lives under `quilt_loader`, and `depends` is an array. */
    private fun parseQuilt(bytes: ByteArray): ModJarMetadata? {
        val root = parseJsonObject(bytes) ?: return null
        val loader = (root["quilt_loader"] as? JsonObject) ?: return null
        val modId = loader.string("id") ?: return null
        val depends = loader.dependencyArray("depends")
        return ModJarMetadata(
            modId = modId.lowercase(),
            name = (loader["metadata"] as? JsonObject)?.string("name"),
            version = loader.string("version"),
            provides = sanitize(loader.dependencyArray("provides").map { it.first }),
            requires = sanitize(depends.filterNot { it.second }.map { it.first }),
            optional = sanitize(depends.filter { it.second }.map { it.first }),
            side = environment((root["minecraft"] as? JsonObject)?.string("environment")),
        )
    }

    private fun parseJsonObject(bytes: ByteArray): JsonObject? =
        try {
            json.parseToJsonElement(bytes.text()) as? JsonObject
        } catch (e: Exception) {
            null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    private fun JsonObject.objectKeys(key: String): List<String> =
        (this[key] as? JsonObject)?.keys?.toList().orEmpty()

    /** Quilt entries are either a bare id string or an object with `id` and an `optional` flag. */
    private fun JsonObject.dependencyArray(key: String): List<Pair<String, Boolean>> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content to false
                is JsonObject -> {
                    val id = element.string("id") ?: return@mapNotNull null
                    val optional = (element["optional"] as? JsonPrimitive)?.content == "true"
                    id to optional
                }
                else -> null
            }
        }

    /**
     * `mods.toml` (Forge and NeoForge), read line by line rather than with a full TOML parser: the
     * only shapes that matter are `[[mods]]` and `[[dependencies.<parent>]]` tables holding flat
     * key/value pairs, and a mis-parse here would cost a spurious warning, not a broken server.
     */
    private fun parseModsToml(bytes: ByteArray): ModJarMetadata? {
        // One jar may declare several `[[mods]]`; the loader registers every one of them, so the
        // extras become ids this jar provides rather than being dropped.
        val modIds = mutableListOf<String>()
        var name: String? = null
        var version: String? = null
        val required = mutableListOf<String>()
        val optional = mutableListOf<String>()

        var inMods = false
        var inDependencies = false
        // A dependency table's fields arrive one per line, so they are collected until the next header.
        var dependencyId: String? = null
        var dependencyRequired = true
        var dependencySide: String? = null

        // What the file states outright, and what its own platform dependency implies.
        var modsSide: String? = null
        var declaredSide = ModSide.UNKNOWN
        var platformSide = ModSide.UNKNOWN

        fun flushDependency() {
            val id = dependencyId ?: return
            // A mod that scopes its own dependency on Minecraft or the loader to one side runs on
            // that side: there is no per-mod side key in mods.toml, and this is what the mods that
            // bother to say so actually write. `BOTH` is the value every generated template ships
            // with, so it is read as silence rather than as a claim.
            if (platformSide == ModSide.UNKNOWN && id.trim().lowercase() in PROVIDED_BY_PLATFORM) {
                platformSide = when (dependencySide?.uppercase()) {
                    "CLIENT" -> ModSide.CLIENT
                    "SERVER" -> ModSide.SERVER
                    else -> ModSide.UNKNOWN
                }
            }
            if (dependencyRequired) required += id else optional += id
            dependencyId = null
            dependencyRequired = true
            dependencySide = null
        }

        for (raw in bytes.text().lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[")) {
                flushDependency()
                val header = line.trim('[', ']').trim()
                inMods = header == "mods"
                inDependencies = header.startsWith("dependencies")
                continue
            }

            val key = line.substringBefore('=', "").trim().lowercase()
            if (key.isEmpty()) continue
            val value = line.substringAfter('=').trim().trim('"', '\'')

            when {
                inMods && key == "modid" -> modIds += value
                inMods && key == "displayname" && name == null -> name = value
                inMods && key == "version" && version == null -> version = value
                inDependencies && key == "modid" -> {
                    flushDependency()
                    dependencyId = value
                }
                // Forge spells it `mandatory = true`; NeoForge uses `type = "required"`.
                inDependencies && key == "mandatory" -> dependencyRequired = value.equals("true", ignoreCase = true)
                inDependencies && key == "type" -> dependencyRequired = value.equals("required", ignoreCase = true)
                inDependencies && key == "side" -> dependencySide = value
                // NeoForge's outright statements of side. `side` on the mod itself is the newest
                // and most specific of them; `clientSideOnly` is written both at the root of the
                // file and inside the mod's own table, so both places are read.
                inMods && key == "side" -> modsSide = value
                !inDependencies && key == "clientsideonly" ->
                    if (value.equals("true", ignoreCase = true)) declaredSide = ModSide.CLIENT
                !inDependencies && key == "serversideonly" ->
                    if (value.equals("true", ignoreCase = true)) declaredSide = ModSide.SERVER
            }
        }
        flushDependency()

        val ids = sanitize(modIds)
        val id = ids.firstOrNull() ?: return null
        return ModJarMetadata(
            modId = id,
            name = name,
            version = version,
            provides = ids.drop(1),
            // A jar declaring several mods also declares their dependencies on each other; those
            // are satisfied from within, so they are not requirements of the jar as a whole.
            requires = sanitize(required) - ids.toSet(),
            optional = sanitize(optional) - ids.toSet(),
            side = modSide(modsSide, declaredSide, platformSide),
        )
    }
}
