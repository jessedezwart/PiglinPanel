package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.DropdownMenuItem
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.CompatibilityProgress
import nl.jessedezwart.piglinpanel.mods.CompatibilityReport
import nl.jessedezwart.piglinpanel.mods.CompatibilityStatus
import nl.jessedezwart.piglinpanel.mods.CompatibilitySummary

/**
 * "Would this pack survive a move to Minecraft X?" — every enabled mod is looked up against a target
 * loader and game version, so the ones with nothing published for it can be seen before anything on
 * the server is touched. Nothing here writes: it is a report, not a migration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompatibilityTab(
    graph: AppGraph,
    profile: ServerProfile,
    groups: List<ModGroup>,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var loader by remember(profile.id) { mutableStateOf(profile.modLoader) }
    var mcVersion by remember(profile.id) { mutableStateOf(profile.mcVersion.orEmpty()) }
    var loaderMenuExpanded by remember { mutableStateOf(false) }
    var report by remember(profile.id) { mutableStateOf<CompatibilityReport?>(null) }
    var summary by remember(profile.id) { mutableStateOf<CompatibilitySummary?>(null) }
    var progress by remember(profile.id) { mutableStateOf<CompatibilityProgress?>(null) }
    var checking by remember(profile.id) { mutableStateOf(false) }

    /** Statuses folded away; empty shows everything. Survives a re-check, so a filter stays put. */
    val hidden = remember(profile.id) { mutableStateSetOf<CompatibilityStatus>() }

    /** Summary rows unfolded to show which mods would be left behind. */
    val expandedVersions = remember(profile.id) { mutableStateSetOf<String>() }

    val installed = remember(groups) { groups.filter { it.active != null } }
    val canCheck = !checking && mcVersion.isNotBlank() && installed.isNotEmpty()

    /** Runs one of the two lookups, keeping the other's stale result off the screen. */
    fun launchCheck(label: String, block: suspend () -> Unit) {
        if (!canCheck) return
        checking = true
        progress = null
        report = null
        summary = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                snackbar.showSnackbar("$label failed: ${e.message}")
            } finally {
                progress = null
                checking = false
            }
        }
    }

    fun check() = launchCheck("Compatibility check") {
        report = graph.modCompatibilityService.check(
            groups = installed,
            loader = loader,
            mcVersion = mcVersion.trim(),
        ) { p -> progress = p }
    }

    fun summarize() = launchCheck("Compatibility summary") {
        // "Newer" is decided on release dates from Modrinth's version list rather than by parsing
        // version strings, so snapshots and the odd 1.21.4-vs-1.21.10 ordering take care of themselves.
        val current = mcVersion.trim()
        val releases = graph.modrinthClient.gameVersions().filter { it.release }
        // An unrecognised version has no date to compare against, so every release is a candidate.
        val from = releases.firstOrNull { it.version.equals(current, ignoreCase = true) }?.releasedAt
        val newer = releases
            .filter { candidate ->
                val released = candidate.releasedAt
                from == null || (released != null && released > from)
            }
            .filterNot { it.version.equals(current, ignoreCase = true) }
            // Nearest upgrade first: that is the one most likely to be taken.
            .sortedBy { it.releasedAt }
            .map { it.version }
        if (newer.isEmpty()) {
            snackbar.showSnackbar("No Minecraft release is newer than $current")
            return@launchCheck
        }
        summary = graph.modCompatibilityService.summarize(
            groups = installed,
            loader = loader,
            mcVersions = newer,
        ) { p -> progress = p }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(expanded = loaderMenuExpanded, onExpandedChange = { loaderMenuExpanded = it }) {
                OutlinedTextField(
                    value = loaderLabel(loader),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Loader") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(loaderMenuExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).width(160.dp),
                )
                ExposedDropdownMenu(expanded = loaderMenuExpanded, onDismissRequest = { loaderMenuExpanded = false }) {
                    LOADERS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(loaderLabel(option)) },
                            onClick = {
                                loader = option
                                loaderMenuExpanded = false
                            },
                        )
                    }
                }
            }
            McVersionField(
                graph = graph,
                value = mcVersion,
                onValueChange = { mcVersion = it },
                label = "MC version",
            )
            Button(onClick = ::check, enabled = canCheck) {
                Text(if (checking) "Checking…" else "Check this version")
            }
            OutlinedButton(onClick = ::summarize, enabled = canCheck) {
                Text("Summarise newer versions")
            }
        }
        if (installed.isEmpty()) {
            Text(
                "No enabled mods to check. Scan the installed mods first.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        progress?.let { p ->
            Column {
                Text(
                    "Checking ${p.done}/${p.total}" + (p.displayName?.let { ": $it" } ?: "…"),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        report?.let { CompatibilityReportView(it, hidden) }
        summary?.let { CompatibilitySummaryView(it, expandedVersions) }
    }
}
