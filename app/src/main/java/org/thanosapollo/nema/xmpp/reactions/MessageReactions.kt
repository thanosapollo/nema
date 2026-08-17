package org.thanosapollo.nema.xmpp.reactions

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager

const val REACTIONS_NAMESPACE = "urn:xmpp:reactions:0"
const val HINTS_NAMESPACE = "urn:xmpp:hints"
val DEFAULT_REACTION_CHOICES = listOf("👍", "❤️", "😂", "🎉", "😮", "😢", "🙏")

private const val REACTIONS_ELEMENT = "reactions"
private const val REACTION_ELEMENT = "reaction"
private const val STORE_ELEMENT = "store"
private const val EMOJI_SEPARATOR = '\u001f'

internal fun installNemaReactionProviders() {
    ProviderManager.addExtensionProvider(
        REACTIONS_ELEMENT,
        REACTIONS_NAMESPACE,
        StandardExtensionElementProvider.INSTANCE,
    )
}

data class ParsedReactions(
    val targetId: String,
    val emojis: List<String>,
)

data class ReactionDisplay(
    val localMessageId: String,
    val emoji: String,
    val count: Int,
    val chosen: Boolean,
    val senders: List<String>,
)

fun deduplicateReactions(emojis: Iterable<String>): List<String> {
    val seen = linkedSetOf<String>()
    for (raw in emojis) {
        val emoji = raw.trim()
        if (emoji.isNotEmpty()) seen += emoji
    }
    return seen.toList()
}

fun toggleReaction(emoji: String, current: Iterable<String>): List<String> {
    val selected = emoji.trim()
    if (selected.isEmpty()) return deduplicateReactions(current)
    val present = deduplicateReactions(current)
    return if (selected in present) present - selected else present + selected
}

fun encodeReactionEmojis(emojis: Iterable<String>): String =
    deduplicateReactions(emojis).joinToString(EMOJI_SEPARATOR.toString())

fun decodeReactionEmojis(raw: String): List<String> {
    if (raw.isEmpty()) return emptyList()
    return deduplicateReactions(raw.split(EMOJI_SEPARATOR))
}

fun reactionDisplaysFor(
    localMessageId: String,
    senderState: List<Pair<String, List<String>>>,
    chosenSender: String?,
): List<ReactionDisplay> {
    val order = linkedSetOf<String>()
    val sendersByEmoji = linkedMapOf<String, MutableList<String>>()
    for ((sender, emojis) in senderState) {
        for (emoji in deduplicateReactions(emojis)) {
            if (order.add(emoji)) sendersByEmoji[emoji] = mutableListOf()
            val senders = sendersByEmoji.getValue(emoji)
            if (sender !in senders) senders += sender
        }
    }
    return order.map { emoji ->
        val senders = sendersByEmoji.getValue(emoji)
        ReactionDisplay(
            localMessageId = localMessageId,
            emoji = emoji,
            count = senders.size,
            chosen = chosenSender != null && chosenSender in senders,
            senders = senders,
        )
    }
}

internal fun Message.parseReactions(): ParsedReactions? {
    val extensions = extensions.filterIsInstance<StandardExtensionElement>()
        .filter { it.elementName == REACTIONS_ELEMENT && it.namespace == REACTIONS_NAMESPACE }
    if (extensions.size != 1) return null
    val extension = extensions.single()
    val targetId = extension.getAttributeValue("id")?.takeIf(String::isNotEmpty) ?: return null
    val children = extension.getElements(REACTION_ELEMENT, REACTIONS_NAMESPACE).orEmpty()
        .ifEmpty { extension.elements.orEmpty().filter { it.elementName == REACTION_ELEMENT } }
    return ParsedReactions(
        targetId = targetId,
        emojis = deduplicateReactions(children.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }),
    )
}

internal fun MessageBuilder.addReactions(targetId: String, emojis: Iterable<String>): MessageBuilder {
    val reactions = StandardExtensionElement.builder(REACTIONS_ELEMENT, REACTIONS_NAMESPACE)
        .addAttribute("id", targetId)
    for (emoji in deduplicateReactions(emojis)) {
        reactions.addElement(REACTION_ELEMENT, emoji)
    }
    addExtension(reactions.build())
    addExtension(StandardExtensionElement.builder(STORE_ELEMENT, HINTS_NAMESPACE).build())
    return this
}
