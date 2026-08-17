package org.thanosapollo.nema.xmpp.rtt

import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.ParserUtils
import org.jivesoftware.smack.xml.XmlPullParser

internal data class RttExtension(val parsed: RttElement?) : ExtensionElement {
    override fun getElementName() = "rtt"
    override fun getNamespace() = RTT_NAMESPACE
    override fun toXML(xmlEnvironment: XmlEnvironment?) = "<rtt xmlns='$RTT_NAMESPACE'/>"
}

internal object NemaRttProvider : ExtensionElementProvider<RttExtension>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): RttExtension {
        val seq = parser.getAttributeValue("", "seq")?.toIntOrNull()
        val event = rttEventNamed(parser.getAttributeValue("", "event"))
        val actions = ArrayList<RttAction>()
        var insertText: StringBuilder? = null
        var insertAt: Int? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> if (parser.depth == initialDepth + 1) {
                    when (parser.name) {
                        "t" -> {
                            insertAt = parser.getAttributeValue("", "p")?.toIntOrNull()
                            insertText = StringBuilder()
                        }
                        "e" -> actions += RttAction.Erase(
                            parser.getAttributeValue("", "p")?.toIntOrNull(),
                            parser.getAttributeValue("", "n")?.toIntOrNull(),
                        )
                        else -> ParserUtils.forwardToEndTagOfDepth(parser, parser.depth)
                    }
                } else if (insertText != null) {
                    ParserUtils.forwardToEndTagOfDepth(parser, parser.depth)
                }
                XmlPullParser.Event.TEXT_CHARACTERS -> insertText?.append(parser.text)
                XmlPullParser.Event.END_ELEMENT -> when {
                    parser.depth == initialDepth + 1 && insertText != null -> {
                        actions += RttAction.Insert(insertAt, insertText.toString())
                        insertText = null
                        insertAt = null
                    }
                    parser.depth == initialDepth -> return RttExtension(
                        if (seq == null || event == null) null else RttElement(seq, event, actions),
                    )
                }
                XmlPullParser.Event.END_DOCUMENT ->
                    return RttExtension(if (seq == null || event == null) null else RttElement(seq, event, actions))
                else -> Unit
            }
        }
    }
}

internal fun installNemaRttProviders() {
    ProviderManager.addExtensionProvider("rtt", RTT_NAMESPACE, NemaRttProvider)
}

internal fun Message.parseRtt(): RttElement? =
    (getExtension("rtt", RTT_NAMESPACE) as? RttExtension)?.parsed
