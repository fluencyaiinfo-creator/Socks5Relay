package com.socksrelay.vertex

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Deliberately minimal — just enough to make the bundled legal/about
 * documents (written in plain markdown) readable in a native TextView
 * without pulling in a full markdown-rendering library for four static
 * documents: `#`/`##` headers become bold + larger, `**bold**` becomes
 * bold. Everything else (links, lists, etc.) renders as plain text, which
 * is fine for this app's actual documents — none of them use anything
 * fancier than headers, bold, and paragraphs.
 */
object MarkdownLite {
    private val boldRegex = Regex("""\*\*(.+?)\*\*""")

    fun render(markdown: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lines = markdown.lines()

        lines.forEachIndexed { index, rawLine ->
            val headerMatch = Regex("""^(#{1,3})\s+(.*)$""").find(rawLine)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val text = headerMatch.groupValues[2]
                val start = builder.length
                builder.append(text)
                val end = builder.length
                builder.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
                builder.setSpan(RelativeSizeSpan(if (level == 1) 1.3f else 1.15f), start, end, 0)
            } else {
                appendWithInlineBold(builder, rawLine)
            }
            if (index != lines.lastIndex) builder.append("\n")
        }
        return builder
    }

    private fun appendWithInlineBold(builder: SpannableStringBuilder, line: String) {
        var remaining = line
        var match = boldRegex.find(remaining)
        while (match != null) {
            builder.append(remaining.substring(0, match.range.first))
            val start = builder.length
            builder.append(match.groupValues[1])
            builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, 0)
            remaining = remaining.substring(match.range.last + 1)
            match = boldRegex.find(remaining)
        }
        builder.append(remaining)
    }
}
