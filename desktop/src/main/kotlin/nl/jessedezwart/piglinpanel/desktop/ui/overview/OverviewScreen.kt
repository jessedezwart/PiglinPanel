package nl.jessedezwart.piglinpanel.desktop.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.jessedezwart.piglinpanel.control.ServerStartCommand
import nl.jessedezwart.piglinpanel.control.StartResult
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.status.ServerStatus
import nl.jessedezwart.piglinpanel.sysinfo.Metrics
import nl.jessedezwart.piglinpanel.sysinfo.Specs

/** Server status, host specs, and live metrics; polls every 5 s without overlapping runs. */
@Composable
fun OverviewScreen(graph: AppGraph, profile: ServerProfile) {
    val status by produceState<ServerStatus?>(null, profile.id) {
        while (isActive) {
            value = graph.statusService.statusFor(profile)
            delay(5_000)
        }
    }
    val specs by produceState<Specs?>(null, profile.id) {
        value = graph.systemInfoService.specs(profile)
    }
    val metrics by produceState<Metrics?>(null, profile.id) {
        while (isActive) {
            value = graph.systemInfoService.metrics(profile)
            delay(5_000)
        }
    }

    val scope = rememberCoroutineScope()
    val starter = remember(profile.id) { ServerStarter(graph, profile, scope) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val online = status?.online == true
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(12.dp)
                            .clip(CircleShape)
                            .background(if (online) Color(0xFF43A047) else Color(0xFFE53935)),
                    )
                    Text(
                        if (online) "Online" else "Offline",
                        Modifier.padding(start = 8.dp).weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = starter::start,
                        enabled = !online && !starter.busy && starter.unavailable == null,
                    ) {
                        Text(if (starter.busy) "Starting…" else "Start server")
                    }
                }
                status?.let { Text("${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall) }
                val note = starter.error ?: starter.unavailable?.takeIf { !online }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (starter.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Host specifications", style = MaterialTheme.typography.titleMedium)
                when (val s = specs) {
                    null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    else -> if (!s.available) {
                        Text("Unavailable: ${s.error}", color = MaterialTheme.colorScheme.error)
                    } else {
                        SpecRow("OS", s.os)
                        SpecRow("CPU", s.cpu)
                        SpecRow("Motherboard", s.motherboard)
                        SpecRow("Memory", s.memory)
                        SpecRow("GPU", s.gpu)
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Live metrics", style = MaterialTheme.typography.titleMedium)
                when (val m = metrics) {
                    null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    else -> if (!m.available) {
                        Text("Unavailable: ${m.error}", color = MaterialTheme.colorScheme.error)
                    } else {
                        if (m.cpuKnown()) {
                            MeterRow("CPU", m.cpuPercent, "${m.cpuPercent}%")
                        } else {
                            SpecRow("CPU", "Unknown")
                        }
                        MeterRow(
                            "Memory",
                            m.memPercent(),
                            "${format1(m.memUsedMb)} / ${format1(m.memTotalMb)} GB (${m.memPercent()}%)",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drives the Start button: runs the start command, then waits for the server port to open, because
 * a start command returning successfully only means the JVM launched, not that anyone can join yet.
 */
private class ServerStarter(
    private val graph: AppGraph,
    private val profile: ServerProfile,
    private val scope: CoroutineScope,
) {
    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** Why the button is disabled, or null when the server can be started. */
    val unavailable: String?
        get() = graph.serverControlService.unavailableReason(profile)

    fun start() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                when (val result = graph.serverControlService.start(profile)) {
                    is StartResult.Failed -> error = result.message
                    StartResult.Started -> awaitOnline()
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun awaitOnline() {
        val opened = withTimeoutOrNull(ONLINE_TIMEOUT_MILLIS) {
            while (!graph.statusService.statusFor(profile).online) {
                delay(3_000)
            }
            true
        }
        if (opened == null) {
            error = "The server started but its port is still closed after two minutes." +
                " Its output is in ${ServerStartCommand.LOG_FILE}, in the server folder."
        }
    }

    private companion object {
        const val ONLINE_TIMEOUT_MILLIS = 120_000L
    }
}

private fun format1(megabytes: Long): String {
    val tenths = (megabytes * 10 + 512) / 1024
    return "${tenths / 10}.${tenths % 10}"
}

@Composable
private fun SpecRow(label: String, value: String?) {
    Row {
        Text(label, Modifier.padding(end = 12.dp).width(120.dp), style = MaterialTheme.typography.labelLarge)
        Text(value ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MeterRow(label: String, percent: Int, text: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { percent.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
