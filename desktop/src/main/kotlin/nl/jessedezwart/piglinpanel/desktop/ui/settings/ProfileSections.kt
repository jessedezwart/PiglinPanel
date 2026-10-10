package nl.jessedezwart.piglinpanel.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.FormField
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.SecretField
import nl.jessedezwart.piglinpanel.desktop.ui.common.Switch
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ServerAction
import nl.jessedezwart.piglinpanel.ssh.SshIdentities

@Composable
internal fun LocationSection(draft: ProfileDraft, edit: (ProfileDraft.() -> ProfileDraft) -> Unit) {
    val local = draft.accessType == AccessType.LOCAL
    SettingsSection(
        title = "Location",
        description = "Where the server lives and how Piglin Panel reaches its files.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = local,
                onClick = { edit { copy(accessType = AccessType.LOCAL) } },
                label = { Text("This computer") },
            )
            FilterChip(
                selected = draft.accessType == AccessType.SSH,
                onClick = { edit { copy(accessType = AccessType.SSH) } },
                label = { Text("Remote over SSH") },
            )
        }
        FormField(
            value = draft.rootPath,
            onValueChange = { edit { copy(rootPath = it) } },
            label = "Server root path",
            placeholder = "~/minecraft-server",
            supportingText = "The folder holding server.properties.",
            trailingIcon = if (local) {
                { BrowseFolderButton(draft.rootPath) { edit { copy(rootPath = it.absolutePath) } } }
            } else {
                null
            },
        )
        FormField(
            value = draft.logsPath,
            onValueChange = { edit { copy(logsPath = it) } },
            label = "Logs folder",
            placeholder = "logs",
            supportingText = "Relative to the server root.",
            trailingIcon = if (local) {
                {
                    BrowseFolderButton(startIn(draft.rootPath, draft.logsPath)) {
                        edit { copy(logsPath = relativeToRoot(rootPath, it)) }
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun SshConnectionSection(draft: ProfileDraft, edit: (ProfileDraft.() -> ProfileDraft) -> Unit) {
    SettingsSection(
        title = "SSH connection",
        description = "The host to connect to and how to authenticate.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                value = draft.sshHost,
                onValueChange = { edit { copy(sshHost = it) } },
                label = "Host",
                placeholder = "mc.example.com",
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = draft.sshPort,
                onValueChange = { edit { copy(sshPort = it.filter(Char::isDigit)) } },
                label = "Port",
                placeholder = "22",
                modifier = Modifier.width(96.dp),
            )
        }
        FormField(
            value = draft.sshUser,
            onValueChange = { edit { copy(sshUser = it) } },
            label = "Username",
            placeholder = "minecraft",
        )

        Text("Authentication", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshAuth.entries.forEach { option ->
                FilterChip(
                    selected = draft.auth == option,
                    onClick = { edit { copy(auth = option) } },
                    label = { Text(option.label) },
                )
            }
        }
        when (draft.auth) {
            SshAuth.DefaultKeys -> DefaultKeysNotice()
            SshAuth.KeyFile -> {
                FormField(
                    value = draft.sshKeyPath,
                    onValueChange = { edit { copy(sshKeyPath = it) } },
                    label = "Private key file",
                    placeholder = "~/.ssh/id_ed25519",
                )
                SecretField(
                    value = draft.sshKeyPassphrase,
                    onValueChange = { edit { copy(sshKeyPassphrase = it) } },
                    label = "Key passphrase",
                    supportingText = "Leave empty if the key is not encrypted.",
                )
            }

            SshAuth.Password -> SecretField(
                value = draft.sshPassword,
                onValueChange = { edit { copy(sshPassword = it) } },
                label = "Password",
                supportingText = "Stored encrypted on this computer.",
            )
        }
    }
}

@Composable
internal fun ActionsSection(draft: ProfileDraft, edit: (ProfileDraft.() -> ProfileDraft) -> Unit) {
    SettingsSection(
        title = "Actions",
        description = "Commands you can run on the server's host from Overview, such as" +
            " docker compose restart. Each runs to completion, and its output is shown.",
    ) {
        draft.actions.forEachIndexed { index, action ->
            ActionEditor(
                action = action,
                onChange = { changed -> edit { copy(actions = actions.replaceAt(index) { changed }) } },
                onRemove = { edit { copy(actions = actions.filterIndexed { i, _ -> i != index }) } },
            )
        }
        OutlinedButton(onClick = { edit { copy(actions = actions + ServerAction()) } }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
            Text("Add action", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun ModFoldersSection(draft: ProfileDraft, edit: (ProfileDraft.() -> ProfileDraft) -> Unit) {
    fun editFolders(transform: List<ModFolder>.() -> List<ModFolder>) = edit { copy(modFolders = modFolders.transform()) }

    SettingsSection(
        title = "Mod folders",
        description = "Folders scanned for mods, each relative to the server root.",
    ) {
        draft.modFolders.forEachIndexed { index, folder ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormField(
                    value = folder.name,
                    onValueChange = { value -> editFolders { replaceAt(index) { it.copy(name = value) } } },
                    label = "Name",
                    placeholder = "Server mods",
                    modifier = Modifier.weight(1f),
                )
                FormField(
                    value = folder.path,
                    onValueChange = { value -> editFolders { replaceAt(index) { it.copy(path = value) } } },
                    label = "Folder",
                    placeholder = "mods",
                    modifier = Modifier.weight(1f),
                    trailingIcon = if (draft.accessType == AccessType.LOCAL) {
                        {
                            BrowseFolderButton(startIn(draft.rootPath, folder.path)) { picked ->
                                editFolders { replaceAt(index) { it.copy(path = relativeToRoot(draft.rootPath, picked)) } }
                            }
                        }
                    } else {
                        null
                    },
                )
                IconButton(onClick = { editFolders { filterIndexed { i, _ -> i != index } } }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove mod folder")
                }
            }
        }
        OutlinedButton(onClick = { editFolders { this + ModFolder() } }) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
            Text("Add mod folder", Modifier.padding(start = 6.dp))
        }
    }
}

/** One configured action: its label, the command, where it runs, and whether to confirm first. */
@Composable
private fun ActionEditor(action: ServerAction, onChange: (ServerAction) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormField(
                value = action.name,
                onValueChange = { onChange(action.copy(name = it)) },
                label = "Name",
                placeholder = "Restart",
                modifier = Modifier.weight(1f),
            )
            FormField(
                value = action.command,
                onValueChange = { onChange(action.copy(command = it)) },
                label = "Command",
                placeholder = "docker compose restart",
                modifier = Modifier.weight(2f),
            )
            FormField(
                value = action.workingDirectory,
                onValueChange = { onChange(action.copy(workingDirectory = it)) },
                label = "Working directory",
                placeholder = "Server root",
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove action")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = action.confirm, onCheckedChange = { onChange(action.copy(confirm = it)) })
            Text("Ask before running", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Names the keys that will actually be tried, so the option is not a guess about the machine. */
@Composable
private fun DefaultKeysNotice() {
    val keys = remember { SshIdentities.defaultKeys() }
    val sshDir = remember { SshIdentities.sshDirectory().path }
    Text(
        if (keys.isEmpty()) {
            "No private keys found in $sshDir — add one, or pick another method."
        } else {
            "Uses " + keys.joinToString { it.name } + " from $sshDir, the same keys as the ssh command."
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (keys.isEmpty()) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private inline fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) transform(item) else item }

/**
 * Where the folder chooser should open for a field stored relative to the server root: the folder
 * it currently points at, or the server root when that folder does not exist (yet).
 */
private fun startIn(rootPath: String, relative: String): String {
    val root = File(rootPath.ifBlank { "." })
    val current = relative.takeIf { it.isNotBlank() }?.let { File(root, it) }
    return (current?.takeIf { it.isDirectory } ?: root).absolutePath
}

/**
 * The value to store for a chosen folder: relative to [rootPath], with forward slashes, so profiles
 * stay portable between the local copy and the server. Folders outside the root keep their absolute
 * path, since there is nothing sensible to make them relative to.
 */
private fun relativeToRoot(rootPath: String, picked: File): String {
    val root = File(rootPath.ifBlank { "." })
    return try {
        val relative = root.absoluteFile.toPath().relativize(picked.absoluteFile.toPath()).toString()
        if (relative.startsWith("..")) picked.absolutePath else relative.replace(File.separatorChar, '/')
    } catch (e: IllegalArgumentException) {
        // Different drive on Windows: no relative path exists.
        picked.absolutePath
    }
}
