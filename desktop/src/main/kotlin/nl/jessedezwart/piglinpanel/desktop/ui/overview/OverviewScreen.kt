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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
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

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val online = status?.online == true
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(12.dp)
                            .clip(CircleShape)
                            .background(if (online) Color(0xFF43A047) else Color(0xFFE53935)),
                    )
                    Text(
                        if (online) "Online" else "Offline",
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                status?.let { Text("${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall) }
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
