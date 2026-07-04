package nl.jessedezwart.piglinpanel.desktop.ui.console

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.console.PanelConsole

/** Streams the panel's own log output; the SharedFlow replay buffer provides the history. */
@Composable
fun ConsoleScreen() {
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        PanelConsole.lines.collect { line ->
            lines += line.trimEnd()
            if (lines.size > 500) {
                lines.removeAt(0)
            }
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    Card(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
            itemsIndexed(lines) { _, line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
