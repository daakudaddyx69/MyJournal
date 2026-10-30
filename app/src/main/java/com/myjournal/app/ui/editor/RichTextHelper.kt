package com.myjournal.app.ui.editor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.max
import kotlin.math.min
enum class FormatStyle {
    BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, HIGHLIGHT
}
object RichTextHelper {
    private val HighlightColor = Color(0x66D4A373)
    fun styleFor(type: FormatStyle): SpanStyle = when (type) {
        FormatStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        FormatStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        FormatStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        FormatStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        FormatStyle.HIGHLIGHT -> SpanStyle(background = HighlightColor)
    }
    private fun matchesStyleType(span: SpanStyle, type: FormatStyle): Boolean = when (type) {
        FormatStyle.BOLD -> span.fontWeight == FontWeight.Bold
        FormatStyle.ITALIC -> span.fontStyle == FontStyle.Italic
        FormatStyle.UNDERLINE -> span.textDecoration?.contains(TextDecoration.Underline) == true
        FormatStyle.STRIKETHROUGH -> span.textDecoration?.contains(TextDecoration.LineThrough) == true
        FormatStyle.HIGHLIGHT -> span.background != Color.Unspecified
    }
    fun getActiveStylesAtSelection(value: TextFieldValue): Set<FormatStyle> {
        val sel = value.selection
        val selStart = min(sel.start, sel.end).coerceIn(0, value.text.length)
        val selEnd = max(sel.start, sel.end).coerceIn(0, value.text.length)
        val active = mutableSetOf<FormatStyle>()
        val spans = value.annotatedString.spanStyles
        if (selStart == selEnd) {
            if (selStart > 0) {
                for (span in spans) {
                    if (span.start <= selStart - 1 && span.end >= selStart) {
                        for (style in FormatStyle.values()) {
                            if (matchesStyleType(span.item, style)) active.add(style)
                        }
                    }
                }
            }
        } else {
            for (style in FormatStyle.values()) {
                val coversAll = (selStart until selEnd).all { idx ->
                    spans.any { span -> span.start <= idx && span.end > idx && matchesStyleType(span.item, style) }
                }
                if (coversAll) active.add(style)
            }
        }
        return active
    }
    fun applyStyleToSelection(value: TextFieldValue, style: FormatStyle): TextFieldValue {
        val sel = value.selection
        if (sel.collapsed) return value
        val selStart = min(sel.start, sel.end).coerceIn(0, value.text.length)
        val selEnd = max(sel.start, sel.end).coerceIn(0, value.text.length)
        if (selStart >= selEnd) return value
        val existingSpans = value.annotatedString.spanStyles
        val alreadyStyled = existingSpans.any {
            matchesStyleType(it.item, style) && it.start <= selStart && it.end >= selEnd
        }
        val updated = buildAnnotatedString {
            append(value.annotatedString.text)
            for (s in existingSpans) {
                if (alreadyStyled && matchesStyleType(s.item, style)) {
                    if (s.end <= selStart || s.start >= selEnd) {
                        addStyle(s.item, s.start, s.end)
                    } else {
                        if (s.start < selStart) addStyle(s.item, s.start, selStart)
                        if (s.end > selEnd) addStyle(s.item, selEnd, s.end)
                    }
                } else {
                    addStyle(s.item, s.start, s.end)
                }
            }
            if (!alreadyStyled) {
                addStyle(styleFor(style), selStart, selEnd)
            }
        }
        return value.copy(annotatedString = updated)
    }
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
        val minLen = min(oldText.length, newText.length)
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
        val updated = buildAnnotatedString {
            append(newText)
            for (span in oldValue.annotatedString.spanStyles) {
                val s = span.start
                val e = span.end
                val newStart = when {
                    s <= prefix -> s
                    s >= oldSuffix -> (s + delta).coerceAtLeast(prefix)
                    else -> prefix
                }.coerceIn(0, newText.length)
                val newEnd = when {
                    e <= prefix -> e
                    e >= oldSuffix -> (e + delta).coerceAtLeast(newStart)
                    else -> newSuffix
                }.coerceIn(0, newText.length)
                if (newStart < newEnd) {
                    addStyle(span.item, newStart, newEnd)
                }
            }
            if (newSuffix > prefix && activeStyles.isNotEmpty()) {
                for (st in activeStyles) {
                    addStyle(styleFor(st), prefix, newSuffix)
                }
            }
        }
        return newValue.copy(annotatedString = updated)
    }
    fun toHtml(annotated: AnnotatedString): String {
        val text = annotated.text
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in text.indices) {
            val active = annotated.spanStyles.filter { i >= it.start && i < it.end }
            val isBold = active.any { it.item.fontWeight == FontWeight.Bold }
            val isItalic = active.any { it.item.fontStyle == FontStyle.Italic }
            val isUnderline = active.any { it.item.textDecoration?.contains(TextDecoration.Underline) == true }
            val isStrike = active.any { it.item.textDecoration?.contains(TextDecoration.LineThrough) == true }
            val isHighlight = active.any { it.item.background != Color.Unspecified }
            if (isBold) sb.append("<b>")
            if (isItalic) sb.append("<i>")
            if (isUnderline) sb.append("<u>")
            if (isStrike) sb.append("<s>")
            if (isHighlight) sb.append("<hl>")
            when (val c = text[i]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
            if (isHighlight) sb.append("</hl>")
            if (isStrike) sb.append("</s>")
            if (isUnderline) sb.append("</u>")
            if (isItalic) sb.append("</i>")
            if (isBold) sb.append("</b>")
        }
        return sb.toString()
    }
    fun fromHtml(html: String): AnnotatedString {
        if (!html.contains("<")) return AnnotatedString(html)
        return buildAnnotatedString {
            var i = 0
            var boldStart = -1
            var italicStart = -1
            var uStart = -1
            var sStart = -1
            var hlStart = -1
            while (i < html.length) {
                when {
                    html.startsWith("<b>", i) -> { boldStart = length; i += 3 }
                    html.startsWith("</b>", i) -> {
                        if (boldStart != -1 && boldStart < length) addStyle(styleFor(FormatStyle.BOLD), boldStart, length)
                        boldStart = -1; i += 4
                    }
                    html.startsWith("<i>", i) -> { italicStart = length; i += 3 }
                    html.startsWith("</i>", i) -> {
                        if (italicStart != -1 && italicStart < length) addStyle(styleFor(FormatStyle.ITALIC), italicStart, length)
                        italicStart = -1; i += 4
                    }
                    html.startsWith("<u>", i) -> { uStart = length; i += 3 }
                    html.startsWith("</u>", i) -> {
                        if (uStart != -1 && uStart < length) addStyle(styleFor(FormatStyle.UNDERLINE), uStart, length)
                        uStart = -1; i += 4
                    }
                    html.startsWith("<s>", i) -> { sStart = length; i += 3 }
                    html.startsWith("</s>", i) -> {
                        if (sStart != -1 && sStart < length) addStyle(styleFor(FormatStyle.STRIKETHROUGH), sStart, length)
                        sStart = -1; i += 4
                    }
                    html.startsWith("<hl>", i) -> { hlStart = length; i += 4 }
                    html.startsWith("</hl>", i) -> {
                        if (hlStart != -1 && hlStart < length) addStyle(styleFor(FormatStyle.HIGHLIGHT), hlStart, length)
                        hlStart = -1; i += 5
                    }
                    html.startsWith("&lt;", i) -> { append('<'); i += 4 }
                    html.startsWith("&gt;", i) -> { append('>'); i += 4 }
                    html.startsWith("&amp;", i) -> { append('&'); i += 5 }
                    else -> { append(html[i]); i++ }
                }
            }
        }
    }
}