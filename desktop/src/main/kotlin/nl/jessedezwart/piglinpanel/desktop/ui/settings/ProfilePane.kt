package nl.jessedezwart.piglinpanel.desktop.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nl.jessedezwart.piglinpanel.desktop.ui.common.Button
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile

/**
 * Editor for one server profile. Field state is local to the pane and keyed on the profile id, so
 * switching profiles resets it; [ServerProfile] is only rebuilt to compare against the saved copy
 * (which is what enables Save) and to hand to [onSave].
 */
@Composable
internal fun ProfilePane(
    profile: ServerProfile,
    isNew: Boolean,
    onSave: (ServerProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(profile.id) { mutableStateOf(ProfileDraft.of(profile)) }
    // Applied to the current draft rather than to the one a section was composed with, so two edits
    // landing before the next recomposition both survive.
    val edit: (ProfileDraft.() -> ProfileDraft) -> Unit = { change -> draft = draft.change() }

    val edited = draft.applyTo(profile)
    val dirty = edited != profile

    DetailPane(
        title = profile.displayName.ifBlank { "New server profile" },
        subtitle = profileSubtitle(profile, isNew, dirty),
        actions = {
            DeleteButton(onClick = onDelete)
            Button(onClick = { onSave(edited) }, enabled = draft.complete && (dirty || isNew)) {
                Text("Save")
            }
        },
    ) {
        LocationSection(draft, edit)
        if (draft.accessType == AccessType.SSH) {
            SshConnectionSection(draft, edit)
            ActionsSection(draft, edit)
        }
        ModFoldersSection(draft, edit)
    }
}

private fun profileSubtitle(profile: ServerProfile, isNew: Boolean, dirty: Boolean): String = when {
    isNew -> "Not saved yet."
    dirty -> "Unsaved changes."
    else -> {
        val access = if (profile.accessType == AccessType.SSH) "Remote over SSH" else "On this computer"
        val detected = listOfNotNull(profile.modLoader?.replaceFirstChar(Char::uppercase), profile.mcVersion)
        if (detected.isEmpty()) access else access + " · " + detected.joinToString(" ")
    }
}
