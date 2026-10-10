package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.mods.AppliedUpdate
import nl.jessedezwart.piglinpanel.mods.ModGrouping
import nl.jessedezwart.piglinpanel.mods.PlannedUpdate
import nl.jessedezwart.piglinpanel.mods.RequiredDependency
import nl.jessedezwart.piglinpanel.mods.ScanProgress
import nl.jessedezwart.piglinpanel.mods.UpdatePlan
import nl.jessedezwart.piglinpanel.mods.updateIgnored

/** Label of the plain rescan; the update-check button shows its own progress text for it. */
internal const val UPDATE_CHECK_TASK = "Update check"

/** Where one mod's changelog stands in the Updates tab. */
internal sealed interface ChangelogState {
    data object Loading : ChangelogState
    data class Loaded(val text: String?) : ChangelogState
    data class Failed(val message: String) : ChangelogState
}

/** Cache key for a changelog: the notes belong to the version, not the mod. */
internal fun changelogKey(group: ModGroup, update: ModUpdate): String = "${group.key}@${update.latestVersionId}"

/**
 * Everything the Mods screen's tabs share for one profile: the last scan, the single operation
 * running against it, and the actions that start one. Lives as long as the profile stays selected.
 *
 * [scope] is the screen's rather than a tab's, so work started from a tab — a changelog fetch, a
 * batch update — still lands when that tab is left part-way through.
 */
@Stable
internal class ModsScreenState(
    private val graph: AppGraph,
    profile: ServerProfile,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    /**
     * The profile as last composed. Settings edits arrive as a new object with the same id, so the
     * screen keeps this current rather than the state being rebuilt.
     */
    var profile by mutableStateOf(profile)

    var groups by mutableStateOf<List<ModGroup>>(emptyList())
        private set

    /** The ungrouped scan result, kept because dependency questions are asked per file, not per mod. */
    var mods by mutableStateOf<List<ModFile>>(emptyList())
        private set

    var progress by mutableStateOf<ScanProgress?>(null)
        private set

    /** Label of the running operation, or null when idle. All controls are disabled while set. */
    var task by mutableStateOf<String?>(null)
        private set

    val busy: Boolean get() = task != null

    /** A planned batch waiting on the user, because applying it as-is would break something. */
    var pendingPlan by mutableStateOf<UpdatePlan?>(null)
        private set

    /** Changelogs already fetched this session, so revisiting the Updates tab does not refetch them. */
    val changelogs = mutableStateMapOf<String, ChangelogState>()

    /** Every active mod with an update to apply, ignored ones excluded. */
    val updatable: List<Pair<ModGroup, ModFile>> by derivedStateOf {
        val current = this.profile
        groups.mapNotNull { group ->
            val active = group.active ?: return@mapNotNull null
            if (group.update?.available() == true && !group.updateIgnored(current)) group to active else null
        }
    }

    // Snackbars suspend until dismissed, so they are always shown off the task coroutine —
    // otherwise the UI would stay disabled for the lifetime of the message.
    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    private suspend fun scan(checkUpdates: Boolean, forceRefresh: Boolean) {
        progress = null
        // Only a scan that reads jars is worth a progress bar; a listing is over before it draws.
        val onProgress: ((ScanProgress) -> Unit)? = if (checkUpdates) { p -> progress = p } else null
        val scanned = graph.modService.scan(profile, checkUpdates, forceRefresh, onProgress)
        mods = scanned
        groups = ModGrouping.group(scanned)
    }

    /** Runs [block] as the single active operation, keeping the UI disabled until it finishes. */
    private fun launchTask(label: String, block: suspend () -> Unit) {
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
        launchTask(UPDATE_CHECK_TASK) { scan(checkUpdates, forceRefresh) }
    }

    /**
     * Shows the list of the last scan straight away, then checks it against the folders as they
     * are now: removed jars drop out, and new or changed ones show bare until updates are checked.
     */
    suspend fun load() {
        val known = graph.modService.lastKnown(profile)
        if (mods.isEmpty() && known.isNotEmpty()) {
            mods = known
            groups = ModGrouping.group(known)
        }
        launchTask("Listing mods") { scan(checkUpdates = false, forceRefresh = false) }
    }

    /** A mod mutation: runs [block], then rescans (checking updates when [checkUpdatesAfter]). */
    private fun mutate(label: String, checkUpdatesAfter: Boolean, block: suspend () -> Unit) {
        launchTask(label) {
            block()
            scan(checkUpdatesAfter, false)
        }
    }

    // The rescans after these check updates: renaming a jar does not change its bytes, so the hashes
    // and the update lookups both come straight from cache. Scanning without them would drop every
    // mod's project identity and shatter the table into one row per file.

    fun disable(mod: ModFile) {
        mutate("Disable", true) { graph.modService.disablePath(profile, mod.relativePath) }
    }

    fun activate(mod: ModFile) {
        mutate("Activate", true) {
            graph.modService.activate(profile, mods, mod)
            // Dependencies that are installed but still disabled are pointed out rather than
            // enabled: what the server loads stays the user's call.
            val needed = ModGrouping.disabledDependenciesOf(mods, mod)
            if (needed.isNotEmpty()) {
                notify("Still disabled: ${needed.joinToString { it.fileName }}")
            }
        }
    }

    fun delete(mod: ModFile) {
        mutate("Delete", true) { graph.modService.delete(profile, mod) }
    }

    /** The enabled mods that would lose a required dependency if [mod] were disabled or deleted. */
    fun dependentsBrokenBy(mod: ModFile): List<String> = ModGrouping.dependentsBrokenBy(mods, mod)

    /** The hard dependencies of each of [targets], keyed by relative path. */
    fun requiredDependenciesOf(targets: List<ModFile>): Map<String, List<RequiredDependency>> =
        ModGrouping.requiredDependenciesOf(mods, targets)

    private fun revert(applied: List<AppliedUpdate>) {
        launchTask("Revert") {
            // Newest first, so a mod updated twice in one batch unwinds in the order it was done.
            applied.reversed().forEach { graph.modService.revertUpdate(profile, it) }
            scan(checkUpdates = true, forceRefresh = false)
            notify("Reverted ${applied.size} mod(s)")
        }
    }

    /**
     * Writes an already-downloaded batch. Not a task of its own, so the planner can run straight
     * into it without waiting for the UI to go idle in between.
     */
    private suspend fun applyEntries(entries: List<PlannedUpdate>) {
        val applied = mutableListOf<AppliedUpdate>()
        val failures = mutableListOf<String>()
        entries.forEachIndexed { index, entry ->
            progress = ScanProgress("updating", index, entries.size, entry.displayName)
            val bytes = entry.bytes
            if (bytes == null) {
                failures += entry.displayName
                return@forEachIndexed
            }
            try {
                applied += graph.modService.applyUpdate(profile, entry.file, bytes)
            } catch (e: Exception) {
                failures += entry.displayName
            }
        }
        progress = null
        scan(checkUpdates = true, forceRefresh = false)

        val message = if (failures.isEmpty()) {
            "Updated ${applied.size} mod(s)"
        } else {
            "Updated ${applied.size} mod(s); ${failures.size} failed: ${failures.joinToString()}"
        }
        // Offered rather than announced: the jars this replaced are still on disk, disabled, and
        // putting them back is the one thing a bad batch needs and the log will not give you.
        scope.launch {
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = "Revert".takeIf { applied.isNotEmpty() },
            )
            if (result == SnackbarResult.ActionPerformed) revert(applied)
        }
    }

    /**
     * Downloads every selected update and checks what the pack would look like with all of them in
     * place before writing any of them; see [nl.jessedezwart.piglinpanel.mods.ModUpdatePlanner].
     */
    fun applyUpdates(targets: List<Pair<ModGroup, ModFile>>) {
        if (targets.isEmpty()) return
        launchTask("Update") {
            val names = targets.associate { (group, mod) -> mod.relativePath to group.displayName }
            val plan = graph.modUpdatePlanner.plan(profile, mods, targets.map { it.second }, names) { p ->
                progress = ScanProgress("downloading", p.done, p.total, p.displayName)
            }
            progress = null
            if (plan.blocked.isEmpty() && plan.failed.isEmpty()) {
                applyEntries(plan.safe)
            } else {
                pendingPlan = plan
            }
        }
    }

    fun applyPlanned(entries: List<PlannedUpdate>) {
        pendingPlan = null
        if (entries.isEmpty()) return
        launchTask("Update") { applyEntries(entries) }
    }

    fun dismissPlan() {
        pendingPlan = null
    }

    /** Skips (or, with false, un-skips) the update [group] is offering, for this profile. */
    fun ignoreUpdate(group: ModGroup, ignore: Boolean) {
        graph.registry.setIgnoredUpdate(profile.id, group.key, if (ignore) group.update?.latestVersionId else null)
    }

    /** Starts fetching the changelog of every pending update not already fetched or on its way. */
    fun fetchChangelogs() {
        for ((group, _) in updatable) {
            val update = group.update ?: continue
            val key = changelogKey(group, update)
            if (changelogs[key] is ChangelogState.Loaded || changelogs[key] is ChangelogState.Loading) continue
            changelogs[key] = ChangelogState.Loading
            scope.launch {
                changelogs[key] = try {
                    ChangelogState.Loaded(graph.modService.changelog(update))
                } catch (e: Exception) {
                    ChangelogState.Failed(e.message ?: "unknown error")
                }
            }
        }
    }
}
