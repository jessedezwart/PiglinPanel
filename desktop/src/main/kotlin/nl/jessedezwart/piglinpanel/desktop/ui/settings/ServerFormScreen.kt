package nl.jessedezwart.piglinpanel.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ModFolder
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig

/** Profile management: list of servers plus a create/edit form. Replaces server-form.html. */
@Composable
fun ServerFormScreen(graph: AppGraph, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val config by graph.registry.config.collectAsState()
    var editing by remember { mutableStateOf<ServerProfile?>(null) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Server Profiles", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(config.servers, key = { it.id }) { profile ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { editing = profile }) {
                                    Text(profile.displayName)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        graph.registry.delete(profile.id)
                                        if (editing?.id == profile.id) editing = null
                                        snackbar.showSnackbar("Deleted ${profile.displayName}")
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { editing = ServerProfile() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add profile", Modifier.padding(start = 6.dp))
                    }
                }
            }
            IntegrationsCard(graph, config.curseforgeApiKey, snackbar)
        }

        editing?.let { profile ->
            ProfileForm(
                key = profile.id,
                initial = profile,
                onSave = { updated ->
                    scope.launch {
                        try {
                            graph.registry.save(updated)
                            snackbar.showSnackbar("Saved ${updated.displayName}")
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Save failed: ${e.message}")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ProfileForm(key: String, initial: ServerProfile, onSave: (ServerProfile) -> Unit) {
    var accessType by remember(key) { mutableStateOf(initial.accessType) }
    var rootPath by remember(key) { mutableStateOf(initial.rootPath) }
    var logsPath by remember(key) { mutableStateOf(initial.logsPath) }
    var modFolders by remember(key) { mutableStateOf(initial.modFolders) }
    var sshHost by remember(key) { mutableStateOf(initial.ssh.host) }
    var sshPort by remember(key) { mutableStateOf(initial.ssh.port.toString()) }
    var sshUser by remember(key) { mutableStateOf(initial.ssh.username) }
    var sshPassword by remember(key) { mutableStateOf(initial.ssh.password) }
    var sshKeyPath by remember(key) { mutableStateOf(initial.ssh.privateKeyPath) }
    var sshKeyPassphrase by remember(key) { mutableStateOf(initial.ssh.privateKeyPassphrase) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Server profile", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = accessType == AccessType.LOCAL,
                onClick = { accessType = AccessType.LOCAL },
                label = { Text("Local") },
            )
            FilterChip(
                selected = accessType == AccessType.SSH,
                onClick = { accessType = AccessType.SSH },
                label = { Text("SSH") },
            )
        }
        OutlinedTextField(
            rootPath,
            { rootPath = it },
            label = { Text("Server root path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.7f),
            trailingIcon = if (accessType == AccessType.LOCAL) {
                { BrowseFolderButton({ pickDirectory(rootPath) }) { rootPath = it } }
            } else null,
        )
        OutlinedTextField(
            logsPath,
            { logsPath = it },
            label = { Text("Logs path (relative)") },
            singleLine = true,
            trailingIcon = if (accessType == AccessType.LOCAL) {
                { BrowseFolderButton({ pickRelativeDirectory(rootPath, logsPath) }) { logsPath = it } }
            } else null,
        )

        HorizontalDivider()
        Text("Mod folders", style = MaterialTheme.typography.titleSmall)
        modFolders.forEachIndexed { index, folder ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    folder.name,
                    { value -> modFolders = modFolders.replaceAt(index) { it.copy(name = value, id = value.ifBlank { it.path }) } },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.width(180.dp),
                )
                OutlinedTextField(
                    folder.path,
                    { value -> modFolders = modFolders.replaceAt(index) { it.copy(path = value) } },
                    label = { Text("Path (relative)") },
                    singleLine = true,
                    modifier = Modifier.width(240.dp),
                    trailingIcon = if (accessType == AccessType.LOCAL) {
                        {
                            BrowseFolderButton({ pickRelativeDirectory(rootPath, folder.path) }) { picked ->
                                modFolders = modFolders.replaceAt(index) { it.copy(path = picked) }
                            }
                        }
                    } else null,
                )
                IconButton(onClick = { modFolders = modFolders.filterIndexed { i, _ -> i != index } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove folder")
                }
            }
        }
        OutlinedButton(onClick = { modFolders = modFolders + ModFolder("", "", "") }) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Add mod folder", Modifier.padding(start = 6.dp))
        }

        if (accessType == AccessType.SSH) {
            HorizontalDivider()
            Text("SSH connection", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sshHost, { sshHost = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.width(260.dp))
                OutlinedTextField(sshPort, { sshPort = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(110.dp))
            }
            OutlinedTextField(sshUser, { sshUser = it }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(sshPassword, { sshPassword = it }, label = { Text("Password (optional)") }, singleLine = true)
            OutlinedTextField(sshKeyPath, { sshKeyPath = it }, label = { Text("Private key path (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.7f))
            OutlinedTextField(sshKeyPassphrase, { sshKeyPassphrase = it }, label = { Text("Key passphrase (optional)") }, singleLine = true)
        }

        Text(
            "RCON settings are read automatically from server.properties on save.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            onSave(
                initial.copy(
                    accessType = accessType,
                    rootPath = rootPath,
                    logsPath = logsPath,
                    modFolders = modFolders.filter { it.path.isNotBlank() },
                    ssh = SshConfig(
                        host = sshHost,
                        port = sshPort.toIntOrNull() ?: 22,
                        username = sshUser,
                        password = sshPassword,
                        privateKeyPath = sshKeyPath,
                        privateKeyPassphrase = sshKeyPassphrase,
                    ),
                ),
            )
        }) {
            Text("Save")
        }
    }
}

private inline fun List<ModFolder>.replaceAt(index: Int, transform: (ModFolder) -> ModFolder): List<ModFolder> =
    mapIndexed { i, folder -> if (i == index) transform(folder) else folder }

/** Trailing-icon button that opens a native directory chooser and applies the result, if any. */
@Composable
private fun BrowseFolderButton(pick: () -> String?, onPicked: (String) -> Unit) {
    IconButton(onClick = { pick()?.let(onPicked) }) {
        Icon(Icons.Default.Folder, contentDescription = "Browse for folder")
    }
}

/** Opens a native "select directory" dialog seeded at [initialDir], returning the absolute path chosen. */
private fun pickDirectory(initialDir: String?): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        dialogTitle = "Select folder"
        if (!initialDir.isNullOrBlank()) {
            val dir = File(initialDir)
            if (dir.isDirectory) currentDirectory = dir
        }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}

/**
 * Opens a directory chooser rooted under [rootPath] and returns the chosen path relative to it
 * (falling back to the absolute path if it falls outside the root). Used for fields — logs path,
 * mod folder paths — that are stored relative to the server root.
 */
private fun pickRelativeDirectory(rootPath: String, initialRelative: String?): String? {
    val rootDir = File(rootPath.ifBlank { "." })
    val startDir = initialRelative?.takeIf { it.isNotBlank() }?.let { File(rootDir, it) }?.takeIf { it.isDirectory }
    val picked = pickDirectory((startDir ?: rootDir).absolutePath) ?: return null
    return try {
        val relative = rootDir.toPath().relativize(File(picked).toPath())
        if (relative.toString().startsWith("..")) picked else relative.toString().replace(File.separatorChar, '/')
    } catch (e: IllegalArgumentException) {
        picked
    }
}

@Composable
private fun IntegrationsCard(graph: AppGraph, storedKey: String, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(storedKey) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(storedKey) { key = storedKey }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Integrations", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("CurseForge API key") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (visible) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Optional — without a key, mod search only covers Modrinth. Get a key at console.curseforge.com.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                scope.launch {
                    graph.registry.updateCurseforgeApiKey(key.trim())
                    snackbar.showSnackbar("Saved CurseForge API key")
                }
            }) {
                Text("Save")
            }
        }
    }
}
