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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(jarBytes: ByteArray): ModJarMetadata? =
        readZipEntry(jarBytes, "fabric.mod.json")?.let(::parseFabric)
            ?: readZipEntry(jarBytes, "quilt.mod.json")?.let(::parseQuilt)
            ?: readZipEntry(jarBytes, "META-INF/neoforge.mods.toml")?.let(::parseModsToml)
            ?: readZipEntry(jarBytes, "META-INF/mods.toml")?.let(::parseModsToml)

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
        )
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
        var modId: String? = null
        var name: String? = null
        var version: String? = null
        val required = mutableListOf<String>()
        val optional = mutableListOf<String>()

        var inMods = false
        var inDependencies = false
        // A dependency table's fields arrive one per line, so they are collected until the next header.
        var dependencyId: String? = null
        var dependencyRequired = true

        fun flushDependency() {
            val id = dependencyId ?: return
            if (dependencyRequired) required += id else optional += id
            dependencyId = null
            dependencyRequired = true
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
                inMods && key == "modid" && modId == null -> modId = value
                inMods && key == "displayname" && name == null -> name = value
                inMods && key == "version" && version == null -> version = value
                inDependencies && key == "modid" -> {
                    flushDependency()
                    dependencyId = value
                }
                // Forge spells it `mandatory = true`; NeoForge uses `type = "required"`.
                inDependencies && key == "mandatory" -> dependencyRequired = value.equals("true", ignoreCase = true)
                inDependencies && key == "type" -> dependencyRequired = value.equals("required", ignoreCase = true)
            }
        }
        flushDependency()

        val id = modId ?: return null
        return ModJarMetadata(
            modId = id.lowercase(),
            name = name,
            version = version,
            requires = sanitize(required),
            optional = sanitize(optional),
        )
    }
}
