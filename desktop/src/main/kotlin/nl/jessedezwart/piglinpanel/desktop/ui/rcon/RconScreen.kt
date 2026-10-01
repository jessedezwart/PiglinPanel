package nl.jessedezwart.piglinpanel.desktop.ui.rcon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.model.ServerProfile

private data class RconLine(val text: String, val isCommand: Boolean, val isError: Boolean = false)

/** RCON console: command input with scrollback. Replaces rcon.html + /ws/console usage. */
@Composable
fun RconScreen(graph: AppGraph, profile: ServerProfile) {
    val scope = rememberCoroutineScope()
    val scrollback = remember(profile.id) { mutableStateListOf<RconLine>() }
    var command by remember(profile.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun submit() {
        val cmd = command.trim()
        if (cmd.isEmpty() || busy) return
        command = ""
        scrollback += RconLine("> $cmd", isCommand = true)
        busy = true
        scope.launch {
            try {
                val response = graph.rconService.execute(profile, cmd)
                scrollback += RconLine(response.ifBlank { "(no response)" }, isCommand = false)
            } catch (e: Exception) {
                scrollback += RconLine("Error: ${e.message}", isCommand = false, isError = true)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(scrollback.size) {
        if (scrollback.isNotEmpty()) {
            listState.scrollToItem(scrollback.size - 1)
        }
    }

    fun activate() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val activated = graph.registry.activateRcon(profile)
                scrollback += RconLine(
                    "RCON enabled on port ${activated.rcon.port} with a generated password. " +
                        "Restart the server for it to take effect.",
                    isCommand = false,
                )
            } catch (e: Exception) {
                scrollback += RconLine("Could not activate RCON: ${e.message}", isCommand = false, isError = true)
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!profile.rcon.enabled) {
            Card {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "RCON is not enabled for this server (enable-rcon in server.properties).",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = ::activate, enabled = !busy) { Text("Activate RCON") }
                }
            }
        }
        Card(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(scrollback) { line ->
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            line.isError -> MaterialTheme.colorScheme.error
                            line.isCommand -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command") },
                singleLine = true,
                enabled = profile.rcon.enabled && !busy,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = ::submit, enabled = profile.rcon.enabled && !busy) {
                Text(if (busy) "Running…" else "Send")
            }
        }
    }
}
