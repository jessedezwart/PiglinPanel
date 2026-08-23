package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.DropdownMenuItem
import nl.jessedezwart.piglinpanel.search.GameVersion

/**
 * Minecraft version picker backed by Modrinth's version list, filtered as you type.
 *
 * Deliberately still a text field: the list is a convenience, not a constraint — a version Modrinth
 * has not indexed yet can be typed in, and a failed fetch costs the user nothing but the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McVersionField(
    graph: AppGraph,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.width(160.dp),
) {
    var versions by remember { mutableStateOf<List<GameVersion>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    /** Snapshots outnumber releases and nobody runs a server on one by accident, so they are opt-in. */
    var showSnapshots by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        versions = try {
            graph.modrinthClient.gameVersions()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val matching = remember(versions, value, showSnapshots) {
        val typed = value.trim()
        // Text that is already a whole version is a settled choice, not a half-typed query, so the
        // menu offers everything. Filtering on it would hide every other version line — opening the
        // picker on a profile pinned to 1.21.1 would never show 26.2.
        val settled = typed.isBlank() || versions.any { it.version.equals(typed, ignoreCase = true) }
        versions.filter { candidate ->
            (showSnapshots || candidate.release) &&
                (settled || candidate.version.startsWith(typed, ignoreCase = true))
        }
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = modifier.menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        // Nothing to offer when the list never arrived, so the field stays a plain text field.
        if (versions.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (showSnapshots) "Releases only" else "Include snapshots",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    onClick = { showSnapshots = !showSnapshots },
                )
                HorizontalDivider()
                matching.take(MENU_LIMIT).forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.version) },
                        onClick = {
                            onValueChange(candidate.version)
                            expanded = false
                        },
                    )
                }
                if (matching.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No matching version", style = MaterialTheme.typography.labelSmall) },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                }
            }
        }
    }
}

/** Modrinth knows hundreds of versions; the menu shows the newest that match and lets typing do the rest. */
private const val MENU_LIMIT = 60
