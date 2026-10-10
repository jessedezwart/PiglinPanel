package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/** The size Material always draws a checkbox at, regardless of the size it is laid out in. */
private val CHECKBOX_DRAW_SIZE = 20.dp

/**
 * A checkbox sized to fit inside a dense row. Material draws it at a fixed 20dp and reserves a 48dp
 * touch target around that, so the target is dropped and the whole control scaled down to [ROW_ICON].
 */
@Composable
fun RowCheckbox(
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
fun RowAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(ROW_BUTTON_HEIGHT),
        enabled = enabled,
        contentPadding = ROW_TEXT_BUTTON_PADDING,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
