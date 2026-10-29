package com.myjournal.app.ui.editor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
enum class FormatStyle {
    BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, HIGHLIGHT
}
object RichTextHelper {
    val HighlightColor = Color(0x66D49B42)
    fun styleFor(type: FormatStyle): SpanStyle = when (type) {
        FormatStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        FormatStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        FormatStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        FormatStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        FormatStyle.HIGHLIGHT -> SpanStyle(background = HighlightColor)
    }
    /**
     * Applies formatting to the currently selected range in the TextFieldValue.
     */
    fun applyStyleToSelection(value: TextFieldValue, type: FormatStyle): TextFieldValue {
        val selection = value.selection
        if (selection.collapsed) return value
        val start = minOf(selection.start, selection.end)
        val end = maxOf(selection.start, selection.end)
        val newAnnotated = buildAnnotatedString {
            append(value.annotatedString)
            addStyle(styleFor(type), start, end)
        }
        return value.copy(annotatedString = newAnnotated)
    }
    /**
     * Preserves existing spans when typing or deleting characters, and applies active typing styles to newly typed text.
     */
    fun mergeEditWithSpans(
        oldValue: TextFieldValue,
        newValue: TextFieldValue,
        activeStyles: Set<FormatStyle>
    ): TextFieldValue {
        val oldText = oldValue.text
        val newText = newValue.text
        if (oldText == newText) {
            return newValue.copy(annotatedString = oldValue.annotatedString)
        }
        var prefix = 0
        val minLen = minOf(oldText.length, newText.length)
        while (prefix < minLen && oldText[prefix] == newText[prefix]) {
            prefix++
        }
        var oldSuffix = oldText.length
        var newSuffix = newText.length
        while (oldSuffix > prefix && newSuffix > prefix && oldText[oldSuffix - 1] == newText[newSuffix - 1]) {
            oldSuffix--
            newSuffix--
        }
        val delta = newText.length - oldText.length
        val built = buildAnnotatedString {
            append(newText)
            for (span in oldValue.annotatedString.spanStyles) {
                val s = when {
                    span.start <= prefix -> span.start
                    span.start >= oldSuffix -> span.start + delta
                    else -> prefix
                }.coerceIn(0, newText.length)
                val e = when {
                    span.end <= prefix -> span.end
                    span.end >= oldSuffix -> span.end + delta
                    else -> newSuffix
                }.coerceIn(0, newText.length)
                if (s < e) {
                    addStyle(span.item, s, e)
                }
            }
            if (newSuffix > prefix && activeStyles.isNotEmpty()) {
                for (style in activeStyles) {
                    addStyle(styleFor(style), prefix, newSuffix)
                }
            }
        }
        return newValue.copy(annotatedString = built)
    }
    /**
     * Serializes AnnotatedString to lightweight HTML tags for Room storage.
     */
    fun toHtml(annotated: AnnotatedString): String {
        if (annotated.text.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in annotated.text.indices) {
            val covering = annotated.spanStyles.filter { it.start <= i && it.end > i }
            val isBold = covering.any { it.item.fontWeight == FontWeight.Bold }
            val isItalic = covering.any { it.item.fontStyle == FontStyle.Italic }
            val isUnderline = covering.any { it.item.textDecoration?.contains(TextDecoration.Underline) == true }
            val isStrike = covering.any { it.item.textDecoration?.contains(TextDecoration.LineThrough) == true }
            val isHighlight = covering.any { it.item.background != Color.Unspecified }
            if (isBold) sb.append("<b>")
            if (isItalic) sb.append("<i>")
            if (isUnderline) sb.append("<u>")
            if (isStrike) sb.append("<s>")
            if (isHighlight) sb.append("<mark>")
            when (val c = annotated.text[i]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
            if (isHighlight) sb.append("</mark>")
            if (isStrike) sb.append("</s>")
            if (isUnderline) sb.append("</u>")
            if (isItalic) sb.append("</i>")
            if (isBold) sb.append("</b>")
        }
        return sb.toString()
    }
    /**
     * Parses serialized HTML back into an AnnotatedString for the WYSIWYG editor.
     */
    fun fromHtml(raw: String): AnnotatedString {
        if (!raw.contains("<")) return AnnotatedString(raw)
        return buildAnnotatedString {
            var i = 0
            var boldStart = -1
            var italicStart = -1
            var underlineStart = -1
            var strikeStart = -1
            var markStart = -1
            while (i < raw.length) {
                when {
                    raw.startsWith("<b>", i) -> { boldStart = this.length; i += 3 }
                    raw.startsWith("</b>", i) -> {
                        if (boldStart != -1 && boldStart < this.length) addStyle(styleFor(FormatStyle.BOLD), boldStart, this.length)
                        boldStart = -1; i += 4
                    }
                    raw.startsWith("<i>", i) -> { italicStart = this.length; i += 3 }
                    raw.startsWith("</i>", i) -> {
                        if (italicStart != -1 && italicStart < this.length) addStyle(styleFor(FormatStyle.ITALIC), italicStart, this.length)
                        italicStart = -1; i += 4
                    }
                    raw.startsWith("<u>", i) -> { underlineStart = this.length; i += 3 }
                    raw.startsWith("</u>", i) -> {
                        if (underlineStart != -1 && underlineStart < this.length) addStyle(styleFor(FormatStyle.UNDERLINE), underlineStart, this.length)
                        underlineStart = -1; i += 4
                    }
                    raw.startsWith("<s>", i) -> { strikeStart = this.length; i += 3 }
                    raw.startsWith("</s>", i) -> {
                        if (strikeStart != -1 && strikeStart < this.length) addStyle(styleFor(FormatStyle.STRIKETHROUGH), strikeStart, this.length)
                        strikeStart = -1; i += 4
                    }
                    raw.startsWith("<mark>", i) -> { markStart = this.length; i += 6 }
                    raw.startsWith("</mark>", i) -> {
                        if (markStart != -1 && markStart < this.length) addStyle(styleFor(FormatStyle.HIGHLIGHT), markStart, this.length)
                        markStart = -1; i += 7
                    }
                    raw.startsWith("&lt;", i) -> { append('<'); i += 4 }
                    raw.startsWith("&gt;", i) -> { append('>'); i += 4 }
                    raw.startsWith("&amp;", i) -> { append('&'); i += 5 }
                    else -> { append(raw[i]); i++ }
                }
            }
        }
    }
}