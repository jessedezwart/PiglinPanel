package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.mods.ScanProgress
import nl.jessedezwart.piglinpanel.search.ModSource

/**
 * The repository page of the project [update] was resolved against, or null for a mod no repository
 * knows. Modrinth wins the lookup wherever it knows a file, so this is its page whenever it has one.
 */
private fun projectUrl(update: ModUpdate?): String? {
    val projectId = update?.projectId ?: return null
    return when (update.source) {
        ModSource.MODRINTH -> "https://modrinth.com/mod/$projectId"
        ModSource.CURSEFORGE -> "https://www.curseforge.com/projects/$projectId"
    }
}

/** Makes a mod name open its repository page when it has one. */
@Composable
internal fun Modifier.projectLink(update: ModUpdate?, enabled: Boolean = true): Modifier {
    val url = projectUrl(update) ?: return this
    val uriHandler = LocalUriHandler.current
    return handCursor().clickable(enabled = enabled) { uriHandler.openUri(url) }
}

/** A mod's icon at row size, or the empty space it would take so the columns still line up. */
@Composable
internal fun ModIcon(url: String?) {
    Box(Modifier.size(ROW_ICON)) {
        url?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(ROW_ICON)) }
    }
}

/** The update-check controls both the Installed and the Updates tab start with. */
@Composable
internal fun UpdateCheckButtons(
    task: String?,
    rescan: (checkUpdates: Boolean, forceRefresh: Boolean) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val busy = task != null
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { rescan(true, false) }, enabled = !busy) {
            Text(if (task == UPDATE_CHECK_TASK) "Checking…" else "Check updates")
        }
        OutlinedButton(onClick = { rescan(true, true) }, enabled = !busy) {
            Text("Force refresh")
        }
        extra()
        if (busy && task != UPDATE_CHECK_TASK) {
            Text("$task…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ScanProgressBar(p: ScanProgress) {
    Column {
        Text(
            when (p.phase) {
                "hashing" -> "Hashing ${p.done}/${p.total}: ${p.fileName ?: ""}"
                "updating" -> "Updating ${p.done + 1}/${p.total}: ${p.fileName ?: ""}"
                "downloading" -> "Checking ${p.done}/${p.total}: ${p.fileName ?: ""}"
                else -> "Looking up updates for ${p.total} mods…"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
