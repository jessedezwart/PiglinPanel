package nl.jessedezwart.piglinpanel.mods

/** The id filtering every manifest format shares. */
internal object ManifestIds {
    /**
     * Ids supplied by the loader or the game itself. They are never satisfied by a jar in the mods
     * folder, so treating them as dependencies would flag every mod as broken.
     */
    val PROVIDED_BY_PLATFORM = setOf(
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

    fun sanitize(ids: Iterable<String>): List<String> =
        ids.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it !in PROVIDED_BY_PLATFORM }
            .distinct()

    /**
     * The same filtering for dependency entries. Where a manifest names one id twice — Forge files
     * that repeat a dependency per side do — the first range wins, since a second entry qualifying
     * the same id is a claim this cannot combine without inventing a syntax for it.
     */
    fun sanitizeDeps(deps: Iterable<ModDependencyRef>): List<ModDependencyRef> =
        deps.map { it.copy(id = it.id.trim().lowercase(), range = it.range?.trim()?.takeIf { r -> r.isNotEmpty() }) }
            .filter { it.id.isNotEmpty() && it.id !in PROVIDED_BY_PLATFORM }
            .distinctBy { it.id }
}
