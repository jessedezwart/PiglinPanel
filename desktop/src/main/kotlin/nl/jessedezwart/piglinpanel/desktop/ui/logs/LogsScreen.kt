package nl.jessedezwart.piglinpanel.desktop.ui.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_TEXT_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.logs.LogFilter
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException
import java.awt.datatransfer.StringSelection

/**
 * One log line. Smaller than `bodySmall` and with the line height pulled down to the glyph height,
 * which is what actually decides how many lines fit on screen.
 */
private val LOG_LINE = TextStyle(fontSize = 11.sp, lineHeight = 12.sp)

/** Log viewing with file picker, filter, and live tail. Replaces logs.html + /ws/logs. */
@OptIn(ExperimentalComposeUiApi::class) // ClipEntry's desktop constructor
@Composable
fun LogsScreen(graph: AppGraph, profile: ServerProfile) {
    var selectedFile by remember(profile.id) { mutableStateOf("${profile.logsPath}/latest.log") }
    var filter by remember(profile.id) { mutableStateOf("") }
    var live by remember(profile.id) { mutableStateOf(false) }
    var content by remember(profile.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val logFiles by produceState<List<FileEntry>>(emptyList(), profile.id) {
        value = try {
            graph.logService.logFiles(profile).filterNot { it.directory }.sortedByDescending { it.name }
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

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.width(200.dp)) {
            LazyColumn(Modifier.padding(4.dp)) {
                items(logFiles) { entry ->
                    TextButton(
                        onClick = {
                            live = false
                            selectedFile = entry.path
                        },
                        modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT),
                        contentPadding = ROW_TEXT_BUTTON_PADDING,
                    ) {
                        Text(
                            entry.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.path == selectedFile) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                IconButton(
                    onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(filtered))) } },
                    enabled = filtered.isNotEmpty(),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy log to clipboard")
                }
            }
            Card(Modifier.fillMaxSize()) {
                // Selection only covers the lines the LazyColumn has composed; the copy button above is
                // the way to get the whole log.
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(6.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        items(lines) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, style = LOG_LINE, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
