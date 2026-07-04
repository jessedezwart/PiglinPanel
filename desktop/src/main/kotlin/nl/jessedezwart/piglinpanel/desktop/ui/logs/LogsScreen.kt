package nl.jessedezwart.piglinpanel.desktop.ui.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.logs.LogFilter
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

/** Log viewing with file picker, filter, and live tail. Replaces logs.html + /ws/logs. */
@Composable
fun LogsScreen(graph: AppGraph, profile: ServerProfile) {
    var selectedFile by remember(profile.id) { mutableStateOf("${profile.logsPath}/latest.log") }
    var filter by remember(profile.id) { mutableStateOf("") }
    var live by remember(profile.id) { mutableStateOf(false) }
    var content by remember(profile.id) { mutableStateOf("") }
    val listState = rememberLazyListState()

    val logFiles by produceState<List<FileEntry>>(emptyList(), profile.id) {
        value = try {
            graph.logService.logFiles(profile).filterNot { it.directory }
        } catch (e: IOException) {
            emptyList()
        }
    }

    LaunchedEffect(profile.id, selectedFile, live) {
        if (live && selectedFile == "${profile.logsPath}/latest.log") {
            content = ""
            graph.logService.tail(profile).collect { appended ->
                content = (content + appended).takeLast(500_000)
            }
        } else {
            content = try {
                graph.fileService.readText(profile, selectedFile)
            } catch (e: Exception) {
                "Could not read $selectedFile: ${e.message}"
            }
        }
    }

    val filtered = remember(content, filter) { LogFilter.apply(content, filter) }
    val lines = remember(filtered) { filtered.lines() }

    LaunchedEffect(lines.size, live) {
        if (live && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.width(240.dp)) {
            LazyColumn(Modifier.padding(8.dp)) {
                items(logFiles) { entry ->
                    TextButton(onClick = {
                        live = false
                        selectedFile = entry.path
                    }) {
                        Text(
                            entry.name,
                            color = if (entry.path == selectedFile) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = live,
                    onClick = {
                        live = !live
                        if (live) selectedFile = "${profile.logsPath}/latest.log"
                    },
                    label = { Text("Live tail") },
                )
            }
            Card(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(12.dp).horizontalScroll(rememberScrollState()),
                ) {
                    items(lines) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                    }
                }
            }
        }
    }
}
