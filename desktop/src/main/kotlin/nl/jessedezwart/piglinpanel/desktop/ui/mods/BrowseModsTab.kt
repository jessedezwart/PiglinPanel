package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.DropdownMenuItem
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.search.ModSearchHit

private sealed interface InstallUiState {
    data object Idle : InstallUiState
    data class Installing(val phase: String) : InstallUiState
    data object Installed : InstallUiState
    data class Failed(val message: String) : InstallUiState
}

private val LOADERS: List<String?> = listOf(null, "fabric", "forge", "neoforge", "quilt")
private fun loaderLabel(loader: String?) = loader?.replaceFirstChar { it.uppercase() } ?: "Any"
private fun itemKey(hit: ModSearchHit) = "${hit.source}:${hit.projectId}"

/** Mod discovery: search Modrinth/CurseForge and one-click install with dependency resolution. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseModsTab(
    graph: AppGraph,
    profile: ServerProfile,
    snackbar: SnackbarHostState,
    onInstalled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember(profile.id) { mutableStateOf("") }
    var loader by remember(profile.id) { mutableStateOf(profile.modLoader) }
    var mcVersion by remember(profile.id) { mutableStateOf(profile.mcVersion.orEmpty()) }
    var folder by remember(profile.id) { mutableStateOf(profile.modFolders.firstOrNull()) }
    var hits by remember(profile.id) { mutableStateOf<List<ModSearchHit>>(emptyList()) }
    var warnings by remember(profile.id) { mutableStateOf<List<String>>(emptyList()) }
    var offset by remember(profile.id) { mutableStateOf(0) }
    var hasMore by remember(profile.id) { mutableStateOf(false) }
    var loading by remember(profile.id) { mutableStateOf(false) }
    var detecting by remember(profile.id) { mutableStateOf(false) }
    var loaderMenuExpanded by remember { mutableStateOf(false) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    val installStates = remember(profile.id) { mutableStateMapOf<String, InstallUiState>() }

    val curseForgeAvailable = graph.curseForgeClient.available

    suspend fun runSearch(reset: Boolean) {
        loading = true
        try {
            val nextOffset = if (reset) 0 else offset
            val result = graph.modSearchService.search(query, loader, mcVersion.ifBlank { null }, nextOffset, 20)
            hits = if (reset) result.hits else hits + result.hits
            warnings = result.warnings
            offset = nextOffset + 20
            hasMore = result.hasMore
        } catch (e: Exception) {
            snackbar.showSnackbar("Search failed: ${e.message}")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(profile.id, query, loader, mcVersion, folder?.id) {
        delay(400)
        runSearch(reset = true)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search mods") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
            OutlinedTextField(
                value = mcVersion,
                onValueChange = { mcVersion = it },
                label = { Text("MC version") },
                singleLine = true,
                modifier = Modifier.width(140.dp),
            )
            if (profile.modFolders.size > 1) {
                ExposedDropdownMenuBox(expanded = folderMenuExpanded, onExpandedChange = { folderMenuExpanded = it }) {
                    OutlinedTextField(
                        value = folder?.displayName ?: "mods",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Folder") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(folderMenuExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).width(160.dp),
                    )
                    ExposedDropdownMenu(expanded = folderMenuExpanded, onDismissRequest = { folderMenuExpanded = false }) {
                        profile.modFolders.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                onClick = {
                                    folder = option
                                    folderMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        detecting = true
                        try {
                            val updated = graph.registry.detectEnvironment(profile)
                            loader = updated.modLoader
                            mcVersion = updated.mcVersion.orEmpty()
                            snackbar.showSnackbar(
                                if (updated.modLoader != null || updated.mcVersion != null) {
                                    "Detected ${updated.modLoader ?: "unknown loader"} ${updated.mcVersion ?: ""}".trim()
                                } else {
                                    "Could not detect loader/version from server files"
                                },
                            )
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Detect failed: ${e.message}")
                        } finally {
                            detecting = false
                        }
                    }
                },
                enabled = !detecting,
            ) {
                Text(if (detecting) "Detecting…" else "Detect")
            }
        }
        if (!curseForgeAvailable) {
            AssistChip(onClick = {}, label = { Text("CurseForge disabled — add an API key in Settings") })
        }
        warnings.forEach { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (loading && hits.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (hits.isEmpty() && !loading) {
            Text("No results.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits, key = ::itemKey) { hit ->
                SearchHitCard(
                    hit = hit,
                    state = installStates[itemKey(hit)] ?: InstallUiState.Idle,
                    onInstall = {
                        val targetFolder = folder ?: profile.modFolders.firstOrNull()
                        if (targetFolder == null) {
                            scope.launch { snackbar.showSnackbar("No mod folder configured for this server") }
                        } else {
                            installStates[itemKey(hit)] = InstallUiState.Installing("resolving")
                            scope.launch {
                                try {
                                    val result = graph.modInstallService.install(
                                        profile,
                                        targetFolder,
                                        hit,
                                        loader,
                                        mcVersion.ifBlank { null },
                                    ) { p -> installStates[itemKey(hit)] = InstallUiState.Installing(p.phase) }
                                    installStates[itemKey(hit)] = InstallUiState.Installed
                                    val summary = buildString {
                                        append("Installed ${result.installed.size} file(s)")
                                        if (result.skipped.isNotEmpty()) append(", ${result.skipped.size} already present")
                                        if (result.failures.isNotEmpty()) append(", ${result.failures.size} failed")
                                    }
                                    snackbar.showSnackbar(summary)
                                    if (result.installed.isNotEmpty()) onInstalled()
                                } catch (e: Exception) {
                                    installStates[itemKey(hit)] = InstallUiState.Failed(e.message ?: "Install failed")
                                    snackbar.showSnackbar("Install failed: ${e.message}")
                                }
                            }
                        }
                    },
                )
            }
            if (hasMore) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(
                            onClick = { scope.launch { runSearch(reset = false) } },
                            enabled = !loading,
                        ) {
                            Text(if (loading) "Loading…" else "Load more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitCard(hit: ModSearchHit, state: InstallUiState, onInstall: () -> Unit) {
    Card {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            hit.iconUrl?.let { url -> AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(40.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleSmall)
                    AssistChip(
                        onClick = {},
                        label = { Text(hit.source.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
                Text(
                    hit.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(hit.author, "${formatDownloads(hit.downloads)} downloads").joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            when (state) {
                is InstallUiState.Idle -> Button(onClick = onInstall) { Text("Install") }
                is InstallUiState.Installing ->
                    Button(onClick = {}, enabled = false) { Text(state.phase.replaceFirstChar { it.uppercase() } + "…") }
                is InstallUiState.Installed -> OutlinedButton(onClick = {}, enabled = false) { Text("Installed") }
                is InstallUiState.Failed -> TextButton(onClick = onInstall) { Text("Retry") }
            }
        }
    }
}

private fun formatDownloads(downloads: Long): String = when {
    downloads >= 1_000_000 -> "${downloads / 1_000_000}M"
    downloads >= 1_000 -> "${downloads / 1_000}K"
    else -> downloads.toString()
}
