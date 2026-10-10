package nl.jessedezwart.piglinpanel.desktop.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.control.ActionResult
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.model.ServerAction
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.status.ServerStatus
import nl.jessedezwart.piglinpanel.sysinfo.Metrics
import nl.jessedezwart.piglinpanel.sysinfo.Specs

/**
 * Server status, host specs, metrics and the profile's actions. Status and metrics are read once when
 * the screen opens and again on Refresh — never on a timer, so an idle panel puts no load on the host.
 */
@Composable
fun OverviewScreen(graph: AppGraph, profile: ServerProfile) {
    val scope = rememberCoroutineScope()
    var status by remember(profile.id) { mutableStateOf<ServerStatus?>(null) }
    var metrics by remember(profile.id) { mutableStateOf<Metrics?>(null) }
    var refreshing by remember(profile.id) { mutableStateOf(false) }
    val specs by produceState<Specs?>(null, profile.id) {
        value = graph.systemInfoService.specs(profile)
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                coroutineScope {
                    launch { status = graph.statusService.statusFor(profile) }
                    launch { metrics = graph.systemInfoService.metrics(profile) }
                }
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(profile.id) { refresh() }

    // Keyed on the whole profile, so an edited working directory or SSH login is what the next run uses.
    val actions = remember(profile) { ActionRunner(graph, profile, scope, onFinished = ::refresh) }

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
                        when {
                            status == null -> "Checking…"
                            online -> "Online"
                            else -> "Offline"
                        },
                        Modifier.padding(start = 8.dp).weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = ::refresh, enabled = !refreshing) {
                        Text(if (refreshing) "Refreshing…" else "Refresh")
                    }
                }
                status?.let { Text("${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall) }
            }
        }

        ActionsCard(profile, actions, unavailable = graph.serverActionService.unavailableReason(profile))

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
                Text("Metrics", style = MaterialTheme.typography.titleMedium)
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

/** Runs one configured action at a time and keeps the outcome of the last one for display. */
private class ActionRunner(
    private val graph: AppGraph,
    private val profile: ServerProfile,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
) {
    /** The action currently running, or null when idle. */
    var running by mutableStateOf<ServerAction?>(null)
        private set

    var last by mutableStateOf<Pair<ServerAction, ActionResult>?>(null)
        private set

    /** An action waiting on the user's go-ahead, because it is configured to ask first. */
    var pending by mutableStateOf<ServerAction?>(null)

    fun request(action: ServerAction) {
        if (action.confirm) pending = action else run(action)
    }

    fun run(action: ServerAction) {
        pending = null
        if (running != null) return
        running = action
        scope.launch {
            try {
                last = action to graph.serverActionService.run(profile, action)
            } finally {
                running = null
            }
            // A restart or stop changes what the status card should say.
            onFinished()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(profile: ServerProfile, runner: ActionRunner, unavailable: String?) {
    runner.pending?.let { action ->
        AlertDialog(
            onDismissRequest = { runner.pending = null },
            title = { Text("Run ${action.displayName}?") },
            text = {
                Text(
                    action.command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
            confirmButton = { Button(onClick = { runner.run(action) }) { Text("Run") } },
            dismissButton = { OutlinedButton(onClick = { runner.pending = null }) { Text("Cancel") } },
        )
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Actions", style = MaterialTheme.typography.titleMedium)
            when {
                unavailable != null -> Text(
                    unavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profile.actions.isEmpty() -> Text(
                    "No actions yet. Add commands such as docker compose restart under Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profile.actions.forEach { action ->
                        OutlinedButton(onClick = { runner.request(action) }, enabled = runner.running == null) {
                            Text(if (runner.running?.id == action.id) "${action.displayName}…" else action.displayName)
                        }
                    }
                }
            }
            runner.last?.let { (action, result) ->
                val failed = result is ActionResult.Failed
                val body = when (result) {
                    is ActionResult.Succeeded -> result.output
                    is ActionResult.Failed -> result.message
                }
                Text(
                    if (failed) "${action.displayName} failed" else "${action.displayName} finished",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (body.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
