package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSideBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.ModSourceBadge
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_BUTTON_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_BUTTON_PADDING
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_HEIGHT
import nl.jessedezwart.piglinpanel.desktop.ui.common.ROW_ICON
import nl.jessedezwart.piglinpanel.desktop.ui.common.RowAction
import nl.jessedezwart.piglinpanel.desktop.ui.common.handCursor
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.formatDownloads
import nl.jessedezwart.piglinpanel.model.formatSize
import nl.jessedezwart.piglinpanel.mods.ModSortKey

/**
 * Column widths of the installed-mods table. The header and the rows read from the same constants,
 * which is the whole reason the columns line up.
 */
private val COL_SIDE = 54.dp
private val COL_REPO = 44.dp
private val COL_DOWNLOADS = 68.dp
private val COL_SIZE = 64.dp
private val COL_DATE = 78.dp
private val COL_ACTIONS = 290.dp
private const val COL_NAME_WEIGHT = 2.0f
private const val COL_FILE_WEIGHT = 2.4f

/** One line of the table: a single jar, and the mod it is a version of. */
internal data class ModRow(val group: ModGroup, val file: ModFile, val active: Boolean)

@Composable
internal fun TableHeader(sortKey: ModSortKey, ascending: Boolean, onSort: (ModSortKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(ROW_ICON))
        HeaderCell("Name", ModSortKey.NAME, sortKey, ascending, onSort, Modifier.weight(COL_NAME_WEIGHT))
        HeaderCell("File", ModSortKey.FILE, sortKey, ascending, onSort, Modifier.weight(COL_FILE_WEIGHT))
        HeaderCell("Side", ModSortKey.SIDE, sortKey, ascending, onSort, Modifier.width(COL_SIDE))
        HeaderLabel("Repo", Modifier.width(COL_REPO))
        HeaderCell(
            "Downloads",
            ModSortKey.DOWNLOADS,
            sortKey,
            ascending,
            onSort,
            Modifier.width(COL_DOWNLOADS),
            TextAlign.End,
        )
        HeaderCell("Size", ModSortKey.SIZE, sortKey, ascending, onSort, Modifier.width(COL_SIZE), TextAlign.End)
        HeaderCell("Date", ModSortKey.DATE, sortKey, ascending, onSort, Modifier.width(COL_DATE))
        Spacer(Modifier.width(COL_ACTIONS))
    }
}

@Composable
private fun HeaderCell(
    label: String,
    key: ModSortKey,
    sortKey: ModSortKey,
    ascending: Boolean,
    onSort: (ModSortKey) -> Unit,
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
internal fun ModTableRow(
    row: ModRow,
    showFolder: Boolean,
    enabled: Boolean,
    ignored: Boolean,
    onIgnore: (Boolean) -> Unit,
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
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            // A step up from the window background, enough to pick the updatable rows out of a
            // long table without competing with the gold of the update button.
            .then(
                if (hasUpdate && !ignored) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModIcon(if (row.active) group.iconUrl else null)
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
                    group.versionConflicts.takeIf { it.isNotEmpty() }
                        ?.let { "needs ${it.joinToString(", ")}" },
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
