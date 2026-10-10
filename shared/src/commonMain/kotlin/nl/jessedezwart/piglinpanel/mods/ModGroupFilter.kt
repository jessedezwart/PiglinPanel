package nl.jessedezwart.piglinpanel.mods

import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile

/** What the installed-mods table is ordered by. */
enum class ModSortKey { NAME, FILE, SIDE, DOWNLOADS, SIZE, DATE }

/** Which mods the installed-mods table shows. Every option narrows the list; the defaults show all. */
data class ModGroupFilter(
    /** Matched against a mod's name and every one of its file names; see [ModGroup.matches]. */
    val query: String = "",
    /** Narrows the list to mods with no enabled jar left, for finding something to re-activate. */
    val disabledOnly: Boolean = false,
    /** Narrows the list to libraries nothing installed uses, for clearing out leftovers. */
    val unusedLibrariesOnly: Boolean = false,
    /** Narrows the list to mods with an update to apply, ignored ones excluded. */
    val updatableOnly: Boolean = false,
    val side: ModSide? = null,
) {
    /** Whether [group] passes, where [updatableKeys] are the keys of the mods with an update to apply. */
    fun matches(group: ModGroup, updatableKeys: Set<String>): Boolean =
        (!disabledOnly || group.active == null) &&
            (!unusedLibrariesOnly || group.unusedLibrary) &&
            (!updatableOnly || group.key in updatableKeys) &&
            (side == null || group.side == side) &&
            group.matches(query)
}

/** Whether a mod answers to [query], by its own name or by any of its file names. */
fun ModGroup.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return displayName.contains(needle, ignoreCase = true) ||
        active?.fileName?.contains(needle, ignoreCase = true) == true ||
        archived.any { it.fileName.contains(needle, ignoreCase = true) }
}

fun modGroupComparator(key: ModSortKey, ascending: Boolean): Comparator<ModGroup> {
    val base: Comparator<ModGroup> = when (key) {
        ModSortKey.NAME -> compareBy { it.displayName.lowercase() }
        ModSortKey.FILE -> compareBy { (it.active ?: it.archived.firstOrNull())?.fileName?.lowercase() ?: "" }
        // Named mods first, so ordering by side gathers the one-sided ones rather than the silent ones.
        ModSortKey.SIDE -> compareBy<ModGroup> { it.side == ModSide.UNKNOWN }.thenBy { it.side.name }
        ModSortKey.DOWNLOADS -> compareBy { it.downloads }
        ModSortKey.SIZE -> compareBy { (it.active ?: it.archived.firstOrNull())?.size ?: 0L }
        ModSortKey.DATE -> compareBy { it.modifiedAt?.toEpochMilliseconds() ?: 0L }
    }
    return if (ascending) base else base.reversed()
}

/** Whether the user chose to skip the exact update [ModGroup.update] is currently offering. */
fun ModGroup.updateIgnored(profile: ServerProfile): Boolean {
    val latest = update?.latestVersionId ?: return false
    return profile.ignoredUpdates[key] == latest
}
