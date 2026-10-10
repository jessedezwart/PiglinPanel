package nl.jessedezwart.piglinpanel.mods

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import nl.jessedezwart.piglinpanel.mods.ManifestIds.sanitize
import nl.jessedezwart.piglinpanel.mods.ManifestIds.sanitizeDeps

/** The JSON manifests: Fabric's `fabric.mod.json` and Quilt's `quilt.mod.json`. */
internal object ModJsonManifests {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `fabric.mod.json`: `depends`/`suggests` are objects keyed by mod id, valued by version range. */
    fun parseFabric(bytes: ByteArray): ModJarMetadata? {
        val root = parseJsonObject(bytes) ?: return null
        val modId = root.string("id") ?: return null
        return ModJarMetadata(
            modId = modId.lowercase(),
            name = root.string("name"),
            version = root.string("version"),
            provides = sanitize(root.stringList("provides")),
            requires = sanitizeDeps(root.dependencyObject("depends")),
            optional = sanitizeDeps(root.dependencyObject("suggests") + root.dependencyObject("recommends")),
            side = environment(root.string("environment")),
            library = hasLibraryBadge((root["custom"] as? JsonObject)?.get("modmenu")),
        )
    }

    /** Mod Menu's `"modmenu": { "badges": ["library"] }`, the flag it uses to hide libraries. */
    private fun hasLibraryBadge(modmenu: JsonElement?): Boolean =
        (modmenu as? JsonObject)?.stringList("badges")?.any { it.equals("library", ignoreCase = true) } == true

    /** Fabric's and Quilt's `environment`: `client`, `server`/`dedicated_server`, or `*` for both. */
    private fun environment(value: String?): ModSide = when (value?.lowercase()) {
        "client" -> ModSide.CLIENT
        "server", "dedicated_server" -> ModSide.SERVER
        "*" -> ModSide.BOTH
        else -> ModSide.UNKNOWN
    }

    /** `quilt.mod.json`: everything lives under `quilt_loader`, and `depends` is an array. */
    fun parseQuilt(bytes: ByteArray): ModJarMetadata? {
        val root = parseJsonObject(bytes) ?: return null
        val loader = (root["quilt_loader"] as? JsonObject) ?: return null
        val modId = loader.string("id") ?: return null
        val depends = loader.dependencyArray("depends")
        return ModJarMetadata(
            modId = modId.lowercase(),
            name = (loader["metadata"] as? JsonObject)?.string("name"),
            version = loader.string("version"),
            provides = sanitize(loader.dependencyArray("provides").map { it.first.id }),
            requires = sanitizeDeps(depends.filterNot { it.second }.map { it.first }),
            optional = sanitizeDeps(depends.filter { it.second }.map { it.first }),
            side = environment((root["minecraft"] as? JsonObject)?.string("environment")),
            // Quilt puts custom sections at the root of the file rather than under `custom`.
            library = hasLibraryBadge(root["modmenu"]),
        )
    }

    private fun parseJsonObject(bytes: ByteArray): JsonObject? =
        try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    /**
     * A Fabric dependency table: `{"fabric-api": ">=0.92.0"}`, where the value may also be an
     * array of predicates. An array means "any of these will do", which is what `||` says in the
     * syntax [ModVersionRange] reads, so the alternatives are joined with it.
     */
    private fun JsonObject.dependencyObject(key: String): List<ModDependencyRef> =
        (this[key] as? JsonObject)?.map { (id, value) -> ModDependencyRef(id, versionRange(value)) }.orEmpty()

    /** A version range written either as one predicate or as a list of alternatives. */
    private fun versionRange(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.content.takeIf { it.isNotBlank() }
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" || ")
        else -> null
    }

    /**
     * Quilt entries are either a bare id string or an object with `id`, a `versions` range and an
     * `optional` flag.
     */
    private fun JsonObject.dependencyArray(key: String): List<Pair<ModDependencyRef, Boolean>> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> ModDependencyRef(element.content) to false
                is JsonObject -> {
                    val id = element.string("id") ?: return@mapNotNull null
                    val optional = (element["optional"] as? JsonPrimitive)?.content == "true"
                    ModDependencyRef(id, versionRange(element["versions"])) to optional
                }
                else -> null
            }
        }
}
