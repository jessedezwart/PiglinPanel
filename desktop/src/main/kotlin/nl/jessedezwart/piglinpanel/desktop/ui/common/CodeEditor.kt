package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakaztahir.codeeditor.model.CodeLang
import com.wakaztahir.codeeditor.prettify.PrettifyParser
import com.wakaztahir.codeeditor.theme.CodeThemeType
import com.wakaztahir.codeeditor.utils.parseCodeAsAnnotatedString
import nl.jessedezwart.piglinpanel.nbt.NbtFiles

/**
 * Syntax-highlighted text editor, built on Qawaz/compose-code-editor.
 *
 * The library only ships the parser and the themes, so the editing surface is ours: a plain
 * [BasicTextField] whose [VisualTransformation] swaps in the highlighted [AnnotatedString], with a
 * line-number gutter painted behind the field. Both live inside one vertical scroll so the gutter
 * can reuse the field's own [TextLayoutResult] coordinates and stays aligned when lines wrap.
 */

private val parser = PrettifyParser()

/**
 * Highlighting is O(file size) and reruns on every keystroke; past this many characters the editor
 * degrades to plain monospace text rather than stuttering.
 */
private const val HIGHLIGHT_LIMIT = 200_000

/** Extension-to-grammar map for the files a Minecraft server actually holds. */
fun codeLangFor(path: String): CodeLang {
    if (NbtFiles.isNbtExtension(path)) return CodeLang.JSON // NBT is edited as SNBT
    return when (path.substringAfterLast('.', "").lowercase()) {
        "json", "json5", "mcmeta", "snbt" -> CodeLang.JSON
        "yml", "yaml" -> CodeLang.YAML
        "toml", "properties", "cfg", "conf", "ini" -> CodeLang.YAML // `#` comments plus key/value pairs
        "xml", "html", "htm" -> CodeLang.XML
        "sh", "bash", "bat", "cmd", "mcfunction" -> CodeLang.Bash
        "js", "mjs" -> CodeLang.JavaScript
        "java" -> CodeLang.Java
        "kt", "kts" -> CodeLang.Kotlin
        "py" -> CodeLang.Python
        "md" -> CodeLang.Markdown
        "sql" -> CodeLang.SQL
        "css" -> CodeLang.CSS
        else -> CodeLang.Default
    }
}

@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    language: CodeLang,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val theme = remember { CodeThemeType.Monokai.theme }
    val codeStyle = LocalTextStyle.current.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    )
    val gutterStyle = codeStyle.copy(color = MaterialTheme.colorScheme.secondary)
    val gutterColor = MaterialTheme.colorScheme.surfaceVariant
    val cursorColor = MaterialTheme.colorScheme.primary

    // BasicTextField needs a TextFieldValue to keep the caret across recompositions, but callers own
    // the text; re-sync whenever a reload or a different file replaces it underneath us.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) {
        field = field.copy(
            text = value,
            selection = TextRange(field.selection.start.coerceAtMost(value.length)),
        )
    }

    val highlighted = remember(value, language, theme) {
        if (value.length > HIGHLIGHT_LIMIT) {
            AnnotatedString(value)
        } else {
            runCatching { parseCodeAsAnnotatedString(parser, theme, language, value) }
                .getOrElse { AnnotatedString(value) }
        }
    }
    val transformation = remember(highlighted) {
        VisualTransformation { input ->
            // Highlighting never changes the character count, so offsets map straight through.
            val styled = if (input.text == highlighted.text) highlighted else input
            TransformedText(styled, OffsetMapping.Identity)
        }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val digitWidth = remember(measurer, gutterStyle) { measurer.measure("0", gutterStyle).size.width }
    val lineCount = remember(value) { value.count { it == '\n' } + 1 }
    val gutterPadPx = with(density) { 8.dp.toPx() }
    val gutterWidthPx = digitWidth * maxOf(2, lineCount.toString().length) + gutterPadPx * 2
    val gutterWidth = with(density) { gutterWidthPx.toDp() }

    Box(modifier.verticalScroll(scroll).padding(vertical = 8.dp)) {
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != value) onValueChange(it.text)
            },
            readOnly = readOnly,
            textStyle = codeStyle,
            visualTransformation = transformation,
            cursorBrush = SolidColor(cursorColor),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(gutterColor, size = size.copy(width = gutterWidthPx))
                    val result = layout ?: return@drawBehind
                    // Only the slice currently scrolled into view is worth measuring.
                    val viewTop = scroll.value - gutterPadPx
                    val viewBottom = viewTop + scroll.viewportSize + gutterPadPx * 2
                    var number = 1
                    for (i in 0 until result.lineCount) {
                        // Wrapped continuations share their paragraph's number, so skip them.
                        if (i > 0 && value.getOrNull(result.getLineStart(i) - 1) != '\n') continue
                        val top = result.getLineTop(i)
                        if (top > viewBottom) break
                        if (top >= viewTop) {
                            val label = measurer.measure(number.toString(), gutterStyle)
                            drawText(label, topLeft = Offset(gutterWidthPx - gutterPadPx - label.size.width, top))
                        }
                        number++
                    }
                }
                .padding(start = gutterWidth + 8.dp),
        )
    }
}
