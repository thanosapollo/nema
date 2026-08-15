package org.thanosapollo.nema.xmpp.reply

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.thanosapollo.nema.xmpp.transport.MessageReplyEnvelope

const val REPLY_NAMESPACE = "urn:xmpp:reply:0"
const val FALLBACK_NAMESPACE = "urn:xmpp:fallback:0"

private const val REPLY_ELEMENT = "reply"
private const val FALLBACK_ELEMENT = "fallback"

internal fun installNemaReplyProviders() {
    ProviderManager.addExtensionProvider(
        FALLBACK_ELEMENT,
        FALLBACK_NAMESPACE,
        StandardExtensionElementProvider.INSTANCE,
    )
}

data class ParsedReplyBody(
    val body: String,
    val fallbackBody: String?,
)

internal fun Message.replyReference(): MessageReplyEnvelope? {
    val extension = getExtensionElement(REPLY_ELEMENT, REPLY_NAMESPACE) as? StandardExtensionElement
        ?: return null
    val id = extension.getAttributeValue("id")?.takeIf(String::isNotEmpty) ?: return null
    val to = extension.getAttributeValue("to")?.takeIf(String::isNotEmpty)
    return MessageReplyEnvelope(id = id, to = to)
}

internal fun Message.replyFallbackRange(): IntRange? {
    val body = body ?: return null
    val extension = getExtensionElement(FALLBACK_ELEMENT, FALLBACK_NAMESPACE) as? StandardExtensionElement
        ?: return null
    if (extension.getAttributeValue("for") != REPLY_NAMESPACE) return null
    val region = extension.getFirstElement("body", FALLBACK_NAMESPACE)
        ?: return 0 until body.length
    val startAttribute = region.getAttributeValue("start")
    val endAttribute = region.getAttributeValue("end")
    if (startAttribute == null && endAttribute == null) return 0 until body.length
    val start = startAttribute?.toIntOrNull() ?: return null
    val end = endAttribute?.toIntOrNull() ?: return null
    if (start < 0 || end <= start) return null
    val utf16Start = body.utf16IndexAtCodePoint(start) ?: return null
    val utf16End = body.utf16IndexAtCodePoint(end) ?: return null
    return utf16Start until utf16End
}

internal fun Message.parseReplyBody(): ParsedReplyBody {
    val body = body.orEmpty()
    val range = replyFallbackRange() ?: return ParsedReplyBody(body, null)
    val fallback = body.substring(range)
    val visible = body.removeRange(range.first, range.last + 1)
    return ParsedReplyBody(visible, fallback.replyFallbackBody())
}

private fun String.replyFallbackBody(): String? {
    val quoted = lineSequence()
        .filter(String::isNotBlank)
        .map { it.removePrefix("> ").removePrefix(">") }
        .toList()
    if (quoted.isEmpty()) return null
    val body = quoted.drop(if (quoted.first().endsWith(" wrote:")) 1 else 0)
        .joinToString("\n")
        .trim()
    return body.takeIf(String::isNotEmpty)
}

internal fun MessageBuilder.addReply(
    reply: MessageReplyEnvelope,
    body: String,
): MessageBuilder {
    val fallback = replyFallback(reply)
    setBody(fallback + body)
    val replyBuilder = StandardExtensionElement.builder(REPLY_ELEMENT, REPLY_NAMESPACE)
        .addAttribute("id", reply.id)
    reply.to?.let { replyBuilder.addAttribute("to", it) }
    addExtension(replyBuilder.build())
    if (fallback.isNotEmpty()) {
        val region = StandardExtensionElement.builder("body", FALLBACK_NAMESPACE)
            .addAttribute("start", "0")
            .addAttribute("end", fallback.codePointCount().toString())
            .build()
        addExtension(
            StandardExtensionElement.builder(FALLBACK_ELEMENT, FALLBACK_NAMESPACE)
                .addAttribute("for", REPLY_NAMESPACE)
                .addElement(region)
                .build(),
        )
    }
    return this
}

private fun replyFallback(reply: MessageReplyEnvelope): String {
    val quoted = reply.fallbackBody?.takeIf(String::isNotEmpty) ?: return ""
    val sender = reply.fallbackSender
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    return buildString {
        if (sender != null) append("> ").append(sender).append(" wrote:\n")
        quoted.lineSequence().forEach { append("> ").append(it).append('\n') }
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)

private fun String.utf16IndexAtCodePoint(offset: Int): Int? = runCatching {
    offsetByCodePoints(0, offset)
}.getOrNull()
