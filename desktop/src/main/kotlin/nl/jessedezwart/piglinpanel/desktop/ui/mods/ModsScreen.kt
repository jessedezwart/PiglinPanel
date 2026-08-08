package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.Tab
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ScanProgress

/** A mod mutation: runs [block], then rescans (checking updates when [checkUpdatesAfter]). */
private typealias Mutation = (label: String, checkUpdatesAfter: Boolean, block: suspend () -> Unit) -> Unit

/** Mod management: installed mods (scan, update, enable/disable/delete) and mod discovery/install. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var tab by remember(profile.id) { mutableStateOf(0) }
    var groups by remember(profile.id) { mutableStateOf<List<ModGroup>>(emptyList()) }
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
        val mods = graph.modService.scan(profile, checkUpdates, forceRefresh) { p -> progress = p }
        groups = graph.modService.group(mods)
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
        Box(Modifier.fillMaxSize().padding(top = 12.dp)) {
            when (tab) {
                0 -> InstalledModsTab(
                    graph = graph,
                    profile = profile,
                    groups = groups,
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
    progress: ScanProgress?,
    task: String?,
    rescan: (checkUpdates: Boolean, forceRefresh: Boolean) -> Unit,
    updateAll: (List<Pair<ModGroup, ModFile>>) -> Unit,
    mutate: Mutation,
    notify: (String) -> Unit,
) {
    val busy = task != null
    val updatable = remember(groups) {
        groups.mapNotNull { group ->
            val active = group.active ?: return@mapNotNull null
            if (group.update?.available() == true) group to active else null
        }
    }
    val canUpdateAll = !busy && updatable.isNotEmpty()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (groups.isEmpty() && !busy) {
            Text("No mods scanned yet. Run a scan to list mods.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.key }) { group ->
                ModGroupCard(
                    group = group,
                    enabled = !busy,
                    onApplyUpdate = { mod ->
                        mutate("Update", true) {
                            graph.modService.applyUpdate(profile, mod)
                            notify("Updated ${group.displayName}")
                        }
                    },
                    onDisable = { mod ->
                        mutate("Disable", false) { graph.modService.disablePath(profile, mod.relativePath) }
                    },
                    onActivate = { mod ->
                        mutate("Activate", false) {
                            graph.modService.activate(profile, mod.relativePath, group.active?.relativePath)
                        }
                    },
                    onDelete = { mod ->
                        mutate("Delete", false) { graph.modService.delete(profile, mod) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModGroupCard(
    group: ModGroup,
    enabled: Boolean,
    onApplyUpdate: (ModFile) -> Unit,
    onDisable: (ModFile) -> Unit,
    onActivate: (ModFile) -> Unit,
    onDelete: (ModFile) -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.iconUrl?.let { url ->
                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(36.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(group.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            group.folderName.takeIf { it.isNotBlank() },
                            group.active?.fileName,
                            group.update?.takeIf { it.downloads > 0 }?.let { "${it.formattedDownloads()} downloads" },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val update = group.update
                if (update != null && update.available()) {
                    Button(onClick = { group.active?.let(onApplyUpdate) }, enabled = enabled) {
                        Text("Update to ${update.latestVersionName ?: update.latestVersionId}")
                    }
                }
                group.active?.let { active ->
                    OutlinedButton(onClick = { onDisable(active) }, enabled = enabled) { Text("Disable") }
                    TextButton(onClick = { onDelete(active) }, enabled = enabled) { Text("Delete") }
                }
            }
            if (group.hasArchived()) {
                Column(Modifier.padding(start = 46.dp)) {
                    group.archived.forEach { archived ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(archived.fileName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onActivate(archived) }, enabled = enabled) { Text("Activate") }
                            TextButton(onClick = { onDelete(archived) }, enabled = enabled) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
