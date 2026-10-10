package nl.jessedezwart.piglinpanel.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.SecretField
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile

/**
 * Settings, as a master/detail pair: everything configurable is an entry in the left list — every
 * server profile, plus the application itself — and the pane on the right edits exactly the entry
 * that is selected. Edits stay inside the pane until Save, so the header can say whether there is
 * anything to save and nothing gets written by accident.
 */
@Composable
fun SettingsScreen(graph: AppGraph, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val config by graph.registry.config.collectAsState()
    var pane by remember { mutableStateOf<SettingsPane?>(null) }
    /** A profile that has been added but not saved yet; it lives here until it reaches the config. */
    var draft by remember { mutableStateOf<ServerProfile?>(null) }
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }

    LaunchedEffect(Unit) {
        pane = graph.registry.selected()?.let { SettingsPane.Server(it.id) }
    }

    val addProfile = {
        val fresh = ServerProfile()
        draft = fresh
        pane = SettingsPane.Server(fresh.id)
    }
    val listed = config.servers + listOfNotNull(draft?.takeIf { d -> config.servers.none { it.id == d.id } })

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsNav(
            profiles = listed,
            unsavedId = draft?.id,
            pane = pane,
            onSelect = { pane = it },
            onAdd = addProfile,
        )

        Box(Modifier.fillMaxSize()) {
            when (val current = pane) {
                null -> EmptyPane(hasProfiles = listed.isNotEmpty(), onAdd = addProfile)

                SettingsPane.Application -> ApplicationPane(graph, config.curseforgeApiKey, snackbar)

                is SettingsPane.Server -> {
                    val stored = config.servers.firstOrNull { it.id == current.id }
                    val profile = stored ?: draft?.takeIf { it.id == current.id }
                    if (profile == null) {
                        // The profile was deleted from under us; fall back to the placeholder.
                        LaunchedEffect(current.id) { pane = null }
                    } else {
                        ProfilePane(
                            profile = profile,
                            isNew = stored == null,
                            onSave = { updated ->
                                scope.launch {
                                    try {
                                        graph.registry.save(updated)
                                        draft = null
                                        snackbar.showSnackbar("Saved ${updated.displayName}")
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar("Save failed: ${e.message}")
                                    }
                                }
                            },
                            onDelete = {
                                if (stored == null) {
                                    draft = null
                                    pane = null
                                } else {
                                    pendingDelete = profile
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this profile?") },
            text = {
                Text(
                    "${profile.displayName} will be removed from Piglin Panel. " +
                        "The server itself and its files are left untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        graph.registry.delete(profile.id)
                        if ((pane as? SettingsPane.Server)?.id == profile.id) pane = null
                        snackbar.showSnackbar("Deleted ${profile.displayName}")
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Which entry the detail pane is editing. */
private sealed interface SettingsPane {
    data class Server(val id: String) : SettingsPane

    data object Application : SettingsPane
}

@Composable
private fun SettingsNav(
    profiles: List<ServerProfile>,
    unsavedId: String?,
    pane: SettingsPane?,
    onSelect: (SettingsPane) -> Unit,
    onAdd: () -> Unit,
) {
    Card(Modifier.width(260.dp).fillMaxSize()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            NavHeading("Servers")
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(profiles, key = { it.id }) { profile ->
                    NavRow(
                        title = profile.displayName.ifBlank { "New server profile" },
                        subtitle = when {
                            profile.id == unsavedId -> "Unsaved"
                            profile.accessType == AccessType.SSH -> "SSH"
                            else -> "Local"
                        },
                        selected = (pane as? SettingsPane.Server)?.id == profile.id,
                        onClick = { onSelect(SettingsPane.Server(profile.id)) },
                    )
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add server", Modifier.padding(start = 6.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavHeading("Application")
            NavRow(
                title = "Integrations",
                subtitle = "API keys and storage",
                selected = pane == SettingsPane.Application,
                icon = Icons.Default.Tune,
                onClick = { onSelect(SettingsPane.Application) },
            )
        }
    }
}

@Composable
private fun NavHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun NavRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).handCursor(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, Modifier.size(18.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shown before anything is selected, which on a fresh install is the very first thing you see. */
@Composable
private fun EmptyPane(hasProfiles: Boolean, onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (hasProfiles) "Select a server to edit" else "No server profiles yet",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!hasProfiles) {
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add a server", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ApplicationPane(graph: AppGraph, storedKey: String, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var key by remember(storedKey) { mutableStateOf(storedKey) }

    DetailPane(
        title = "Integrations",
        subtitle = "Settings that apply to Piglin Panel itself rather than to one server.",
        actions = {
            Button(
                onClick = {
                    scope.launch {
                        graph.registry.updateCurseforgeApiKey(key.trim())
                        snackbar.showSnackbar("Saved CurseForge API key")
                    }
                },
                enabled = key != storedKey,
            ) {
                Text("Save")
            }
        },
    ) {
        SettingsSection(
            title = "CurseForge",
            description = "Optional. Without a key, mod search and update checks cover Modrinth only.",
        ) {
            SecretField(
                value = key,
                onValueChange = { key = it },
                label = "API key",
                placeholder = "${'$'}2a${'$'}10${'$'}Yx8k…",
                supportingText = "Create one at console.curseforge.com.",
            )
        }
        SettingsSection(
            title = "Storage",
            description = "Where Piglin Panel keeps this configuration.",
        ) {
            Text(graph.configPath.toString(), style = MaterialTheme.typography.bodySmall)
            Text(
                if (graph.usesOsCredentialStore) {
                    "Passwords, passphrases and API keys are not in this file — they are kept in " +
                        "${credentialStoreName()} under \"Piglin Panel\"."
                } else {
                    "This computer has no credential store, so passwords, passphrases and API keys " +
                        "are encrypted with a key file kept next to it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What this platform calls the place the OS keeps a user's credentials. */
private fun credentialStoreName(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> "Windows Credential Manager"
        os.contains("mac") || os.contains("darwin") -> "the macOS Keychain"
        else -> "the system keyring"
    }
}
