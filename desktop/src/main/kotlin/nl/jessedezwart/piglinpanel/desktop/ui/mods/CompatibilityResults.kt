package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSourceBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.SUB_ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.mods.CompatibilityReport
import nl.jessedezwart.piglinpanel.mods.CompatibilityStatus
import nl.jessedezwart.piglinpanel.mods.CompatibilitySummary
import nl.jessedezwart.piglinpanel.mods.ModCompatibility
import nl.jessedezwart.piglinpanel.mods.VersionSummary

/** One target version's answer for every mod, with a chip per status to fold that status away. */
@Composable
internal fun CompatibilityReportView(result: CompatibilityReport, hidden: MutableSet<CompatibilityStatus>) {
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

/** How the pack fares on each newer release, each unfolding to the mods it would leave behind. */
@Composable
internal fun CompatibilitySummaryView(result: CompatibilitySummary, expandedVersions: MutableSet<String>) {
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
