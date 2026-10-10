package nl.jessedezwart.piglinpanel.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.FormField
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.SecretField
import nl.jessedezwart.piglinpanel.desktop.ui.common.Switch
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ServerAction
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig
import nl.jessedezwart.piglinpanel.ssh.SshIdentities

/**
 * Editor for one server profile. Field state is local to the pane and keyed on the profile id, so
 * switching profiles resets it; [ServerProfile] is only rebuilt to compare against the saved copy
 * (which is what enables Save) and to hand to [onSave].
 */
@Composable
internal fun ProfilePane(
    profile: ServerProfile,
    isNew: Boolean,
    onSave: (ServerProfile) -> Unit,
    onDelete: () -> Unit,
) {
    val key = profile.id
    var accessType by remember(key) { mutableStateOf(profile.accessType) }
    var rootPath by remember(key) { mutableStateOf(profile.rootPath) }
    var logsPath by remember(key) { mutableStateOf(profile.logsPath) }
    var modFolders by remember(key) { mutableStateOf(profile.modFolders) }
    var sshHost by remember(key) { mutableStateOf(profile.ssh.host) }
    var sshPort by remember(key) { mutableStateOf(profile.ssh.port.toString()) }
    var sshUser by remember(key) { mutableStateOf(profile.ssh.username) }
    var sshPassword by remember(key) { mutableStateOf(profile.ssh.password) }
    var sshKeyPath by remember(key) { mutableStateOf(profile.ssh.privateKeyPath) }
    var sshKeyPassphrase by remember(key) { mutableStateOf(profile.ssh.privateKeyPassphrase) }
    var auth by remember(key) { mutableStateOf(SshAuth.of(profile.ssh)) }
    var actions by remember(key) { mutableStateOf(profile.actions) }

    val edited = profile.copy(
        accessType = accessType,
        rootPath = rootPath.trim(),
        logsPath = logsPath.trim(),
        modFolders = modFolders.filter { it.path.isNotBlank() }.map { it.withId() },
        ssh = SshConfig(
            host = sshHost.trim(),
            port = sshPort.toIntOrNull() ?: 22,
            username = sshUser.trim(),
            // Only the credentials belonging to the chosen method are kept, so the saved profile
            // says unambiguously how it authenticates — and so switching to ~/.ssh really does
            // stop sending a password that was typed earlier.
            password = if (auth == SshAuth.Password) sshPassword else "",
            privateKeyPath = if (auth == SshAuth.KeyFile) sshKeyPath.trim() else "",
            privateKeyPassphrase = if (auth == SshAuth.KeyFile) sshKeyPassphrase else "",
        ),
        actions = actions
            .filter { it.command.isNotBlank() }
            .map { it.copy(name = it.name.trim(), command = it.command.trim(), workingDirectory = it.workingDirectory.trim()) },
    )
    val complete = rootPath.isNotBlank() && (accessType == AccessType.LOCAL || sshHost.isNotBlank())
    val dirty = edited != profile

    DetailPane(
        title = profile.displayName.ifBlank { "New server profile" },
        subtitle = profileSubtitle(profile, isNew, dirty),
        actions = {
            DeleteButton(onClick = onDelete)
            Button(onClick = { onSave(edited) }, enabled = complete && (dirty || isNew)) {
                Text("Save")
            }
        },
    ) {
        SettingsSection(
            title = "Location",
            description = "Where the server lives and how Piglin Panel reaches its files.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = accessType == AccessType.LOCAL,
                    onClick = { accessType = AccessType.LOCAL },
                    label = { Text("This computer") },
                )
                FilterChip(
                    selected = accessType == AccessType.SSH,
                    onClick = { accessType = AccessType.SSH },
                    label = { Text("Remote over SSH") },
                )
            }
            FormField(
                value = rootPath,
                onValueChange = { rootPath = it },
                label = "Server root path",
                placeholder = "~/minecraft-server",
                supportingText = "The folder holding server.properties.",
                trailingIcon = if (accessType == AccessType.LOCAL) {
                    { BrowseFolderButton(rootPath) { rootPath = it.absolutePath } }
                } else {
                    null
                },
            )
            FormField(
                value = logsPath,
                onValueChange = { logsPath = it },
                label = "Logs folder",
                placeholder = "logs",
                supportingText = "Relative to the server root.",
                trailingIcon = if (accessType == AccessType.LOCAL) {
                    { BrowseFolderButton(startIn(rootPath, logsPath)) { logsPath = relativeToRoot(rootPath, it) } }
                } else {
                    null
                },
            )
        }

        if (accessType == AccessType.SSH) {
            SettingsSection(
                title = "SSH connection",
                description = "The host to connect to and how to authenticate.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField(
                        value = sshHost,
                        onValueChange = { sshHost = it },
                        label = "Host",
                        placeholder = "mc.example.com",
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = sshPort,
                        onValueChange = { sshPort = it.filter(Char::isDigit) },
                        label = "Port",
                        placeholder = "22",
                        modifier = Modifier.width(96.dp),
                    )
                }
                FormField(
                    value = sshUser,
                    onValueChange = { sshUser = it },
                    label = "Username",
                    placeholder = "minecraft",
                )

                Text("Authentication", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SshAuth.entries.forEach { option ->
                        FilterChip(
                            selected = auth == option,
                            onClick = { auth = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                when (auth) {
                    SshAuth.DefaultKeys -> DefaultKeysNotice()
                    SshAuth.KeyFile -> {
                        FormField(
                            value = sshKeyPath,
                            onValueChange = { sshKeyPath = it },
                            label = "Private key file",
                            placeholder = "~/.ssh/id_ed25519",
                        )
                        SecretField(
                            value = sshKeyPassphrase,
                            onValueChange = { sshKeyPassphrase = it },
                            label = "Key passphrase",
                            supportingText = "Leave empty if the key is not encrypted.",
                        )
                    }

                    SshAuth.Password -> SecretField(
                        value = sshPassword,
                        onValueChange = { sshPassword = it },
                        label = "Password",
                        supportingText = "Stored encrypted on this computer.",
                    )
                }
            }

            SettingsSection(
                title = "Actions",
                description = "Commands you can run on the server's host from Overview, such as" +
                    " docker compose restart. Each runs to completion, and its output is shown.",
            ) {
                actions.forEachIndexed { index, action ->
                    ActionEditor(
                        action = action,
                        onChange = { changed -> actions = actions.mapIndexed { i, a -> if (i == index) changed else a } },
                        onRemove = { actions = actions.filterIndexed { i, _ -> i != index } },
                    )
                }
                OutlinedButton(onClick = { actions = actions + ServerAction() }) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Text("Add action", Modifier.padding(start = 6.dp))
                }
            }
        }

        SettingsSection(
            title = "Mod folders",
            description = "Folders scanned for mods, each relative to the server root.",
        ) {
            modFolders.forEachIndexed { index, folder ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FormField(
                        value = folder.name,
                        onValueChange = { value ->
                            modFolders = modFolders.replaceAt(index) { it.copy(name = value) }
                        },
                        label = "Name",
                        placeholder = "Server mods",
                        modifier = Modifier.weight(1f),
                    )
                    FormField(
                        value = folder.path,
                        onValueChange = { value ->
                            modFolders = modFolders.replaceAt(index) { it.copy(path = value) }
                        },
                        label = "Folder",
                        placeholder = "mods",
                        modifier = Modifier.weight(1f),
                        trailingIcon = if (accessType == AccessType.LOCAL) {
                            {
                                BrowseFolderButton(startIn(rootPath, folder.path)) { picked ->
                                    modFolders = modFolders.replaceAt(index) {
                                        it.copy(path = relativeToRoot(rootPath, picked))
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                    IconButton(onClick = { modFolders = modFolders.filterIndexed { i, _ -> i != index } }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove mod folder")
                    }
                }
            }
            OutlinedButton(onClick = { modFolders = modFolders + ModFolder() }) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Text("Add mod folder", Modifier.padding(start = 6.dp))
            }
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

/** How a profile authenticates over SSH. Derived from which credentials the profile carries. */
private enum class SshAuth(val label: String) {
    DefaultKeys("Keys from ~/.ssh"),
    KeyFile("Key file"),
    Password("Password"),
    ;

    companion object {
        fun of(config: SshConfig): SshAuth = when {
            config.privateKeyPath.isNotBlank() -> KeyFile
            config.password.isNotBlank() -> Password
            else -> DefaultKeys
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

private fun profileSubtitle(profile: ServerProfile, isNew: Boolean, dirty: Boolean): String = when {
    isNew -> "Not saved yet."
    dirty -> "Unsaved changes."
    else -> {
        val access = if (profile.accessType == AccessType.SSH) "Remote over SSH" else "On this computer"
        val detected = listOfNotNull(profile.modLoader?.replaceFirstChar(Char::uppercase), profile.mcVersion)
        if (detected.isEmpty()) access else access + " · " + detected.joinToString(" ")
    }
}

/** Mod folder ids are what the rest of the app keys on; keep them in step with the name. */
private fun ModFolder.withId(): ModFolder = copy(id = id.ifBlank { name.ifBlank { path } })

private inline fun List<ModFolder>.replaceAt(
    index: Int,
    transform: (ModFolder) -> ModFolder,
): List<ModFolder> = mapIndexed { i, folder -> if (i == index) transform(folder) else folder }

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
