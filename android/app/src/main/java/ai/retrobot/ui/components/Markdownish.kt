package ai.retrobot.ui.components

/** A piece of a rendered message. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Code(val text: String, val language: String?) : Segment
}

/**
 * Split assistant text into plain paragraphs and fenced code blocks.
 *
 * This is deliberately not a Markdown parser. It handles the one construct
 * that matters for a model's output — code blocks — and leaves everything
 * else as plain text, so nothing a model emits can break the layout.
 */
fun parseSegments(content: String): List<Segment> {
    if (content.isEmpty()) return emptyList()

    val segments = mutableListOf<Segment>()
    val lines = content.lines()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        val fence = line.trimStart()

        if (fence.startsWith("```")) {
            val language = fence.removePrefix("```").trim().ifEmpty { null }
            val buffer = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                buffer.appendLine(lines[index])
                index++
            }
            // Skip the closing fence if there is one.
            if (index < lines.size) index++
            segments += Segment.Code(buffer.toString().trimEnd('\n'), language)
            continue
        }

        // Group consecutive non-fence lines into one paragraph block.
        val paragraph = StringBuilder()
        while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(lines[index])
            index++
        }
        segments += Segment.Text(paragraph.toString())
    }

    return segments.filterNot { segment -> segment is Segment.Text && segment.text.isBlank() }
}
