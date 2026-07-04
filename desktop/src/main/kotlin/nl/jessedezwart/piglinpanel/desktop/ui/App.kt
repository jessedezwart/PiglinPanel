package nl.jessedezwart.piglinpanel.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.console.ConsoleScreen
import nl.jessedezwart.piglinpanel.desktop.ui.files.FilesScreen
import nl.jessedezwart.piglinpanel.desktop.ui.logs.LogsScreen
import nl.jessedezwart.piglinpanel.desktop.ui.mods.ModsScreen
import nl.jessedezwart.piglinpanel.desktop.ui.overview.OverviewScreen
import nl.jessedezwart.piglinpanel.desktop.ui.rcon.RconScreen
import nl.jessedezwart.piglinpanel.desktop.ui.settings.ServerFormScreen
import nl.jessedezwart.piglinpanel.model.ServerProfile

enum class Screen(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Dashboard),
    Mods("Mods", Icons.Default.Extension),
    Files("Files", Icons.Default.Folder),
    Logs("Logs", Icons.Default.Article),
    Rcon("RCON", Icons.Default.Terminal),
    Console("Console", Icons.Default.Monitor),
    Settings("Servers", Icons.Default.Settings),
}

@Composable
fun App(graph: AppGraph) {
    var screen by remember { mutableStateOf(Screen.Overview) }
    val snackbar = remember { SnackbarHostState() }
    val config by graph.registry.config.collectAsState()
    val selected = config.servers.firstOrNull { it.id == config.selectedServerId }
        ?: config.servers.firstOrNull()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail {
                Screen.entries.forEach { item ->
                    NavigationRailItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                ProfileSelector(graph, selected, onManageServers = { screen = Screen.Settings })
                Box(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    when (screen) {
                        Screen.Settings -> ServerFormScreen(graph, snackbar)
                        else -> if (selected == null) {
                            NoServerPlaceholder(onAdd = { screen = Screen.Settings })
                        } else {
                            when (screen) {
                                Screen.Overview -> OverviewScreen(graph, selected)
                                Screen.Mods -> ModsScreen(graph, selected, snackbar)
                                Screen.Files -> FilesScreen(graph, selected, snackbar)
                                Screen.Logs -> LogsScreen(graph, selected)
                                Screen.Rcon -> RconScreen(graph, selected)
                                Screen.Console -> ConsoleScreen()
                                Screen.Settings -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelector(
    graph: AppGraph,
    selected: ServerProfile?,
    onManageServers: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val config by graph.registry.config.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.name ?: "No server configured",
                onValueChange = {},
                readOnly = true,
                label = { Text("Server") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).width(320.dp),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                config.servers.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            expanded = false
                            scope.launch { graph.registry.select(profile) }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Manage servers…") },
                    onClick = {
                        expanded = false
                        onManageServers()
                    },
                )
            }
        }
    }
}

@Composable
private fun NoServerPlaceholder(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("No server profiles yet", style = MaterialTheme.typography.headlineSmall)
            Surface(
                onClick = onAdd,
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    "Add a server",
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
