package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSourceBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_BUTTON_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_TEXT_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.SUB_ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.Tab
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ScanProgress

/** A pending disable/delete that other installed mods depend on, held until the user confirms it. */
private data class Breakage(
    val action: String,
    val title: String,
    val dependents: List<String>,
    val proceed: () -> Unit,
)

/** A mod mutation: runs [block], then rescans (checking updates when [checkUpdatesAfter]). */
private typealias Mutation = (label: String, checkUpdatesAfter: Boolean, block: suspend () -> Unit) -> Unit

/** Mod management: installed mods (scan, update, enable/disable/delete) and mod discovery/install. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var tab by remember(profile.id) { mutableStateOf(0) }
    var groups by remember(profile.id) { mutableStateOf<List<ModGroup>>(emptyList()) }

    /** The ungrouped scan result, kept because dependency questions are asked per file, not per mod. */
    var mods by remember(profile.id) { mutableStateOf<List<ModFile>>(emptyList()) }
    var progress by remember(profile.id) { mutableStateOf<ScanProgress?>(null) }

    /** Label of the running operation, or null when idle. All controls are disabled while set. */
    var task by remember(profile.id) { mutableStateOf<String?>(null) }
    val busy = task != null

    // Snackbars suspend until dismissed, so they are always shown off the task coroutine —
    // otherwise the UI would stay disabled for the lifetime of the message.
    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun scan(checkUpdates: Boolean, forceRefresh: Boolean) {
        progress = null
        val scanned = graph.modService.scan(profile, checkUpdates, forceRefresh) { p -> progress = p }
        mods = scanned
        groups = graph.modService.group(scanned)
    }

    /** Runs [block] as the single active operation, keeping the UI disabled until it finishes. */
    fun launchTask(label: String, block: suspend () -> Unit) {
        if (busy) return
        task = label
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                notify("$label failed: ${e.message}")
            } finally {
                progress = null
                task = null
            }
        }
    }

    fun rescan(checkUpdates: Boolean, forceRefresh: Boolean = false) {
        launchTask("Scan") { scan(checkUpdates, forceRefresh) }
    }

    val mutate: Mutation = { label, checkUpdatesAfter, block ->
        launchTask(label) {
            block()
            scan(checkUpdatesAfter, false)
        }
    }

    fun updateAll(targets: List<Pair<ModGroup, ModFile>>) {
        if (targets.isEmpty()) return
        launchTask("Update all") {
            var updated = 0
            val failures = mutableListOf<String>()
            targets.forEachIndexed { index, (group, mod) ->
                progress = ScanProgress("updating", index, targets.size, group.displayName)
                try {
                    graph.modService.applyUpdate(profile, mod)
                    updated++
                } catch (e: Exception) {
                    failures += group.displayName
                }
            }
            progress = null
            notify(
                if (failures.isEmpty()) {
                    "Updated $updated mod(s)"
                } else {
                    "Updated $updated mod(s); ${failures.size} failed: ${failures.joinToString()}"
                },
            )
            scan(checkUpdates = true, forceRefresh = false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Browse") })
        }
        Box(Modifier.fillMaxSize().padding(top = 6.dp)) {
            when (tab) {
                0 -> InstalledModsTab(
                    graph = graph,
                    profile = profile,
                    groups = groups,
                    mods = mods,
                    progress = progress,
                    task = task,
                    rescan = ::rescan,
                    updateAll = ::updateAll,
                    mutate = mutate,
                    notify = ::notify,
                )
                else -> BrowseModsTab(
                    graph = graph,
                    profile = profile,
                    snackbar = snackbar,
                    onInstalled = { rescan(checkUpdates = true) },
                )
            }
        }
    }
}

@Composable
private fun InstalledModsTab(
    graph: AppGraph,
    profile: ServerProfile,
    groups: List<ModGroup>,
    mods: List<ModFile>,
    progress: ScanProgress?,
    task: String?,
    rescan: (checkUpdates: Boolean, forceRefresh: Boolean) -> Unit,
    updateAll: (List<Pair<ModGroup, ModFile>>) -> Unit,
    mutate: Mutation,
    notify: (String) -> Unit,
) {
    val busy = task != null

    /** Mods whose disabled versions are unfolded. Survives a rescan, so the tree does not refold. */
    val expanded = remember(profile.id) { mutableStateSetOf<String>() }

    /** Set while a removal is waiting on confirmation because other mods depend on the target. */
    var breakage by remember(profile.id) { mutableStateOf<Breakage?>(null) }

    /** Narrows the list to mods with no enabled jar left, for finding something to re-activate. */
    var disabledOnly by remember(profile.id) { mutableStateOf(false) }

    val disabled = remember(groups) { groups.filter { it.active == null } }
    val visible = if (disabledOnly) disabled else groups

    /**
     * Runs a disable/delete, first asking about any mods it would break. The check is skipped when
     * nothing is known to depend on the target — which includes jars whose manifest could not be
     * read, so this warns about what it can see rather than guaranteeing nothing else breaks.
     */
    fun removing(action: String, mod: ModFile, title: String, run: () -> Unit) {
        val dependents = graph.modService.dependentsBrokenBy(mods, mod)
        if (dependents.isEmpty()) run() else breakage = Breakage(action, title, dependents, run)
    }

    val updatable = remember(groups) {
        groups.mapNotNull { group ->
            val active = group.active ?: return@mapNotNull null
            if (group.update?.available() == true) group to active else null
        }
    }
    val canUpdateAll = !busy && updatable.isNotEmpty()

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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { rescan(true, false) }, enabled = !busy) {
                Text(if (task == "Scan") "Scanning…" else "Scan & check updates")
            }
            Button(onClick = { updateAll(updatable) }, enabled = canUpdateAll) {
                Text(if (updatable.isEmpty()) "Update all" else "Update all (${updatable.size})")
            }
            OutlinedButton(onClick = { rescan(true, true) }, enabled = !busy) {
                Text("Force refresh")
            }
            OutlinedButton(onClick = { rescan(false, false) }, enabled = !busy) {
                Text("Quick scan")
            }
            FilterChip(
                selected = disabledOnly,
                onClick = { disabledOnly = !disabledOnly },
                label = {
                    Text(if (disabled.isEmpty()) "Disabled only" else "Disabled only (${disabled.size})")
                },
            )
            if (busy && task != "Scan") {
                Text("$task…", style = MaterialTheme.typography.bodySmall)
            }
        }
        progress?.let { p ->
            Column {
                Text(
                    when (p.phase) {
                        "hashing" -> "Hashing ${p.done}/${p.total}: ${p.fileName ?: ""}"
                        "updating" -> "Updating ${p.done + 1}/${p.total}: ${p.fileName ?: ""}"
                        else -> "Looking up updates for ${p.total} mods…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        if (visible.isEmpty() && !busy) {
            Text(
                if (disabledOnly && groups.isNotEmpty()) {
                    "No fully disabled mods."
                } else {
                    "No mods scanned yet. Run a scan to list mods."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn {
            items(visible, key = { it.key }) { group ->
                ModGroupRow(
                    group = group,
                    enabled = !busy,
                    // A group with nothing enabled shows all it can offer in its children, so the
                    // filtered list unfolds itself rather than making every row a second click.
                    expanded = disabledOnly || group.key in expanded,
                    onToggleExpanded = {
                        if (!expanded.add(group.key)) expanded.remove(group.key)
                    },
                    onApplyUpdate = { mod ->
                        mutate("Update", true) {
                            graph.modService.applyUpdate(profile, mod)
                            notify("Updated ${group.displayName}")
                        }
                    },
                    // These rescans check updates: renaming a jar does not change its bytes, so the
                    // hashes and the update lookups both come straight from cache. Scanning without
                    // them would drop every mod's project identity and shatter the tree into one
                    // row per file.
                    onDisable = { mod ->
                        removing("Disable", mod, group.displayName) {
                            mutate("Disable", true) { graph.modService.disablePath(profile, mod.relativePath) }
                        }
                    },
                    onActivate = { mod ->
                        mutate("Activate", true) {
                            graph.modService.activate(profile, mod.relativePath, group.active?.relativePath)
                            // Enabling a version is pointless if what it needs stays disabled, so the
                            // dependencies come along with it.
                            val pulled = graph.modService.enableRequiredDependencies(profile, mods, mod)
                            if (pulled.isNotEmpty()) {
                                notify("Also enabled ${pulled.size} dependency(s): ${pulled.joinToString { it.fileName }}")
                            }
                        }
                    },
                    onDelete = { mod ->
                        removing("Delete", mod, group.displayName) {
                            mutate("Delete", true) { graph.modService.delete(profile, mod) }
                        }
                    },
                )
            }
        }
    }
}

/**
 * A mod as a tree node: the active version on a single [ROW_HEIGHT] line, with its disabled versions
 * folded away underneath it. Mods without disabled versions are leaves and have no toggle.
 */
@Composable
private fun ModGroupRow(
    group: ModGroup,
    enabled: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onApplyUpdate: (ModFile) -> Unit,
    onDisable: (ModFile) -> Unit,
    onActivate: (ModFile) -> Unit,
    onDelete: (ModFile) -> Unit,
) {
    val children = group.archived
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Column {
        Row(
            Modifier.fillMaxWidth().height(ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (children.isEmpty()) {
                Spacer(Modifier.size(ROW_ICON))
            } else {
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(ROW_ICON)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(ROW_ICON),
                    )
                }
            }
            Box(Modifier.size(ROW_ICON)) {
                group.iconUrl?.let { url ->
                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(ROW_ICON))
                }
            }
            // The name and details double as the toggle, since a 16dp chevron is a small target.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (children.isEmpty()) {
                            Modifier
                        } else {
                            Modifier.handCursor().clickable(onClick = onToggleExpanded)
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    group.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        group.folderName.takeIf { it.isNotBlank() },
                        group.active?.fileName,
                        children.count { it.archived }.takeIf { it > 0 }?.let { "$it disabled" },
                        // Why this mod is here at all: a library nobody chose directly reads as
                        // clutter until you can see what pulled it in.
                        group.requiredBy.takeIf { it.isNotEmpty() }?.let { "needed by ${it.joinToString(", ")}" },
                        group.update?.takeIf { it.downloads > 0 }?.let { "${it.formattedDownloads()} downloads" },
                    ).joinToString("  ·  "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.missingDependencies.isNotEmpty()) {
                    Text(
                        "missing ${group.missingDependencies.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val update = group.update
            if (update != null && update.available()) {
                Button(
                    onClick = { group.active?.let(onApplyUpdate) },
                    modifier = Modifier.height(ROW_BUTTON_HEIGHT),
                    enabled = enabled,
                    contentPadding = ROW_BUTTON_PADDING,
                ) {
                    ModSourceBadge(update.source)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Update to ${update.latestVersionName ?: update.latestVersionId}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            group.active?.let { active ->
                RowAction("Disable", enabled) { onDisable(active) }
                RowAction("Delete", enabled) { onDelete(active) }
            }
        }
        if (expanded) {
            // Children hang off a guide line drawn down the centre of the parent's chevron column.
            Column(
                Modifier
                    .padding(start = ROW_ICON / 2)
                    .drawBehind {
                        drawLine(
                            color = guideColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 1f,
                        )
                    },
            ) {
                children.forEach { child ->
                    Row(
                        Modifier.fillMaxWidth().height(SUB_ROW_HEIGHT).padding(start = ROW_ICON + 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            child.fileName,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // A child is normally a disabled version, but a second *enabled* jar of the
                        // same mod also lands here — offering "Activate" for one of those would
                        // disable the real active version and leave two enabled jars behind.
                        if (child.archived) {
                            RowAction("Activate", enabled) { onActivate(child) }
                        } else {
                            RowAction("Disable", enabled) { onDisable(child) }
                        }
                        RowAction("Delete", enabled) { onDelete(child) }
                    }
                }
            }
        }
    }
}

/** A text action sized to fit inside a dense row. */
@Composable
private fun RowAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(ROW_BUTTON_HEIGHT),
        enabled = enabled,
        contentPadding = ROW_TEXT_BUTTON_PADDING,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
