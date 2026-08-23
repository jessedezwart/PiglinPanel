package nl.jessedezwart.piglinpanel.desktop.ui.mods

/** The loaders a search or compatibility check can be narrowed to; null means "any". */
internal val LOADERS: List<String?> = listOf(null, "fabric", "forge", "neoforge", "quilt")

internal fun loaderLabel(loader: String?) = loader?.replaceFirstChar { it.uppercase() } ?: "Any"
