package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.xml.XmlPullParser
import org.thanosapollo.nema.xmpp.XmppElement
import org.thanosapollo.nema.xmpp.omemo.OmemoProtocol
import org.thanosapollo.nema.xmpp.omemo.OmemoContentCodec
import org.thanosapollo.nema.xmpp.omemo.OmemoInspection
import org.thanosapollo.nema.xmpp.omemo.ProtectedCarrier
import org.thanosapollo.nema.xmpp.omemo.ProtectedContent
import org.thanosapollo.nema.xmpp.omemo.ProtectedRejection

internal class NemaOmemoElement(
    val protocol: OmemoProtocol,
    val tree: XmppElement?,
    val rejection: ProtectedRejection?,
) : ExtensionElement {
    override fun getElementName() = "encrypted"
    override fun getNamespace() = protocol.namespace
    override fun toXML(xmlEnvironment: XmlEnvironment): CharSequence =
        error("Inbound protected evidence must not be serialized as outgoing content")
}

/** Bounds retained subtree state, not the XML lexer's token allocation or the whole stanza. */
internal object NemaOmemoProvider : ExtensionElementProvider<NemaOmemoElement>() {
    private class Node(val name: String, val namespace: String, val attributes: Map<String, String>) {
        val text = StringBuilder()
        val children = mutableListOf<XmppElement>()
        fun freeze() = XmppElement(name, namespace, attributes, text.toString(), children.toList())
    }

    override fun parse(parser: XmlPullParser, initialDepth: Int, xmlEnvironment: XmlEnvironment): NemaOmemoElement {
        val protocol = OmemoProtocol.entries.single { it.namespace == parser.namespace }
        val stack = mutableListOf<Node>()
        var characters = 0L
        var nodes = 0
        var rejection: ProtectedRejection? = null
        var tree: XmppElement? = null
        fun start() {
            characters += parser.name.length + parser.namespace.orEmpty().length
            if (++nodes > 1024 || parser.depth - initialDepth > 3 || parser.attributeCount > 2) {
                rejection = ProtectedRejection.BUDGET
                return
            }
            val attributes = linkedMapOf<String, String>()
            for (index in 0 until parser.attributeCount) {
                val name = parser.getAttributeName(index)
                val value = parser.getAttributeValue(index)
                characters += name.length + value.length
                if (!parser.getAttributeNamespace(index).isNullOrEmpty() || attributes.put(name, value) != null) {
                    rejection = ProtectedRejection.MALFORMED
                }
            }
            if (characters > 1_048_576) rejection = ProtectedRejection.BUDGET
            if (rejection == null) stack += Node(parser.name, parser.namespace.orEmpty(), attributes)
        }
        start()
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> if (rejection == null) start()
                XmlPullParser.Event.TEXT_CHARACTERS, XmlPullParser.Event.IGNORABLE_WHITESPACE,
                XmlPullParser.Event.ENTITY_REFERENCE -> if (rejection == null) {
                    val text = parser.text.orEmpty()
                    characters += text.length
                    if (characters > 1_048_576) rejection = ProtectedRejection.BUDGET
                    else stack.last().text.append(text)
                }
                XmlPullParser.Event.END_ELEMENT -> {
                    if (rejection == null) {
                        val completed = stack.removeAt(stack.lastIndex).freeze()
                        if (stack.isEmpty()) tree = completed else stack.last().children += completed
                    }
                    if (parser.depth == initialDepth) break
                }
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("Protected content has no closing tag")
                else -> Unit
            }
            if (rejection != null) stack.clear()
        }
        return NemaOmemoElement(protocol, tree, rejection)
    }
}

internal fun installNemaOmemoProviders() = synchronized(NemaOmemoProvider) {
    OmemoProtocol.entries.forEach {
        val current = ProviderManager.getExtensionProvider("encrypted", it.namespace)
        check(current == null || current === NemaOmemoProvider) { "Unexpected protected provider owner" }
    }
    OmemoProtocol.entries.forEach { ProviderManager.addExtensionProvider("encrypted", it.namespace, NemaOmemoProvider) }
}

internal fun Message.hasProtectedContent(): Boolean = extensions.any {
    it.elementName == "encrypted" && OmemoProtocol.entries.any { protocol -> protocol.namespace == it.namespace }
}

internal fun Message.protectedContent(carrier: ProtectedCarrier): ProtectedContent? {
    val selected = extensions.filter {
        it.elementName == "encrypted" && OmemoProtocol.entries.any { protocol -> protocol.namespace == it.namespace }
    }
    if (selected.isEmpty()) return null
    val protocols = selected.map { extension -> OmemoProtocol.entries.single { it.namespace == extension.namespace } }.toSet()
    val owned = selected.singleOrNull() as? NemaOmemoElement
    val inspected = owned?.tree?.let { OmemoContentCodec.inspect(listOf(it)) }
    val reason = when {
        selected.size != 1 -> ProtectedRejection.DUPLICATE
        owned == null -> ProtectedRejection.PROVIDER
        owned.rejection != null -> owned.rejection
        inspected !is OmemoInspection.Unsupported -> ProtectedRejection.MALFORMED
        else -> null
    }
    // Overlong addressing is not silently truncated into purported complete provenance.
    val bounded = listOf(carrier.from, carrier.to, carrier.outerFrom, carrier.outerTo, carrier.archiveAuthority, carrier.resultId, carrier.archiveScope)
        .all { it == null || it.length <= 4096 }
    val observation = if (bounded) carrier else ProtectedCarrier(carrier.kind, null, null)
    return ProtectedContent(protocols, (inspected as? OmemoInspection.Unsupported)?.content.takeIf { reason == null && bounded },
        if (bounded) reason else ProtectedRejection.CARRIER_BUDGET, listOf(observation))
}
