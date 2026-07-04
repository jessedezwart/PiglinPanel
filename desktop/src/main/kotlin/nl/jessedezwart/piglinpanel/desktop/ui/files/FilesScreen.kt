package nl.jessedezwart.piglinpanel.desktop.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.nbt.NbtFiles

/** Matches the old FilesController guard: NBT files above this size open read-only as a warning. */
private const val LARGE_NBT_BYTES = 512_000L

/** File browser with breadcrumbs, text/SNBT editing, rename and delete. Replaces files.html. */
@Composable
fun FilesScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var currentDir by remember(profile.id) { mutableStateOf("") }
    var entries by remember(profile.id) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var openFile by remember(profile.id) { mutableStateOf<FileEntry?>(null) }
    var editorText by remember(profile.id) { mutableStateOf("") }
    var editorError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(profile.id, currentDir, reloadKey) {
        entries = try {
            graph.fileService.list(profile, currentDir)
        } catch (e: Exception) {
            snackbar.showSnackbar("Could not list $currentDir: ${e.message}")
            emptyList()
        }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.directory) {
            currentDir = entry.path
            openFile = null
            return
        }
        if (NbtFiles.isNbtExtension(entry.path) && entry.size > LARGE_NBT_BYTES) {
            editorError = "NBT file is larger than 500 KB; editing is disabled to avoid corruption."
            openFile = entry
            editorText = ""
            return
        }
        scope.launch {
            try {
                editorText = graph.fileService.readText(profile, entry.path)
                editorError = null
                openFile = entry
            } catch (e: Exception) {
                snackbar.showSnackbar("Could not open ${entry.name}: ${e.message}")
            }
        }
    }

    fun save() {
        val file = openFile ?: return
        scope.launch {
            try {
                if (NbtFiles.isNbtExtension(file.path)) {
                    graph.fileService.saveNbt(profile, file.path, editorText)
                } else {
                    graph.fileService.saveText(profile, file.path, editorText)
                }
                snackbar.showSnackbar("Saved ${file.name}")
                reloadKey++
            } catch (e: Exception) {
                snackbar.showSnackbar("Save failed: ${e.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Breadcrumbs(currentDir) { currentDir = it; openFile = null }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.width(360.dp)) {
                LazyColumn(Modifier.padding(6.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            )
                            TextButton(onClick = { openEntry(entry) }, modifier = Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { renaming = entry }) {
                                Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Rename")
                            }
                            IconButton(onClick = { deleting = entry }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
            Card(Modifier.weight(1f).fillMaxSize()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val file = openFile) {
                        null -> Text("Select a file to view or edit.", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(file.path, style = MaterialTheme.typography.titleSmall)
                                Button(onClick = ::save, enabled = editorError == null) { Text("Save") }
                            }
                            editorError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            } ?: OutlinedTextField(
                                value = editorText,
                                onValueChange = { editorText = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { entry ->
        var target by remember(entry.path) { mutableStateOf(entry.path) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Move / rename") },
            text = {
                OutlinedTextField(target, { target = it }, label = { Text("New path (relative)") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            graph.fileService.move(profile, entry.path, target)
                            reloadKey++
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Move failed: ${e.message}")
                        }
                        renaming = null
                    }
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${entry.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            graph.fileService.delete(profile, entry.path)
                            if (openFile?.path == entry.path) openFile = null
                            reloadKey++
                        } catch (e: Exception) {
                            snackbar.showSnackbar("Delete failed: ${e.message}")
                        }
                        deleting = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Breadcrumbs(currentDir: String, onNavigate: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate("") }) { Text("root") }
        if (currentDir.isNotBlank()) {
            val segments = currentDir.split('/')
            var path = ""
            for (segment in segments) {
                path = if (path.isEmpty()) segment else "$path/$segment"
                val target = path
                Text("/", color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = { onNavigate(target) }) { Text(segment) }
            }
        }
    }
}
