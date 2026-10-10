package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Tab
import nl.jessedezwart.piglinpanel.model.ServerProfile

/** Mod management: installed mods, mod discovery/install, the dependency graph and compatibility. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var tab by remember(profile.id) { mutableStateOf(0) }
    val state = remember(profile.id) { ModsScreenState(graph, profile, scope, snackbar) }
    SideEffect { state.profile = profile }

    LaunchedEffect(profile.id) { state.load() }

    state.pendingPlan?.let { plan ->
        UpdatePlanDialog(
            plan = plan,
            onApply = state::applyPlanned,
            onDismiss = state::dismissPlan,
        )
    }

    val updatable = state.updatable
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(if (updatable.isEmpty()) "Updates" else "Updates (${updatable.size})") },
            )
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Browse") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Dependency graph") })
            Tab(selected = tab == 4, onClick = { tab = 4 }, text = { Text("Compatibility") })
        }
        Box(Modifier.fillMaxSize().padding(top = 6.dp)) {
            when (tab) {
                0 -> InstalledModsTab(state, profile)
                1 -> UpdatesTab(state, profile)
                2 -> BrowseModsTab(
                    graph = graph,
                    profile = profile,
                    snackbar = snackbar,
                    onInstalled = { state.rescan(checkUpdates = true) },
                )
                // Reads the same scan the Installed tab shows, so neither needs a second scan.
                3 -> DependencyGraphTab(mods = state.mods, groups = state.groups)
                else -> CompatibilityTab(
                    graph = graph,
                    profile = profile,
                    groups = state.groups,
                    snackbar = snackbar,
                )
            }
        }
    }
}
