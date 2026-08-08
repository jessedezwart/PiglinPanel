package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Shared metrics for the dense list rows used by the mods, logs and files screens.
 *
 * Material sizes its controls for touch: a button reserves 40dp of height and a list row rarely
 * fits under 56dp. These screens are read at a glance on a desktop monitor, so they trade the
 * touch targets for row count — the constants live here so the three screens stay in step.
 */

/** Height of a primary list row: one line of [androidx.compose.material3.Typography.bodySmall] plus a hairline of breathing room. */
val ROW_HEIGHT = 22.dp

/** Height of a secondary/indented row, such as an archived mod version. */
val SUB_ROW_HEIGHT = 20.dp

/** Height of an inline action button sitting inside a list row. */
val ROW_BUTTON_HEIGHT = 18.dp

/** Icon drawn at the start of a row (mod icon, file/folder glyph). */
val ROW_ICON = 16.dp

/** Content padding for a filled/outlined button inside a row. */
val ROW_BUTTON_PADDING = PaddingValues(horizontal = 6.dp, vertical = 0.dp)

/** Content padding for a text button inside a row; tighter still, since it has no container. */
val ROW_TEXT_BUTTON_PADDING = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
