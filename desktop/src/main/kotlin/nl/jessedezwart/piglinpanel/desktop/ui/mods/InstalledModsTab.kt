package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ModGroupFilter
import nl.jessedezwart.piglinpanel.mods.ModSide
import nl.jessedezwart.piglinpanel.mods.ModSortKey
import nl.jessedezwart.piglinpanel.mods.modGroupComparator
import nl.jessedezwart.piglinpanel.mods.updateIgnored

/** A pending disable/delete that other installed mods depend on, held until the user confirms it. */
private data class Breakage(
    val action: String,
    val title: String,
    val dependents: List<String>,
    val proceed: () -> Unit,
)

@Composable
internal fun InstalledModsTab(state: ModsScreenState, profile: ServerProfile) {
    val busy = state.busy
    val groups = state.groups
    val updatable = state.updatable

    /** Set while a removal is waiting on confirmation because other mods depend on the target. */
    var breakage by remember(profile.id) { mutableStateOf<Breakage?>(null) }

    var filter by remember(profile.id) { mutableStateOf(ModGroupFilter()) }

    /** Off by default: the disabled versions are history, and the table is about what is running. */
    var showDisabled by remember(profile.id) { mutableStateOf(false) }
    var sortKey by remember(profile.id) { mutableStateOf(ModSortKey.NAME) }
    var ascending by remember(profile.id) { mutableStateOf(true) }

    /** More than one mod folder means a bare file name no longer says which jar is meant. */
    val showFolder = profile.modFolders.size > 1

    val fullyDisabled = remember(groups) { groups.filter { it.active == null } }
    val unusedLibraries = remember(groups) { groups.filter { it.unusedLibrary } }
    val updatableKeys = remember(updatable) { updatable.map { it.first.key }.toSet() }

    val rows = remember(groups, filter, showDisabled, updatableKeys, sortKey, ascending) {
        val ordered = groups
            .filter { filter.matches(it, updatableKeys) }
            .sortedWith(modGroupComparator(sortKey, ascending))
        buildList {
            for (group in ordered) {
                group.active?.let { add(ModRow(group, it, active = true)) }
                // A mod with nothing enabled has only its disabled versions to offer, so those stay
                // visible however the filter is set — otherwise the row could not be acted on.
                if (showDisabled || group.active == null) {
                    group.archived.forEach { add(ModRow(group, it, active = false)) }
                }
            }
        }
    }

    /**
     * Runs a disable/delete, first asking about any mods it would break. The check is skipped when
     * nothing is known to depend on the target — which includes jars whose manifest could not be
     * read, so this warns about what it can see rather than guaranteeing nothing else breaks.
     */
    fun removing(action: String, mod: ModFile, title: String, run: () -> Unit) {
        val dependents = state.dependentsBrokenBy(mod)
        if (dependents.isEmpty()) run() else breakage = Breakage(action, title, dependents, run)
    }

    breakage?.let { pending ->
        AlertDialog(
            onDismissRequest = { breakage = null },
            title = { Text("${pending.action} ${pending.title}?") },
            text = {
                Text(
                    "These mods require it and will fail to load:\n\n" +
                        pending.dependents.joinToString("\n") { "  • $it" },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val proceed = pending.proceed
                        breakage = null
                        proceed()
                    },
                ) {
                    Text("${pending.action} anyway")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { breakage = null }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        UpdateCheckButtons(task = state.task, rescan = state::rescan)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { filter = filter.copy(query = it) },
                placeholder = { Text("Filter by name or file", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(240.dp),
            )
            FilterChip(
                selected = filter.updatableOnly,
                onClick = { filter = filter.copy(updatableOnly = !filter.updatableOnly) },
                label = { Text(if (updatable.isEmpty()) "Updatable" else "Updatable (${updatable.size})") },
            )
            FilterChip(
                selected = showDisabled,
                onClick = { showDisabled = !showDisabled },
                label = { Text("Show disabled versions") },
            )
            FilterChip(
                selected = filter.disabledOnly,
                onClick = { filter = filter.copy(disabledOnly = !filter.disabledOnly) },
                label = {
                    Text(if (fullyDisabled.isEmpty()) "Fully disabled" else "Fully disabled (${fullyDisabled.size})")
                },
            )
            FilterChip(
                selected = filter.unusedLibrariesOnly,
                onClick = { filter = filter.copy(unusedLibrariesOnly = !filter.unusedLibrariesOnly) },
                label = {
                    Text(if (unusedLibraries.isEmpty()) "Unused libraries" else "Unused libraries (${unusedLibraries.size})")
                },
            )
            SideFilterChip("Client-only", ModSide.CLIENT, filter.side) { filter = filter.copy(side = it) }
            SideFilterChip("Server-only", ModSide.SERVER, filter.side) { filter = filter.copy(side = it) }
            Text(
                "${rows.distinctBy { it.group.key }.size} of ${groups.size} mods",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.progress?.let { ScanProgressBar(it) }
        if (groups.isEmpty() && !busy) {
            Text(
                "No mods found in this server's mod folders.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (rows.isEmpty() && !busy) {
            Text("No mods match the current filters.", style = MaterialTheme.typography.bodyMedium)
        }
        if (rows.isNotEmpty()) {
            TableHeader(
                sortKey = sortKey,
                ascending = ascending,
                onSort = { key ->
                    if (key == sortKey) ascending = !ascending else { sortKey = key; ascending = true }
                },
            )
            HorizontalDivider()
        }
        LazyColumn {
            items(rows, key = { it.file.relativePath }) { row ->
                ModTableRow(
                    row = row,
                    showFolder = showFolder,
                    enabled = !busy,
                    ignored = row.group.updateIgnored(profile),
                    onIgnore = { ignore -> state.ignoreUpdate(row.group, ignore) },
                    // Through the same planner as a batch: a single update can strand a dependent
                    // just as well, and one mod is the cheapest possible plan.
                    onApplyUpdate = { mod -> state.applyUpdates(listOf(row.group to mod)) },
                    onDisable = { mod -> removing("Disable", mod, row.group.displayName) { state.disable(mod) } },
                    onActivate = state::activate,
                    onDelete = { mod -> removing("Delete", mod, row.group.displayName) { state.delete(mod) } },
                )
            }
        }
    }
}

/** Narrows the table to mods that run on [side] alone; clicking it again lifts the narrowing. */
@Composable
private fun SideFilterChip(label: String, side: ModSide, selected: ModSide?, onChange: (ModSide?) -> Unit) {
    FilterChip(
        selected = selected == side,
        onClick = { onChange(if (selected == side) null else side) },
        label = { Text(label) },
    )
}
