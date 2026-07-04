package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ScanProgress

/** Mod management: scan with progress, updates, enable/disable/delete. Replaces mods.html. */
@Composable
fun ModsScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var groups by remember(profile.id) { mutableStateOf<List<ModGroup>>(emptyList()) }
    var progress by remember(profile.id) { mutableStateOf<ScanProgress?>(null) }
    var scanning by remember(profile.id) { mutableStateOf(false) }

    fun rescan(checkUpdates: Boolean, forceRefresh: Boolean = false) {
        if (scanning) return
        scanning = true
        progress = null
        scope.launch {
            try {
                val mods = graph.modService.scan(profile, checkUpdates, forceRefresh) { p ->
                    progress = p
                }
                groups = graph.modService.group(mods)
            } catch (e: Exception) {
                snackbar.showSnackbar("Scan failed: ${e.message}")
            } finally {
                scanning = false
                progress = null
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { rescan(checkUpdates = true) }, enabled = !scanning) {
                Text(if (scanning) "Scanning…" else "Scan & check updates")
            }
            OutlinedButton(onClick = { rescan(checkUpdates = true, forceRefresh = true) }, enabled = !scanning) {
                Text("Force refresh")
            }
            OutlinedButton(onClick = { rescan(checkUpdates = false) }, enabled = !scanning) {
                Text("Quick scan")
            }
        }
        progress?.let { p ->
            Column {
                Text(
                    when (p.phase) {
                        "hashing" -> "Hashing ${p.done}/${p.total}: ${p.fileName ?: ""}"
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
        if (groups.isEmpty() && !scanning) {
            Text("No mods scanned yet. Run a scan to list mods.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it.key }) { group ->
                ModGroupCard(
                    group = group,
                    onApplyUpdate = { mod ->
                        scope.launch {
                            try {
                                graph.modService.applyUpdate(profile, mod)
                                snackbar.showSnackbar("Updated ${group.displayName}")
                                rescan(checkUpdates = true)
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Update failed: ${e.message}")
                            }
                        }
                    },
                    onDisable = { mod ->
                        scope.launch {
                            try {
                                graph.modService.disablePath(profile, mod.relativePath)
                                rescan(checkUpdates = false)
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Disable failed: ${e.message}")
                            }
                        }
                    },
                    onActivate = { mod ->
                        scope.launch {
                            try {
                                graph.modService.activate(profile, mod.relativePath, group.active?.relativePath)
                                rescan(checkUpdates = false)
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Activate failed: ${e.message}")
                            }
                        }
                    },
                    onDelete = { mod ->
                        scope.launch {
                            try {
                                graph.modService.delete(profile, mod)
                                rescan(checkUpdates = false)
                            } catch (e: Exception) {
                                snackbar.showSnackbar("Delete failed: ${e.message}")
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ModGroupCard(
    group: ModGroup,
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
                    Button(onClick = { group.active?.let(onApplyUpdate) }) {
                        Text("Update to ${update.latestVersionName ?: update.latestVersionId}")
                    }
                }
                group.active?.let { active ->
                    OutlinedButton(onClick = { onDisable(active) }) { Text("Disable") }
                    TextButton(onClick = { onDelete(active) }) { Text("Delete") }
                }
            }
            if (group.hasArchived()) {
                Column(Modifier.padding(start = 46.dp)) {
                    group.archived.forEach { archived ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(archived.fileName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onActivate(archived) }) { Text("Activate") }
                            TextButton(onClick = { onDelete(archived) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
