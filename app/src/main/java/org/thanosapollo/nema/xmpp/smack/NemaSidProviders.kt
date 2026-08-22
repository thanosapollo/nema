package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.ParserUtils
import org.jivesoftware.smack.util.XmlStringBuilder
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.sid.StableUniqueStanzaIdManager
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jivesoftware.smackx.sid.element.StanzaIdElement
import org.jivesoftware.smackx.sid.provider.OriginIdProvider
import org.jivesoftware.smackx.sid.provider.StanzaIdProvider
import org.jxmpp.jid.impl.JidCreate

private const val INVALID_SID_VALUE = "nema-invalid-sid"
private val sidProviderLock = Any()

internal class NemaOriginIdElement(
    id: String,
    val structurallyValid: Boolean,
) : OriginIdElement(id) {
    override fun toXML(xmlEnvironment: XmlEnvironment): CharSequence =
        if (structurallyValid) super.toXML(xmlEnvironment) else ""
}

internal class NemaStanzaIdElement(
    id: String,
    by: String,
    val structurallyValid: Boolean,
) : StanzaIdElement(id, by) {
    override fun toXML(xmlEnvironment: XmlEnvironment): XmlStringBuilder =
        if (structurallyValid) super.toXML(xmlEnvironment) else XmlStringBuilder()
}

internal object NemaOriginIdProvider : ExtensionElementProvider<OriginIdElement>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): OriginIdElement {
        val parsed = parseSidElement(parser, initialDepth, origin = true)
        return NemaOriginIdElement(parsed.id?.takeIf(String::isNotEmpty) ?: INVALID_SID_VALUE, parsed.valid)
    }
}

internal object NemaStanzaIdProvider : ExtensionElementProvider<StanzaIdElement>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): StanzaIdElement {
        val parsed = parseSidElement(parser, initialDepth, origin = false)
        return NemaStanzaIdElement(
            parsed.id?.takeIf(String::isNotEmpty) ?: INVALID_SID_VALUE,
            parsed.by?.takeIf(String::isNotEmpty) ?: INVALID_SID_VALUE,
            parsed.valid,
        )
    }
}

private fun parseSidElement(
    parser: XmlPullParser,
    initialDepth: Int,
    origin: Boolean,
): ParsedSid {
    val element = if (origin) OriginIdElement.ELEMENT else StanzaIdElement.ELEMENT
    val attributes = (0 until parser.attributeCount).associate {
        (parser.getAttributeNamespace(it).orEmpty() to parser.getAttributeName(it)) to parser.getAttributeValue(it)
    }
    val id = attributes["" to "id"]
    val by = attributes["" to "by"]
    var valid = parser.name == element && parser.namespace == StableUniqueStanzaIdManager.NAMESPACE &&
        !id.isNullOrEmpty() && if (origin) {
        attributes.keys == setOf("" to "id")
    } else {
        attributes.keys == setOf("" to "id", "" to "by") && !by.isNullOrEmpty() &&
            by.none(Char::isWhitespace) &&
            runCatching { JidCreate.from(by) }.isSuccess
    }
    while (true) {
        when (parser.next()) {
            XmlPullParser.Event.START_ELEMENT -> {
                valid = false
                ParserUtils.forwardToEndTagOfDepth(parser, parser.depth)
            }
            XmlPullParser.Event.TEXT_CHARACTERS -> valid = false
            XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) break
            XmlPullParser.Event.END_DOCUMENT -> throw IOException("SID element ended before its closing tag")
            else -> Unit
        }
    }
    return ParsedSid(id, by, valid)
}

private data class ParsedSid(val id: String?, val by: String?, val valid: Boolean)

internal fun installNemaSidProviders() = synchronized(sidProviderLock) {
    installSidProvider(OriginIdElement.ELEMENT, NemaOriginIdProvider, OriginIdProvider::class.java)
    installSidProvider(StanzaIdElement.ELEMENT, NemaStanzaIdProvider, StanzaIdProvider::class.java)
}

private fun installSidProvider(
    element: String,
    provider: ExtensionElementProvider<*>,
    stockProvider: Class<out ExtensionElementProvider<*>>,
) {
    val current = ProviderManager.getExtensionProvider(element, StableUniqueStanzaIdManager.NAMESPACE)
    if (current === provider) return
    check(current != null && current.javaClass == stockProvider) { "Unexpected SID provider owner" }
    ProviderManager.addExtensionProvider(element, StableUniqueStanzaIdManager.NAMESPACE, provider)
}
