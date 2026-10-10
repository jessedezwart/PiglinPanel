package nl.jessedezwart.piglinpanel.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import java.io.File
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.ui.common.IconButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton

/**
 * Detail-pane scaffolding shared by both panes: a fixed header carrying the title and the actions,
 * then a scrolling stack of [SettingsSection] cards.
 */
@Composable
internal fun DetailPane(
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** One titled group of related fields. Every setting lives in exactly one of these. */
@Composable
internal fun SettingsSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * Trailing-icon button that opens the operating system's own folder chooser — IFileDialog on
 * Windows, NSOpenPanel on macOS, the XDG portal on Linux — starting at [startDirectory] when that
 * folder exists. The dialog runs off the UI thread and calls [onPicked] only if something is chosen.
 */
@Composable
internal fun BrowseFolderButton(startDirectory: String?, onPicked: (File) -> Unit) {
    val start = remember(startDirectory) {
        startDirectory?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
            ?.let { PlatformFile(it) }
    }
    val launcher = rememberDirectoryPickerLauncher(directory = start) { picked ->
        picked?.file?.let(onPicked)
    }
    IconButton(onClick = launcher::launch) {
        Icon(Icons.Default.Folder, contentDescription = "Browse for folder")
    }
}

/** Destructive action in a detail-pane header. */
@Composable
internal fun DeleteButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp))
        Text("Delete", Modifier.padding(start = 6.dp))
    }
}
