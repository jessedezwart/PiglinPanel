package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.DropdownMenuItem
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSourceBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.SUB_ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.CompatibilityProgress
import nl.jessedezwart.piglinpanel.mods.CompatibilityReport
import nl.jessedezwart.piglinpanel.mods.CompatibilityStatus
import nl.jessedezwart.piglinpanel.mods.CompatibilitySummary
import nl.jessedezwart.piglinpanel.mods.ModCompatibility
import nl.jessedezwart.piglinpanel.mods.VersionSummary

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
        report?.let { result ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${result.results.size} mods against ${loaderLabel(result.loader)} ${result.mcVersion}",
                    style = MaterialTheme.typography.bodySmall,
                )
                CompatibilityStatus.entries.forEach { status ->
                    FilterChip(
                        selected = status !in hidden,
                        onClick = { if (!hidden.add(status)) hidden.remove(status) },
                        label = { Text("${statusLabel(status)} (${result.count(status)})") },
                    )
                }
            }
            val visible = result.results.filter { it.status !in hidden }
            LazyColumn {
                items(visible, key = { it.key }) { entry -> CompatibilityRow(entry) }
            }
        }
        summary?.let { result ->
            Text(
                "${result.answered} mods on ${loaderLabel(result.loader)}, " +
                    "across ${result.versions.size} newer release(s)" +
                    result.unknown.size.takeIf { it > 0 }?.let { " · $it could not be checked" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
            if (result.unknown.isNotEmpty()) {
                Text(
                    "Unknown: ${result.unknown.joinToString { it.displayName }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn {
                items(result.versions, key = { it.mcVersion }) { version ->
                    VersionSummaryRow(
                        summary = version,
                        answered = result.answered,
                        loaderName = loaderLabel(result.loader),
                        expanded = version.mcVersion in expandedVersions,
                        onToggle = {
                            if (!expandedVersions.add(version.mcVersion)) expandedVersions.remove(version.mcVersion)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One candidate Minecraft version: how much of the pack survives it, unfolding to name the mods that
 * would be left behind — the count answers "can I move?", the names answer "what do I do about it?".
 */
@Composable
private fun VersionSummaryRow(
    summary: VersionSummary,
    answered: Int,
    loaderName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val whole = summary.incompatible == 0

    // A version where not one mod has a build is almost never a pack problem: it is a loader that
    // has not reached that Minecraft version yet. Naming every mod as "missing" would bury that.
    val barren = summary.compatible == 0 && answered > 0
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .handCursor()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(ROW_ICON),
            )
            Text(
                summary.mcVersion,
                modifier = Modifier.width(96.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Text(
                "${summary.compatible}/$answered",
                modifier = Modifier.width(72.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (whole) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                when {
                    whole -> "every mod has a version"
                    barren -> "nothing published for $loaderName yet"
                    else -> "missing: ${summary.missing.joinToString()}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    whole || barren -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Nothing to unfold when the answer is "the loader is not here yet" — the list would be the
        // whole pack, which says less than the one line already does.
        if (expanded && !barren && summary.missing.isNotEmpty()) {
            Column(Modifier.padding(start = ROW_ICON + 6.dp, bottom = 4.dp)) {
                summary.missing.forEach { name ->
                    Text(
                        name,
                        modifier = Modifier.height(SUB_ROW_HEIGHT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompatibilityRow(entry: ModCompatibility) {
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(ROW_ICON), contentAlignment = Alignment.Center) {
            entry.source?.let { ModSourceBadge(it) }
        }
        Text(
            statusLabel(entry.status),
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(entry.status),
            maxLines = 1,
        )
        Text(
            entry.displayName,
            modifier = Modifier.width(240.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            // Either the version that would be installed, or why no answer could be had.
            entry.version?.let { version -> version.versionName?.takeIf { it.isNotBlank() } ?: version.fileName }
                ?: entry.detail
                ?: "no version published for this target",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun statusLabel(status: CompatibilityStatus): String = when (status) {
    CompatibilityStatus.COMPATIBLE -> "Compatible"
    CompatibilityStatus.INCOMPATIBLE -> "No version"
    CompatibilityStatus.UNKNOWN -> "Unknown"
}

@Composable
private fun statusColor(status: CompatibilityStatus): Color = when (status) {
    CompatibilityStatus.COMPATIBLE -> MaterialTheme.colorScheme.primary
    CompatibilityStatus.INCOMPATIBLE -> MaterialTheme.colorScheme.error
    CompatibilityStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}
