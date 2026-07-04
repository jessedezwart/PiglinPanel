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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
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
        Card(Modifier.width(300.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Servers", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(config.servers, key = { it.id }) { profile ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = { editing = profile }) {
                                Text(profile.name)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    graph.registry.delete(profile.id)
                                    if (editing?.id == profile.id) editing = null
                                    snackbar.showSnackbar("Deleted ${profile.name}")
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
                    Text("Add server", Modifier.padding(start = 6.dp))
                }
            }
        }

        editing?.let { profile ->
            ProfileForm(
                key = profile.id,
                initial = profile,
                onSave = { updated ->
                    scope.launch {
                        try {
                            graph.registry.save(updated)
                            snackbar.showSnackbar("Saved ${updated.name}")
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
    var name by remember(key) { mutableStateOf(initial.name) }
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
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
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
        OutlinedTextField(rootPath, { rootPath = it }, label = { Text("Server root path") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.7f))
        OutlinedTextField(logsPath, { logsPath = it }, label = { Text("Logs path (relative)") }, singleLine = true)

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
                    name = name,
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
