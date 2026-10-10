package nl.jessedezwart.piglinpanel.desktop.ui.mods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import nl.jessedezwart.piglinpanel.desktop.ui.common.RowAction

/**
 * How much of a changelog shows before it is expanded. Some mods ship their entire history as the
 * changelog of every release, which would bury every other mod in the dialog.
 */
private const val CHANGELOG_PREVIEW_LINES = 20

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
internal fun ChangelogMarkdown(text: String) {
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
