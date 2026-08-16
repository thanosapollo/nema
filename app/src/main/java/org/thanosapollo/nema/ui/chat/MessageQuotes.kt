package org.thanosapollo.nema.ui.chat

sealed class BodySegment {
    data class Plain(val text: String) : BodySegment()
    data class Quote(val depth: Int, val text: String) : BodySegment()
}

fun parseQuotedBody(body: String): List<BodySegment> {
    if (body.isEmpty()) return listOf(BodySegment.Plain(""))
    val segments = mutableListOf<BodySegment>()
    body.split('\n').forEach { line ->
        val (depth, text) = quotePrefix(line)
        if (depth == 0) {
            appendPlain(segments, line)
        } else {
            appendQuote(segments, depth, text)
        }
    }
    return collapseLeadingBlankAfterQuote(segments)
}

private fun quotePrefix(line: String): Pair<Int, String> {
    var depth = 0
    var index = 0
    while (index < line.length && line[index] == '>') {
        depth++
        index++
        if (index < line.length && line[index] == ' ') index++
    }
    return depth to line.substring(index)
}

private fun appendPlain(segments: MutableList<BodySegment>, line: String) {
    val last = segments.lastOrNull()
    if (last is BodySegment.Plain) {
        segments[segments.lastIndex] = BodySegment.Plain(last.text + "\n" + line)
    } else {
        segments += BodySegment.Plain(line)
    }
}

private fun appendQuote(segments: MutableList<BodySegment>, depth: Int, text: String) {
    val last = segments.lastOrNull()
    if (last is BodySegment.Quote && last.depth == depth) {
        segments[segments.lastIndex] = BodySegment.Quote(depth, last.text + "\n" + text)
    } else {
        segments += BodySegment.Quote(depth, text)
    }
}

private fun collapseLeadingBlankAfterQuote(segments: List<BodySegment>): List<BodySegment> {
    if (segments.size < 2) return segments
    return segments.mapIndexed { index, segment ->
        val previous = segments.getOrNull(index - 1)
        if (previous is BodySegment.Quote && segment is BodySegment.Plain && segment.text.startsWith("\n")) {
            BodySegment.Plain(segment.text.removePrefix("\n"))
        } else {
            segment
        }
    }.filterNot { it is BodySegment.Plain && it.text.isEmpty() && segments.size > 1 }
}
