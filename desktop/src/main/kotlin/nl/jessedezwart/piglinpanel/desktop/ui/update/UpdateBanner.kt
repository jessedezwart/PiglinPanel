package nl.jessedezwart.piglinpanel.desktop.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.desktop.update.AppUpdater
import nl.jessedezwart.piglinpanel.desktop.update.UpdateState

/**
 * A strip across the top of the window while there is a newer release to offer. It takes no space
 * at all otherwise. [onInstall] is called once the installer is downloaded and the user wants it
 * applied; the app does not come back from it.
 */
@Composable
fun UpdateBanner(updater: AppUpdater, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    val current = state
    if (current == UpdateState.Idle) return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (current) {
                    UpdateState.Idle -> {}

                    is UpdateState.Available -> Text(
                        "Piglin Panel ${current.version} is available — you have ${updater.currentVersion}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is UpdateState.Downloading -> {
                        Text(
                            "Downloading Piglin Panel ${current.version}…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val fraction = current.fraction
                        if (fraction == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    is UpdateState.Ready -> Text(
                        "Piglin Panel ${current.version} is ready to install. The app restarts to finish.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is UpdateState.Failed -> Text(
                        "Could not download Piglin Panel ${current.version}: ${current.message}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            when (current) {
                is UpdateState.Available -> Button(onClick = updater::download) { Text("Download") }
                is UpdateState.Ready -> Button(onClick = onInstall) { Text("Restart and install") }
                is UpdateState.Failed -> Button(onClick = updater::download) { Text("Retry") }
                else -> {}
            }
            if (current !is UpdateState.Downloading) {
                TextButton(onClick = updater::dismiss) { Text("Later") }
            }
        }
    }
}
