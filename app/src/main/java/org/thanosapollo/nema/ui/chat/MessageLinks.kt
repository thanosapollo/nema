package org.thanosapollo.nema.ui.chat

sealed class TextRun {
    data class Plain(val text: String) : TextRun()
    data class Url(val text: String) : TextRun()
}

fun parseHttpLinks(body: String): List<TextRun> {
    if (body.isEmpty()) return listOf(TextRun.Plain(""))
    val runs = mutableListOf<TextRun>()
    var cursor = 0
    HTTP_LINK.findAll(body).forEach { match ->
        val raw = match.value
        val url = raw.trimEnd(*TRAILING_URL_PUNCT)
        val start = match.range.first
        val end = start + url.length
        if (start > cursor) runs += TextRun.Plain(body.substring(cursor, start))
        if (url.isNotEmpty()) runs += TextRun.Url(url)
        cursor = end
    }
    if (cursor < body.length) runs += TextRun.Plain(body.substring(cursor))
    return runs.ifEmpty { listOf(TextRun.Plain(body)) }
}

private val HTTP_LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val TRAILING_URL_PUNCT = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']')
