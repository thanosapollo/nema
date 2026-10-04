package org.thanosapollo.nema.xmpp.omemo

import java.util.Base64
import org.jxmpp.jid.impl.JidCreate
import org.thanosapollo.nema.xmpp.XmppElement

internal enum class OmemoProtocol(val namespace: String) {
    LEGACY("eu.siacs.conversations.axolotl"),
    MODERN("urn:xmpp:omemo:2"),
}

/** Unauthenticated wire evidence, never a trust decision or a decrypted message. */
internal data class OmemoContent(
    val protocol: OmemoProtocol,
    val senderDevice: Long,
    val keys: List<OmemoRecipientKey>,
    val legacyIv: String?,
    val payload: String?,
) {
    val formatVersion: Int get() = 1
}

internal data class OmemoRecipientKey(
    val recipientBareJid: String?,
    val device: Long,
    val keyExchange: Boolean,
    val ciphertext: String,
)

internal sealed interface OmemoInspection {
    data object Absent : OmemoInspection
    data object Rejected : OmemoInspection
    data class Unsupported(val content: OmemoContent) : OmemoInspection
}

/**
 * Semantic codec only. Callers must validate the carrier before using its result.
 * Not installed in ingress until ciphertext and client-owned status can be persisted.
 */
internal object OmemoContentCodec {
    private const val MAX_CHARACTERS = 1_048_576
    private const val MAX_NODES = 1024
    private const val MAX_KEYS = 512

    fun inspect(children: List<XmppElement>): OmemoInspection {
        if (children.size > MAX_NODES) return OmemoInspection.Rejected
        val candidates = children.filter { element ->
            element.name == "encrypted" && OmemoProtocol.entries.any { it.namespace == element.namespace }
        }
        if (candidates.isEmpty()) return OmemoInspection.Absent
        if (candidates.size != 1) return OmemoInspection.Rejected
        val root = candidates.single()
        if (!withinBudget(root)) return OmemoInspection.Rejected
        return try {
            OmemoInspection.Unsupported(parse(root))
        } catch (_: IllegalArgumentException) {
            OmemoInspection.Rejected
        }
    }

    private fun parse(root: XmppElement): OmemoContent {
        val protocol = OmemoProtocol.entries.single { it.namespace == root.namespace }
        root.container(protocol, "encrypted", emptySet())
        require(root.children.size in 1..2 && root.children.all { it.name in setOf("header", "payload") })
        val header = requireNotNull(root.children.singleOrNull { it.name == "header" })
        require(root.children.count { it.name == "payload" } <= 1)
        header.container(protocol, "header", setOf("sid"))
        val sender = device(header.attributes["sid"])
        val keys: List<OmemoRecipientKey>
        val iv: String?
        if (protocol == OmemoProtocol.LEGACY) {
            require(header.children.size in 2..MAX_KEYS + 1)
            require(header.children.last().name == "iv")
            keys = header.children.dropLast(1).map { key(it, protocol, null) }
            iv = binary(header.children.last(), protocol, "iv", emptySet())
        } else {
            require(header.children.isNotEmpty() && header.children.size <= MAX_KEYS)
            val recipients = hashSetOf<String>()
            keys = header.children.flatMap { group ->
                group.container(protocol, "keys", setOf("jid"))
                val jid = requireNotNull(group.attributes["jid"])
                require(jid.length <= 1024)
                val parsedJid = requireNotNull(runCatching { JidCreate.from(jid) }.getOrNull())
                require(parsedJid.isEntityBareJid)
                require(recipients.add(parsedJid.toString()))
                require(group.children.isNotEmpty())
                group.children.map { key(it, protocol, jid) }
            }
            iv = null
        }
        require(keys.size <= MAX_KEYS)
        require(keys.map { it.recipientBareJid to it.device }.distinct().size == keys.size)
        val payload = root.children.singleOrNull { it.name == "payload" }
            ?.let { binary(it, protocol, "payload", emptySet()) }
        return OmemoContent(protocol, sender, keys, iv, payload)
    }

    private fun key(element: XmppElement, protocol: OmemoProtocol, jid: String?): OmemoRecipientKey {
        val flag = if (protocol == OmemoProtocol.LEGACY) "prekey" else "kex"
        val ciphertext = binary(element, protocol, "key", setOf("rid", flag))
        val exchange = when (element.attributes[flag]) {
            null, "false", "0" -> false
            "true", "1" -> true
            else -> throw IllegalArgumentException()
        }
        return OmemoRecipientKey(jid, device(element.attributes["rid"]), exchange, ciphertext)
    }

    private fun device(raw: String?): Long {
        require(raw != null && raw.isNotEmpty() && raw.all { it in '0'..'9' })
        val value = requireNotNull(raw.toLongOrNull())
        require(value in 1L..Int.MAX_VALUE.toLong())
        return value
    }

    private fun XmppElement.container(protocol: OmemoProtocol, name: String, attributes: Set<String>) {
        require(this.name == name && namespace == protocol.namespace)
        require(this.attributes.keys.all { it in attributes })
        require(text.orEmpty().all(::xmlWhitespace))
    }

    private fun binary(element: XmppElement, protocol: OmemoProtocol, name: String, attributes: Set<String>): String {
        require(element.name == name && element.namespace == protocol.namespace)
        require(element.attributes.keys.all { it in attributes } && element.children.isEmpty())
        val encoded = element.text.orEmpty().filterNot(::xmlWhitespace)
        // Legacy empty-body ciphertext is empty; its authentication tag travels with the key.
        require(encoded.isNotEmpty() || (protocol == OmemoProtocol.LEGACY && name == "payload"))
        require(encoded.length % 4 == 0)
        val bytes = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(bytes) == encoded)
        return encoded
    }

    private fun xmlWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\r' || char == '\n'

    private fun withinBudget(root: XmppElement): Boolean {
        var characters = 0L
        var nodes = 0
        fun visit(element: XmppElement, depth: Int): Boolean {
            if (depth > 3 || ++nodes > MAX_NODES || element.children.size > MAX_NODES) return false
            characters += element.name.length.toLong() + (element.namespace?.length ?: 0) + (element.text?.length ?: 0)
            if (element.attributes.size > 2) return false
            for ((key, value) in element.attributes) characters += key.length.toLong() + value.length
            return characters <= MAX_CHARACTERS && element.children.all { visit(it, depth + 1) }
        }
        return visit(root, 0)
    }
}
