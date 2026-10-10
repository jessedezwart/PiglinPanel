package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.mods.ManifestIds.PROVIDED_BY_PLATFORM
import nl.jessedezwart.piglinpanel.mods.ManifestIds.sanitize
import nl.jessedezwart.piglinpanel.mods.ManifestIds.sanitizeDeps

internal object ModsToml {
    /**
     * `mods.toml` (Forge and NeoForge), read line by line rather than with a full TOML parser: the
     * only shapes that matter are `[[mods]]` and `[[dependencies.<parent>]]` tables holding flat
     * key/value pairs, and a mis-parse here would cost a spurious warning, not a broken server.
     */
    fun parse(bytes: ByteArray): ModJarMetadata? {
        // One jar may declare several `[[mods]]`; the loader registers every one of them, so the
        // extras become ids this jar provides rather than being dropped.
        val modIds = mutableListOf<String>()
        // Aliases a mod answers to, e.g. Forgified Fabric API's `fabric-api` for Fabric mods run
        // through Connector.
        val providedIds = mutableListOf<String>()
        var name: String? = null
        var version: String? = null
        val required = mutableListOf<ModDependencyRef>()
        val optional = mutableListOf<ModDependencyRef>()

        var inMods = false
        var inDependencies = false
        // A dependency table's fields arrive one per line, so they are collected until the next header.
        var dependencyId: String? = null
        var dependencyRequired = true
        var dependencySide: String? = null
        var dependencyRange: String? = null

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
            val dependency = ModDependencyRef(id, dependencyRange)
            if (dependencyRequired) required += dependency else optional += dependency
            dependencyId = null
            dependencyRequired = true
            dependencySide = null
            dependencyRange = null
        }

        for (raw in bytes.decodeToString().lineSequence()) {
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
                // Only the single-line `["a", "b"]` form, which is the one seen in practice.
                inMods && key == "provides" ->
                    providedIds += value.removePrefix("[").removeSuffix("]").split(',').map { it.trim().trim('"', '\'') }
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
                inDependencies && key == "versionrange" -> dependencyRange = value
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
            provides = (ids.drop(1) + sanitize(providedIds)).distinct() - id,
            // A jar declaring several mods also declares their dependencies on each other; those
            // are satisfied from within, so they are not requirements of the jar as a whole.
            requires = sanitizeDeps(required).filterNot { it.id in ids },
            optional = sanitizeDeps(optional).filterNot { it.id in ids },
            side = modSide(modsSide, declaredSide, platformSide),
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
}
