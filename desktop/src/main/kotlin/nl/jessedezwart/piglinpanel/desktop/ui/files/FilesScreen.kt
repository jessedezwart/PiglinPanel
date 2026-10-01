package nl.jessedezwart.piglinpanel.desktop.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.CodeEditor
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_TEXT_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.codeLangFor
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.fs.FileContent
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.nbt.NbtFiles

/** Matches the old FilesController guard: NBT files above this size open read-only as a warning. */
private const val LARGE_NBT_BYTES = 512_000L

/** Above this the file is refused outright, before it is ever pulled over the wire. */
private const val LARGE_FILE_BYTES = 8L * 1024 * 1024

/** File browser with breadcrumbs, text/SNBT editing, rename and delete. Replaces files.html. */
@Composable
fun FilesScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var currentDir by remember(profile.id) { mutableStateOf("") }
    var entries by remember(profile.id) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var openFile by remember(profile.id) { mutableStateOf<FileEntry?>(null) }
    var editorText by remember(profile.id) { mutableStateOf("") }
    // What is on disk for the open file, so Save can stay disabled until the editor differs from it.
    var savedText by remember(profile.id) { mutableStateOf("") }
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
        fun refuse(reason: String) {
            editorError = reason
            editorText = ""
            openFile = entry
        }
        if (NbtFiles.isNbtExtension(entry.path) && entry.size > LARGE_NBT_BYTES) {
            return refuse("NBT file is larger than 500 KB; editing is disabled to avoid corruption.")
        }
        if (entry.size > LARGE_FILE_BYTES) {
            return refuse("File is larger than 8 MB; editing is disabled.")
        }
        scope.launch {
            try {
                when (val content = graph.fileService.readEditable(profile, entry.path)) {
                    is FileContent.Binary -> refuse("${entry.name} is a binary file and cannot be edited here.")
                    is FileContent.Text -> {
                        editorText = content.value
                        savedText = content.value
                        editorError = null
                        openFile = entry
                    }
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("Could not open ${entry.name}: ${e.message}")
            }
        }
    }

    fun save() {
        val file = openFile ?: return
        val text = editorText
        scope.launch {
            try {
                if (NbtFiles.isNbtExtension(file.path)) {
                    graph.fileService.saveNbt(profile, file.path, text)
                } else {
                    graph.fileService.saveText(profile, file.path, text)
                }
                if (openFile?.path == file.path) savedText = text
                snackbar.showSnackbar("Saved ${file.name}")
                reloadKey++
            } catch (e: Exception) {
                snackbar.showSnackbar("Save failed: ${e.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Breadcrumbs(currentDir) { currentDir = it; openFile = null }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.width(300.dp)) {
                LazyColumn(Modifier.padding(4.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.path == openFile?.path,
                            onOpen = { openEntry(entry) },
                            onRename = { renaming = entry },
                            onDelete = { deleting = entry },
                        )
                    }
                }
            }
            Card(Modifier.weight(1f).fillMaxSize()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val file = openFile) {
                        null -> Text("Select a file to view or edit.", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(file.path, style = MaterialTheme.typography.titleSmall)
                                Button(onClick = ::save, enabled = editorError == null && editorText != savedText) { Text("Save") }
                            }
                            editorError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            } ?: CodeEditor(
                                value = editorText,
                                onValueChange = { editorText = it },
                                language = codeLangFor(file.path),
                                modifier = Modifier.fillMaxSize(),
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

/**
 * One entry in the file list. The whole row opens the entry — glyph and trailing gap included — and
 * it paints its own hover fill, since a 22dp row is too thin a target to aim at without one.
 */
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(background)
            .hoverable(interactions)
            .handCursor()
            .clickable(onClick = onOpen)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(ROW_ICON),
            tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        Text(
            entry.name,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRename, modifier = Modifier.size(ROW_HEIGHT)) {
            Icon(
                Icons.Default.DriveFileRenameOutline,
                contentDescription = "Rename",
                modifier = Modifier.size(ROW_ICON),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(ROW_HEIGHT)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                modifier = Modifier.size(ROW_ICON),
            )
        }
    }
}

@Composable
private fun Breadcrumbs(currentDir: String, onNavigate: (String) -> Unit) {
    @Composable
    fun Crumb(label: String, target: String) {
        TextButton(
            onClick = { onNavigate(target) },
            modifier = Modifier.height(ROW_HEIGHT),
            contentPadding = ROW_TEXT_BUTTON_PADDING,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }

    Row(Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Crumb("root", "")
        if (currentDir.isNotBlank()) {
            val segments = currentDir.split('/')
            var path = ""
            for (segment in segments) {
                path = if (path.isEmpty()) segment else "$path/$segment"
                Text("/", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Crumb(segment, path)
            }
        }
    }
}
