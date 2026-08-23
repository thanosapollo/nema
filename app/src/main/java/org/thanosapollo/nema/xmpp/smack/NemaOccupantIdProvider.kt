package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.MessageOrPresence
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.util.XmlStringBuilder
import org.jivesoftware.smack.xml.XmlPullParser

private const val OCCUPANT_ID_ELEMENT = "occupant-id"
private const val OCCUPANT_ID_NAMESPACE = "urn:xmpp:occupant-id:0"

internal data class NemaOccupantIdElement(
    val id: String?,
    val structurallyValid: Boolean,
) : ExtensionElement {
    override fun getElementName() = OCCUPANT_ID_ELEMENT
    override fun getNamespace() = OCCUPANT_ID_NAMESPACE
    override fun toXML(xmlEnvironment: XmlEnvironment?): CharSequence =
        if (!structurallyValid) "" else XmlStringBuilder(this)
            .attribute("id", checkNotNull(id))
            .closeEmptyElement()
}

internal object NemaOccupantIdProvider : ExtensionElementProvider<NemaOccupantIdElement>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): NemaOccupantIdElement {
        val id = parser.getAttributeValue("", "id")
        val length = id?.codePointCount(0, id.length) ?: 0
        var valid = parser.name == OCCUPANT_ID_ELEMENT &&
            parser.namespace == OCCUPANT_ID_NAMESPACE &&
            parser.attributeCount == 1 &&
            parser.getAttributeNamespace(0).isNullOrEmpty() &&
            parser.getAttributeName(0) == "id" &&
            length in 1..128
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> valid = false
                XmlPullParser.Event.TEXT_CHARACTERS,
                XmlPullParser.Event.IGNORABLE_WHITESPACE,
                XmlPullParser.Event.ENTITY_REFERENCE,
                XmlPullParser.Event.OTHER,
                -> valid = false
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) {
                    return NemaOccupantIdElement(id, valid)
                }
                XmlPullParser.Event.END_DOCUMENT ->
                    throw IOException("Occupant ID ended before its closing tag")
                else -> Unit
            }
        }
    }
}

internal fun MessageOrPresence<*>.nemaOccupantId(): String? {
    val matches = getExtensions(OCCUPANT_ID_ELEMENT, OCCUPANT_ID_NAMESPACE)
    if (matches.size != 1) return null
    val occupantId = matches.single() as? NemaOccupantIdElement ?: return null
    return occupantId.id.takeIf { occupantId.structurallyValid }
}
