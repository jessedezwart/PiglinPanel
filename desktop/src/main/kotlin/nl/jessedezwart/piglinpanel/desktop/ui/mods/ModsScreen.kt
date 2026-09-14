package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.FilterChip
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSideBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSourceBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.OutlinedButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_BUTTON_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_TEXT_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.Tab
import nl.jessedezwart.piglinpanel.desktop.ui.common.TextButton
import nl.jessedezwart.piglinpanel.desktop.ui.common.TriStateCheckbox
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.formatDownloads
import nl.jessedezwart.piglinpanel.model.formatSize
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.ModSide
import nl.jessedezwart.piglinpanel.mods.ScanProgress
import nl.jessedezwart.piglinpanel.search.ModSource

/** The size Material always draws a checkbox at, regardless of the size it is laid out in. */
private val CHECKBOX_DRAW_SIZE = 20.dp

/**
 * Column widths of the installed-mods table. The header and the rows read from the same constants,
 * which is the whole reason the columns line up.
 */
private val COL_SELECT = 18.dp
private val COL_SIDE = 54.dp
private val COL_REPO = 44.dp
private val COL_DOWNLOADS = 68.dp
private val COL_SIZE = 64.dp
private val COL_DATE = 78.dp
private val COL_ACTIONS = 290.dp
private const val COL_NAME_WEIGHT = 2.0f
private const val COL_FILE_WEIGHT = 2.4f

/** A pending disable/delete that other installed mods depend on, held until the user confirms it. */
private data class Breakage(
    val action: String,
    val title: String,
    val dependents: List<String>,
    val proceed: () -> Unit,
)

/** A mod mutation: runs [block], then rescans (checking updates when [checkUpdatesAfter]). */
private typealias Mutation = (label: String, checkUpdatesAfter: Boolean, block: suspend () -> Unit) -> Unit

/** One line of the table: a single jar, and the mod it is a version of. */
private data class ModRow(val group: ModGroup, val file: ModFile, val active: Boolean)

/** What the table is ordered by. */
private enum class SortKey { NAME, FILE, SIDE, DOWNLOADS, SIZE, DATE }

/** Where one mod's changelog stands in the overview dialog. */
private sealed interface ChangelogState {
    data object Loading : ChangelogState
    data class Loaded(val text: String?) : ChangelogState
    data class Failed(val message: String) : ChangelogState
}

/** Cache key for a changelog: the notes belong to the version, not the mod. */
private fun changelogKey(group: ModGroup, update: ModUpdate): String = "${group.key}@${update.latestVersionId}"

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
private fun Modifier.projectLink(update: ModUpdate?, enabled: Boolean = true): Modifier {
    val url = projectUrl(update) ?: return this
    val uriHandler = LocalUriHandler.current
    return handCursor().clickable(enabled = enabled) { uriHandler.openUri(url) }
}

/** Whether the user chose to skip the exact update [group] is currently offering. */
private fun ModGroup.updateIgnored(profile: ServerProfile): Boolean {
    val latest = update?.latestVersionId ?: return false
    return profile.ignoredUpdates[key] == latest
}

/** Mod management: installed mods, mod discovery/install, the dependency graph and compatibility. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(graph: AppGraph, profile: ServerProfile, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var tab by remember(profile.id) { mutableStateOf(0) }
    var groups by remember(profile.id) { mutableStateOf<List<ModGroup>>(emptyList()) }

    /** The ungrouped scan result, kept because dependency questions are asked per file, not per mod. */
    var mods by remember(profile.id) { mutableStateOf<List<ModFile>>(emptyList()) }
    var progress by remember(profile.id) { mutableStateOf<ScanProgress?>(null) }

    /** Label of the running operation, or null when idle. All controls are disabled while set. */
    var task by remember(profile.id) { mutableStateOf<String?>(null) }
    val busy = task != null

    // Snackbars suspend until dismissed, so they are always shown off the task coroutine —
    // otherwise the UI would stay disabled for the lifetime of the message.
    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun scan(checkUpdates: Boolean, forceRefresh: Boolean) {
        progress = null
        val scanned = graph.modService.scan(profile, checkUpdates, forceRefresh) { p -> progress = p }
        mods = scanned
        groups = graph.modService.group(scanned)
    }

    /** Runs [block] as the single active operation, keeping the UI disabled until it finishes. */
    fun launchTask(label: String, block: suspend () -> Unit) {
        if (busy) return
        task = label
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                notify("$label failed: ${e.message}")
            } finally {
                progress = null
                task = null
            }
        }
    }

    fun rescan(checkUpdates: Boolean, forceRefresh: Boolean = false) {
        launchTask("Scan") { scan(checkUpdates, forceRefresh) }
    }

    val mutate: Mutation = { label, checkUpdatesAfter, block ->
        launchTask(label) {
            block()
            scan(checkUpdatesAfter, false)
        }
    }

    fun applyUpdates(targets: List<Pair<ModGroup, ModFile>>) {
        if (targets.isEmpty()) return
        launchTask("Update") {
            var updated = 0
            val failures = mutableListOf<String>()
            targets.forEachIndexed { index, (group, mod) ->
                progress = ScanProgress("updating", index, targets.size, group.displayName)
                try {
                    graph.modService.applyUpdate(profile, mod)
                    updated++
                } catch (e: Exception) {
                    failures += group.displayName
                }
            }
            progress = null
            notify(
                if (failures.isEmpty()) {
                    "Updated $updated mod(s)"
                } else {
                    "Updated $updated mod(s); ${failures.size} failed: ${failures.joinToString()}"
                },
            )
            scan(checkUpdates = true, forceRefresh = false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Browse") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Dependency graph") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Compatibility") })
        }
        Box(Modifier.fillMaxSize().padding(top = 6.dp)) {
            when (tab) {
                0 -> InstalledModsTab(
                    graph = graph,
                    profile = profile,
                    groups = groups,
                    mods = mods,
                    progress = progress,
                    task = task,
                    rescan = ::rescan,
                    applyUpdates = ::applyUpdates,
                    mutate = mutate,
                    notify = ::notify,
                )
                1 -> BrowseModsTab(
                    graph = graph,
                    profile = profile,
                    snackbar = snackbar,
                    onInstalled = { rescan(checkUpdates = true) },
                )
                // Reads the same scan the Installed tab shows, so neither needs a second scan.
                2 -> DependencyGraphTab(mods = mods, groups = groups)
                else -> CompatibilityTab(
                    graph = graph,
                    profile = profile,
                    groups = groups,
                    snackbar = snackbar,
                )
            }
        }
    }
}

@Composable
private fun InstalledModsTab(
    graph: AppGraph,
    profile: ServerProfile,
    groups: List<ModGroup>,
    mods: List<ModFile>,
    progress: ScanProgress?,
    task: String?,
    rescan: (checkUpdates: Boolean, forceRefresh: Boolean) -> Unit,
    applyUpdates: (List<Pair<ModGroup, ModFile>>) -> Unit,
    mutate: Mutation,
    notify: (String) -> Unit,
) {
    val busy = task != null
    val scope = rememberCoroutineScope()

    /** Set while a removal is waiting on confirmation because other mods depend on the target. */
    var breakage by remember(profile.id) { mutableStateOf<Breakage?>(null) }

    var query by remember(profile.id) { mutableStateOf("") }

    /** Off by default: the disabled versions are history, and the table is about what is running. */
    var showDisabled by remember(profile.id) { mutableStateOf(false) }

    /** Narrows the list to mods with no enabled jar left, for finding something to re-activate. */
    var disabledOnly by remember(profile.id) { mutableStateOf(false) }

    /** Narrows the list to libraries nothing installed uses, for clearing out leftovers. */
    var unusedLibrariesOnly by remember(profile.id) { mutableStateOf(false) }
    var sideFilter by remember(profile.id) { mutableStateOf<ModSide?>(null) }

    /** Narrows the list to mods with an update to apply, ignored ones excluded. */
    var updatableOnly by remember(profile.id) { mutableStateOf(false) }
    var showChangelogs by remember(profile.id) { mutableStateOf(false) }

    /** Changelogs already fetched this session, so reopening the overview does not refetch them. */
    val changelogs = remember(profile.id) { mutableStateMapOf<String, ChangelogState>() }
    var sortKey by remember(profile.id) { mutableStateOf(SortKey.NAME) }
    var ascending by remember(profile.id) { mutableStateOf(true) }

    /** More than one mod folder means a bare file name no longer says which jar is meant. */
    val showFolder = profile.modFolders.size > 1

    val fullyDisabled = remember(groups) { groups.filter { it.active == null } }
    val unusedLibraries = remember(groups) { groups.filter { it.unusedLibrary } }

    val updatable = remember(groups, profile.ignoredUpdates) {
        groups.mapNotNull { group ->
            val active = group.active ?: return@mapNotNull null
            if (group.update?.available() == true && !group.updateIgnored(profile)) group to active else null
        }
    }
    val updatableKeys = remember(updatable) { updatable.map { it.first.key }.toSet() }

    val rows = remember(
        groups, query, showDisabled, disabledOnly, unusedLibrariesOnly, updatableOnly, updatableKeys, sideFilter,
        sortKey, ascending,
    ) {
        val matching = groups.filter { group ->
            (!disabledOnly || group.active == null) &&
                (!unusedLibrariesOnly || group.unusedLibrary) &&
                (!updatableOnly || group.key in updatableKeys) &&
                (sideFilter == null || group.side == sideFilter) &&
                group.matches(query)
        }
        val ordered = matching.sortedWith(comparatorFor(sortKey, ascending))
        buildList {
            for (group in ordered) {
                group.active?.let { add(ModRow(group, it, active = true)) }
                // A mod with nothing enabled has only its disabled versions to offer, so those stay
                // visible however the filter is set — otherwise the row could not be acted on.
                if (showDisabled || group.active == null) {
                    group.archived.forEach { add(ModRow(group, it, active = false)) }
                }
            }
        }
    }

    /**
     * Runs a disable/delete, first asking about any mods it would break. The check is skipped when
     * nothing is known to depend on the target — which includes jars whose manifest could not be
     * read, so this warns about what it can see rather than guaranteeing nothing else breaks.
     */
    fun removing(action: String, mod: ModFile, title: String, run: () -> Unit) {
        val dependents = graph.modService.dependentsBrokenBy(mods, mod)
        if (dependents.isEmpty()) run() else breakage = Breakage(action, title, dependents, run)
    }

    /** Skips (or, with a null version, un-skips) the update [group] is offering, for this profile. */
    fun ignoreUpdate(group: ModGroup, ignore: Boolean) {
        graph.registry.setIgnoredUpdate(profile.id, group.key, if (ignore) group.update?.latestVersionId else null)
    }

    /** Mods ticked for a targeted update. Empty means "all of them", so the button always works. */
    val selected = remember(profile.id) { mutableStateSetOf<String>() }

    // A mod that was updated (or vanished) is no longer a target, so it drops out of the selection
    // instead of quietly counting towards the next update.
    LaunchedEffect(updatableKeys) { selected.retainAll(updatableKeys) }

    val targets = if (selected.isEmpty()) updatable else updatable.filter { it.first.key in selected }
    val canUpdate = !busy && targets.isNotEmpty()

    breakage?.let { pending ->
        AlertDialog(
            onDismissRequest = { breakage = null },
            title = { Text("${pending.action} ${pending.title}?") },
            text = {
                Text(
                    "These mods require it and will fail to load:\n\n" +
                        pending.dependents.joinToString("\n") { "  • $it" },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val proceed = pending.proceed
                        breakage = null
                        proceed()
                    },
                ) {
                    Text("${pending.action} anyway")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { breakage = null }) { Text("Cancel") }
            },
        )
    }

    if (showChangelogs) {
        ChangelogDialog(
            graph = graph,
            updates = updatable,
            changelogs = changelogs,
            scope = scope,
            onIgnore = { group -> ignoreUpdate(group, true) },
            onDismiss = { showChangelogs = false },
        )
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { rescan(true, false) }, enabled = !busy) {
                Text(if (task == "Scan") "Scanning…" else "Scan & check updates")
            }
            if (updatable.isNotEmpty()) {
                RowCheckbox(
                    state = when (selected.size) {
                        0 -> ToggleableState.Off
                        updatable.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    },
                    enabled = !busy,
                    contentDescription = "Select all updatable mods",
                    onClick = {
                        val all = selected.size == updatable.size
                        selected.clear()
                        if (!all) selected.addAll(updatableKeys)
                    },
                )
            }
            Button(onClick = { applyUpdates(targets) }, enabled = canUpdate) {
                Text(
                    when {
                        updatable.isEmpty() -> "Update all"
                        selected.isEmpty() -> "Update all (${updatable.size})"
                        else -> "Update selected (${selected.size})"
                    },
                )
            }
            OutlinedButton(onClick = { showChangelogs = true }, enabled = updatable.isNotEmpty()) {
                Text("Changelogs")
            }
            OutlinedButton(onClick = { rescan(true, true) }, enabled = !busy) {
                Text("Force refresh")
            }
            OutlinedButton(onClick = { rescan(false, false) }, enabled = !busy) {
                Text("Quick scan")
            }
            if (busy && task != "Scan") {
                Text("$task…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter by name or file", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(240.dp),
            )
            FilterChip(
                selected = updatableOnly,
                onClick = { updatableOnly = !updatableOnly },
                label = { Text(if (updatable.isEmpty()) "Updatable" else "Updatable (${updatable.size})") },
            )
            FilterChip(
                selected = showDisabled,
                onClick = { showDisabled = !showDisabled },
                label = { Text("Show disabled versions") },
            )
            FilterChip(
                selected = disabledOnly,
                onClick = { disabledOnly = !disabledOnly },
                label = {
                    Text(if (fullyDisabled.isEmpty()) "Fully disabled" else "Fully disabled (${fullyDisabled.size})")
                },
            )
            FilterChip(
                selected = unusedLibrariesOnly,
                onClick = { unusedLibrariesOnly = !unusedLibrariesOnly },
                label = {
                    Text(if (unusedLibraries.isEmpty()) "Unused libraries" else "Unused libraries (${unusedLibraries.size})")
                },
            )
            FilterChip(
                selected = sideFilter == ModSide.CLIENT,
                onClick = { sideFilter = if (sideFilter == ModSide.CLIENT) null else ModSide.CLIENT },
                label = { Text("Client-only") },
            )
            FilterChip(
                selected = sideFilter == ModSide.SERVER,
                onClick = { sideFilter = if (sideFilter == ModSide.SERVER) null else ModSide.SERVER },
                label = { Text("Server-only") },
            )
            Text(
                "${rows.distinctBy { it.group.key }.size} of ${groups.size} mods",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        progress?.let { p ->
            Column {
                Text(
                    when (p.phase) {
                        "hashing" -> "Hashing ${p.done}/${p.total}: ${p.fileName ?: ""}"
                        "updating" -> "Updating ${p.done + 1}/${p.total}: ${p.fileName ?: ""}"
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
        if (groups.isEmpty() && !busy) {
            Text(
                "No mods scanned yet. Run a scan to list mods.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (rows.isEmpty() && !busy) {
            Text("No mods match the current filters.", style = MaterialTheme.typography.bodyMedium)
        }
        if (rows.isNotEmpty()) {
            TableHeader(
                sortKey = sortKey,
                ascending = ascending,
                onSort = { key ->
                    if (key == sortKey) ascending = !ascending else { sortKey = key; ascending = true }
                },
            )
            HorizontalDivider()
        }
        LazyColumn {
            items(rows, key = { it.file.relativePath }) { row ->
                ModTableRow(
                    row = row,
                    showFolder = showFolder,
                    enabled = !busy,
                    selected = row.group.key in selected,
                    ignored = row.group.updateIgnored(profile),
                    onIgnore = { ignore -> ignoreUpdate(row.group, ignore) },
                    onToggleSelected = {
                        if (!selected.add(row.group.key)) selected.remove(row.group.key)
                    },
                    onApplyUpdate = { mod ->
                        mutate("Update", true) {
                            graph.modService.applyUpdate(profile, mod)
                            notify("Updated ${row.group.displayName}")
                        }
                    },
                    // These rescans check updates: renaming a jar does not change its bytes, so the
                    // hashes and the update lookups both come straight from cache. Scanning without
                    // them would drop every mod's project identity and shatter the table into one
                    // row per file.
                    onDisable = { mod ->
                        removing("Disable", mod, row.group.displayName) {
                            mutate("Disable", true) { graph.modService.disablePath(profile, mod.relativePath) }
                        }
                    },
                    onActivate = { mod ->
                        mutate("Activate", true) {
                            graph.modService.activate(profile, mods, mod)
                            // Dependencies that are installed but still disabled are pointed out
                            // rather than enabled: what the server loads stays the user's call.
                            val needed = graph.modService.disabledDependenciesOf(mods, mod)
                            if (needed.isNotEmpty()) {
                                notify("Still disabled: ${needed.joinToString { it.fileName }}")
                            }
                        }
                    },
                    onDelete = { mod ->
                        removing("Delete", mod, row.group.displayName) {
                            mutate("Delete", true) { graph.modService.delete(profile, mod) }
                        }
                    },
                )
            }
        }
    }
}

/** Whether a mod answers to [query], by its own name or by any of its file names. */
private fun ModGroup.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim()
    return displayName.contains(needle, ignoreCase = true) ||
        active?.fileName?.contains(needle, ignoreCase = true) == true ||
        archived.any { it.fileName.contains(needle, ignoreCase = true) }
}

private fun comparatorFor(key: SortKey, ascending: Boolean): Comparator<ModGroup> {
    val base: Comparator<ModGroup> = when (key) {
        SortKey.NAME -> compareBy { it.displayName.lowercase() }
        SortKey.FILE -> compareBy { (it.active ?: it.archived.firstOrNull())?.fileName?.lowercase() ?: "" }
        // Named mods first, so ordering by side gathers the one-sided ones rather than the silent ones.
        SortKey.SIDE -> compareBy<ModGroup> { it.side == ModSide.UNKNOWN }.thenBy { it.side.name }
        SortKey.DOWNLOADS -> compareBy { it.downloads }
        SortKey.SIZE -> compareBy { (it.active ?: it.archived.firstOrNull())?.size ?: 0L }
        SortKey.DATE -> compareBy { it.modifiedAt?.toEpochMilliseconds() ?: 0L }
    }
    return if (ascending) base else base.reversed()
}

@Composable
private fun TableHeader(sortKey: SortKey, ascending: Boolean, onSort: (SortKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(COL_SELECT))
        Spacer(Modifier.width(ROW_ICON))
        HeaderCell("Name", SortKey.NAME, sortKey, ascending, onSort, Modifier.weight(COL_NAME_WEIGHT))
        HeaderCell("File", SortKey.FILE, sortKey, ascending, onSort, Modifier.weight(COL_FILE_WEIGHT))
        HeaderCell("Side", SortKey.SIDE, sortKey, ascending, onSort, Modifier.width(COL_SIDE))
        HeaderLabel("Repo", Modifier.width(COL_REPO))
        HeaderCell(
            "Downloads",
            SortKey.DOWNLOADS,
            sortKey,
            ascending,
            onSort,
            Modifier.width(COL_DOWNLOADS),
            TextAlign.End,
        )
        HeaderCell("Size", SortKey.SIZE, sortKey, ascending, onSort, Modifier.width(COL_SIZE), TextAlign.End)
        HeaderCell("Date", SortKey.DATE, sortKey, ascending, onSort, Modifier.width(COL_DATE))
        Spacer(Modifier.width(COL_ACTIONS))
    }
}

@Composable
private fun HeaderCell(
    label: String,
    key: SortKey,
    sortKey: SortKey,
    ascending: Boolean,
    onSort: (SortKey) -> Unit,
    modifier: Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val active = key == sortKey
    Text(
        text = if (active) "$label ${if (ascending) "▲" else "▼"}" else label,
        modifier = modifier.handCursor().clickable { onSort(key) },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A column that nothing sorts by — there is no useful order for a pair of repository badges. */
@Composable
private fun HeaderLabel(label: String, modifier: Modifier) {
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * One jar on one line. A disabled version is drawn in the same columns as its active sibling but
 * dimmed, so the table stays a single grid rather than a tree with rows that do not line up.
 */
@Composable
private fun ModTableRow(
    row: ModRow,
    showFolder: Boolean,
    enabled: Boolean,
    selected: Boolean,
    ignored: Boolean,
    onIgnore: (Boolean) -> Unit,
    onToggleSelected: () -> Unit,
    onApplyUpdate: (ModFile) -> Unit,
    onDisable: (ModFile) -> Unit,
    onActivate: (ModFile) -> Unit,
    onDelete: (ModFile) -> Unit,
) {
    val group = row.group
    val file = row.file
    val dimmed = !row.active
    val primary = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val update = group.update
    val hasUpdate = row.active && update != null && update.available()

    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Only a mod with an update to apply can be ticked, so most rows leave the column empty.
        if (hasUpdate && !ignored) {
            RowCheckbox(
                state = ToggleableState(selected),
                enabled = enabled,
                contentDescription = "Select ${group.displayName} for update",
                onClick = onToggleSelected,
            )
        } else {
            Spacer(Modifier.width(COL_SELECT))
        }
        Box(Modifier.size(ROW_ICON)) {
            if (row.active) {
                group.iconUrl?.let { url ->
                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(ROW_ICON))
                }
            }
        }
        Row(
            Modifier.weight(COL_NAME_WEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                group.displayName,
                Modifier.projectLink(update),
                style = MaterialTheme.typography.bodySmall,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only the active row carries the mod's problems: a disabled version loads nothing and
            // so breaks nothing.
            if (row.active) {
                val problems = listOfNotNull(
                    group.missingDependencies.takeIf { it.isNotEmpty() }
                        ?.let { "missing ${it.joinToString(", ")}" },
                    group.disabledDependencies.takeIf { it.isNotEmpty() }
                        ?.let { "disabled ${it.joinToString(", ")}" },
                )
                if (problems.isNotEmpty()) {
                    Text(
                        problems.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (group.unusedLibrary) {
                    // A hint, not an error: the library may still be wanted, e.g. by a datapack.
                    Text(
                        "unused library",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            if (showFolder) "${group.folderName}/${file.fileName}" else file.fileName,
            Modifier.weight(COL_FILE_WEIGHT),
            style = MaterialTheme.typography.bodySmall,
            color = if (dimmed) secondary else primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.width(COL_SIDE), contentAlignment = Alignment.CenterStart) {
            if (row.active) ModSideBadge(group.side)
        }
        Row(
            Modifier.width(COL_REPO),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.active) {
                group.sources.sortedBy { it.name }.forEach { source -> ModSourceBadge(source) }
            }
        }
        Text(
            if (row.active && group.downloads > 0) formatDownloads(group.downloads) else "",
            Modifier.width(COL_DOWNLOADS),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            formatSize(file.size),
            Modifier.width(COL_SIZE),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            file.modifiedAt?.toString()?.take(10) ?: "",
            Modifier.width(COL_DATE),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            maxLines = 1,
        )
        Row(
            Modifier.width(COL_ACTIONS),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasUpdate && ignored) {
                // Kept visible so the skip can be undone from the row that made it.
                RowAction("Unignore update", enabled) { onIgnore(false) }
            } else if (hasUpdate) {
                RowAction("Ignore", enabled) { onIgnore(true) }
                Button(
                    onClick = { onApplyUpdate(file) },
                    modifier = Modifier.height(ROW_BUTTON_HEIGHT),
                    enabled = enabled,
                    contentPadding = ROW_BUTTON_PADDING,
                ) {
                    ModSourceBadge(update!!.source)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Update to ${update.latestVersionName ?: update.latestVersionId}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // A dimmed row is normally a disabled version, but a second *enabled* jar of the same
            // mod also lands here — offering "Activate" for one of those would disable the real
            // active version and leave two enabled jars behind.
            if (file.archived) {
                RowAction("Activate", enabled) { onActivate(file) }
            } else {
                RowAction("Disable", enabled) { onDisable(file) }
            }
            RowAction("Delete", enabled) { onDelete(file) }
        }
    }
}

/**
 * Every pending update with the release notes of the version it would install, so a batch update
 * can be reviewed before it is applied. Notes are fetched when the dialog opens — Modrinth's usually
 * came with the scan already — and kept in [changelogs] for the rest of the session.
 */
@Composable
private fun ChangelogDialog(
    graph: AppGraph,
    updates: List<Pair<ModGroup, ModFile>>,
    changelogs: MutableMap<String, ChangelogState>,
    scope: CoroutineScope,
    onIgnore: (ModGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(updates) {
        for ((group, _) in updates) {
            val update = group.update ?: continue
            val key = changelogKey(group, update)
            if (changelogs[key] is ChangelogState.Loaded || changelogs[key] is ChangelogState.Loading) continue
            changelogs[key] = ChangelogState.Loading
            // Launched on the tab's scope rather than this effect's, so closing the dialog mid-fetch
            // still lets the result land in the cache instead of leaving the entry stuck on Loading.
            scope.launch {
                changelogs[key] = try {
                    ChangelogState.Loaded(graph.modService.changelog(update))
                } catch (e: Exception) {
                    ChangelogState.Failed(e.message ?: "unknown error")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        // Changelogs are long reads, so the dialog takes most of the window rather than a card's width.
        modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
        title = { Text("Changelogs (${updates.size})") },
        text = {
            if (updates.isEmpty()) {
                Text("No updates pending.", style = MaterialTheme.typography.bodyMedium)
            } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(updates, key = { it.first.key }) { (group, file) ->
                    val update = group.update ?: return@items
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(ROW_ICON)) {
                                group.iconUrl?.let { url ->
                                    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(ROW_ICON))
                                }
                            }
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
                            RowAction("Ignore", enabled = true) { onIgnore(group) }
                        }
                        when (val state = changelogs[changelogKey(group, update)]) {
                            null, ChangelogState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            is ChangelogState.Failed -> Text(
                                "Could not load changelog: ${state.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            is ChangelogState.Loaded -> if (state.text == null) {
                                Text(
                                    "No changelog provided.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                ChangelogMarkdown(state.text)
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * How much of a changelog shows before it is expanded. Some mods ship their entire history as the
 * changelog of every release, which would bury every other mod in the dialog.
 */
private const val CHANGELOG_PREVIEW_LINES = 100

/**
 * The first [maxLines] lines of [markdown]. A cut inside a fenced code block closes the fence, so the
 * truncated text still renders as a code block rather than swallowing whatever follows it.
 */
private fun truncateMarkdown(markdown: String, maxLines: Int): String {
    val kept = markdown.lines().take(maxLines)
    val openFence = kept.count { it.trimStart().startsWith("```") } % 2 == 1
    return kept.joinToString("\n") + if (openFence) "\n```" else ""
}

/**
 * A changelog rendered as Markdown, collapsed to [CHANGELOG_PREVIEW_LINES] until expanded. Headings
 * are scaled down from Material's defaults: a changelog's `# 1.2.0` sits under the mod's own name,
 * and at display size it would outweigh it.
 */
@Composable
private fun ChangelogMarkdown(text: String) {
    // Saveable so the choice survives the item scrolling out of the lazy list and back.
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val totalLines = remember(text) { text.lines().size }
    val collapsible = totalLines > CHANGELOG_PREVIEW_LINES
    val shown = remember(text, expanded) {
        if (collapsible && !expanded) truncateMarkdown(text, CHANGELOG_PREVIEW_LINES) else text
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SelectionContainer { MarkdownBody(shown) }
        if (collapsible) {
            RowAction(
                if (expanded) "Show less" else "Show all ($totalLines lines)",
                enabled = true,
            ) { expanded = !expanded }
        }
    }
}

@Composable
private fun MarkdownBody(text: String) {
    val type = MaterialTheme.typography
    Markdown(
        content = text,
        imageTransformer = Coil3ImageTransformerImpl,
        typography = markdownTypography(
            h1 = type.titleMedium,
            h2 = type.titleSmall,
            h3 = type.labelLarge,
            h4 = type.labelLarge,
            h5 = type.labelMedium,
            h6 = type.labelMedium,
            text = type.bodySmall,
            paragraph = type.bodySmall,
            ordered = type.bodySmall,
            bullet = type.bodySmall,
            list = type.bodySmall,
            code = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
        ),
    )
}

/**
 * A checkbox sized to fit inside a dense row. Material draws it at a fixed 20dp and reserves a 48dp
 * touch target around that, so the target is dropped and the whole control scaled down to [ROW_ICON].
 */
@Composable
private fun RowCheckbox(
    state: ToggleableState,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TriStateCheckbox(
            state = state,
            onClick = onClick,
            modifier = Modifier
                .requiredSize(ROW_ICON)
                .scale(ROW_ICON / CHECKBOX_DRAW_SIZE)
                .semantics { this.contentDescription = contentDescription },
            enabled = enabled,
        )
    }
}

/** A text action sized to fit inside a dense row. */
@Composable
private fun RowAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(ROW_BUTTON_HEIGHT),
        enabled = enabled,
        contentPadding = ROW_TEXT_BUTTON_PADDING,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
