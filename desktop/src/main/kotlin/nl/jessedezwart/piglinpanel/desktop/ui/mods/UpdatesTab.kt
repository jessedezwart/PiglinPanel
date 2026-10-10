package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSourceBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.RowAction
import nl.jessedezwart.piglinpanel.desktop.ui.common.RowCheckbox
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.PlannedUpdate
import nl.jessedezwart.piglinpanel.mods.RequiredDependency
import nl.jessedezwart.piglinpanel.mods.UpdatePlan

/**
 * Every pending update with what it depends on and the release notes of the version it would
 * install, so a batch can be reviewed before it is applied. Everything starts ticked: the usual
 * answer is "all of it", and unticking the odd one out is the exception.
 *
 * Notes are fetched when the tab opens — Modrinth's usually came with the scan already — and kept
 * in [ModsScreenState.changelogs] for the rest of the session.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdatesTab(state: ModsScreenState, profile: ServerProfile) {
    val busy = state.busy
    val updatable = state.updatable
    val updatableKeys = remember(updatable) { updatable.map { it.first.key }.toSet() }

    /**
     * Mods unticked by the user. Tracked as exclusions rather than selections, so every update a
     * new check turns up arrives ticked.
     */
    val deselected = remember(profile.id) { mutableStateSetOf<String>() }

    // A mod that was updated (or vanished) no longer needs remembering as unticked.
    LaunchedEffect(updatableKeys) { deselected.retainAll(updatableKeys) }

    val targets = updatable.filter { it.first.key !in deselected }

    /** Which mod each enabled jar belongs to, so a dependency can be named and its update spotted. */
    val groupByPath = remember(state.groups) {
        state.groups.mapNotNull { group -> group.active?.let { it.relativePath to group } }.toMap()
    }
    val dependencies = remember(state.mods, updatable) {
        state.requiredDependenciesOf(updatable.map { it.second })
    }

    LaunchedEffect(updatable) { state.fetchChangelogs() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        UpdateCheckButtons(task = state.task, rescan = state::rescan) {
            if (updatable.isNotEmpty()) {
                RowCheckbox(
                    state = when (deselected.size) {
                        0 -> ToggleableState.On
                        updatable.size -> ToggleableState.Off
                        else -> ToggleableState.Indeterminate
                    },
                    enabled = !busy,
                    contentDescription = "Select all updates",
                    onClick = {
                        val noneSelected = deselected.size == updatable.size
                        deselected.clear()
                        if (!noneSelected) deselected.addAll(updatableKeys)
                    },
                )
            }
            Button(onClick = { state.applyUpdates(targets) }, enabled = !busy && targets.isNotEmpty()) {
                Text(
                    if (targets.size == updatable.size) {
                        "Update all (${updatable.size})"
                    } else {
                        "Update selected (${targets.size} of ${updatable.size})"
                    },
                )
            }
        }
        state.progress?.let { ScanProgressBar(it) }
        if (updatable.isEmpty() && !busy) {
            Text(
                "No updates pending. Check updates to look for new versions.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(updatable, key = { it.first.key }) { (group, file) ->
                val update = group.update ?: return@items
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RowCheckbox(
                            state = ToggleableState(group.key !in deselected),
                            enabled = !busy,
                            contentDescription = "Select ${group.displayName} for update",
                            onClick = { if (!deselected.add(group.key)) deselected.remove(group.key) },
                        )
                        ModIcon(group.iconUrl)
                        ModSourceBadge(update.source)
                        Text(
                            group.displayName,
                            Modifier.projectLink(update),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${file.fileName} → ${update.latestVersionName ?: update.latestVersionId}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RowAction("Ignore", enabled = !busy) { state.ignoreUpdate(group, true) }
                        // Through the same planner as the batch: a single update can strand a
                        // dependent just as well.
                        RowAction("Update only this", enabled = !busy) { state.applyUpdates(listOf(group to file)) }
                    }
                    val required = dependencies[file.relativePath].orEmpty()
                    if (required.isNotEmpty()) {
                        FlowRow(
                            Modifier.padding(start = ROW_ICON + 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "Requires:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            required.forEach { dependency ->
                                DependencyLabel(
                                    dependency = dependency,
                                    provider = dependency.provider?.let { groupByPath[it.relativePath] },
                                    updatableKeys = updatableKeys,
                                    deselected = deselected,
                                )
                            }
                        }
                    }
                    when (val changelog = state.changelogs[changelogKey(group, update)]) {
                        null, ChangelogState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is ChangelogState.Failed -> Text(
                            "Could not load changelog: ${changelog.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is ChangelogState.Loaded -> if (changelog.text == null) {
                            Text(
                                "No changelog provided.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ChangelogMarkdown(changelog.text)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/**
 * One dependency of a pending update: the mod providing it, and whether that mod is itself being
 * updated in this batch — the case worth reading twice, since the two changelogs belong together.
 * Read from the jar installed now; the new version may ask for something different, which the
 * planner checks once the jars are downloaded.
 */
@Composable
private fun DependencyLabel(
    dependency: RequiredDependency,
    provider: ModGroup?,
    updatableKeys: Set<String>,
    deselected: Set<String>,
) {
    val name = provider?.displayName ?: dependency.id
    val (suffix, color) = when {
        dependency.provider == null -> " (missing)" to MaterialTheme.colorScheme.error
        provider != null && provider.key in updatableKeys ->
            if (provider.key in deselected) {
                " (update not selected)" to MaterialTheme.colorScheme.tertiary
            } else {
                " (also updating)" to MaterialTheme.colorScheme.primary
            }
        else -> "" to MaterialTheme.colorScheme.onSurface
    }
    Text(
        name + suffix,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/**
 * What a planned batch would do, shown when some of it would break the pack.
 *
 * The safe updates are offered on their own because that is almost always the right answer: a
 * batch of thirty where two conflict should still install twenty-eight. Applying everything stays
 * available, since the planner reads ranges mod authors do sometimes get wrong.
 */
@Composable
internal fun UpdatePlanDialog(
    plan: UpdatePlan,
    onApply: (List<PlannedUpdate>) -> Unit,
    onDismiss: () -> Unit,
) {
    val blocked = plan.blocked
    val failed = plan.failed
    val safe = plan.safe
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply ${safe.size} of ${plan.entries.size} updates?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocked.isNotEmpty()) {
                    Text(
                        "These updates would break the dependency chain:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SelectionContainer {
                        Text(
                            blocked.joinToString("\n") { entry ->
                                "  • ${entry.displayName} — ${entry.problems.joinToString("; ")}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (failed.isNotEmpty()) {
                    Text("These could not be downloaded:", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(
                            failed.joinToString("\n") { "  • ${it.displayName} — ${it.failure}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    if (safe.isEmpty()) {
                        "Nothing in this batch is safe to apply."
                    } else {
                        "The other ${safe.size} can be applied as they are."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApply(safe) }, enabled = safe.isNotEmpty()) {
                Text("Apply ${safe.size} safe")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    // The download failures are left out: there is nothing to write for them.
                    onClick = { onApply(safe + blocked) },
                    enabled = blocked.isNotEmpty(),
                ) {
                    Text("Apply all anyway")
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
