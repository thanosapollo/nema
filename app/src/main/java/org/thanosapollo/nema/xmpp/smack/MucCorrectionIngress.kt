package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.message_correct.element.MessageCorrectExtension
import org.jivesoftware.smackx.message_correct.provider.MessageCorrectProvider
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.storage.MucClaimState
import org.thanosapollo.nema.storage.MucEventFacts
import org.thanosapollo.nema.storage.MucOccupantEvidence
import org.thanosapollo.nema.storage.MucPayloadState

internal class NemaCorrectionElement(val wireId: String?, val structurallyValid: Boolean) :
    MessageCorrectExtension(wireId?.takeIf(String::isNotEmpty) ?: "nema-invalid-correction") {
    override fun toXML(xmlEnvironment: XmlEnvironment): org.jivesoftware.smack.util.XmlStringBuilder =
        if (wireId.isNullOrEmpty()) org.jivesoftware.smack.util.XmlStringBuilder() else super.toXML(xmlEnvironment)
}

private object NemaCorrectionProvider : ExtensionElementProvider<NemaCorrectionElement>() {
    override fun parse(parser: XmlPullParser, initialDepth: Int, xmlEnvironment: XmlEnvironment): NemaCorrectionElement {
        val id = parser.getAttributeValue("", "id")
        var valid = !id.isNullOrEmpty() && parser.attributeCount == 1 &&
            parser.getAttributeNamespace(0).isNullOrEmpty() && parser.getAttributeName(0) == "id"
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT, XmlPullParser.Event.TEXT_CHARACTERS,
                XmlPullParser.Event.IGNORABLE_WHITESPACE, XmlPullParser.Event.ENTITY_REFERENCE,
                XmlPullParser.Event.OTHER -> valid = false
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) return NemaCorrectionElement(id, valid)
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("Correction ended before its closing tag")
                else -> Unit
            }
        }
    }
}

internal fun installNemaCorrectionProvider() = synchronized(NemaCorrectionProvider) {
    val current = ProviderManager.getExtensionProvider(MessageCorrectExtension.ELEMENT, MessageCorrectExtension.NAMESPACE)
    check(current === NemaCorrectionProvider || current is MessageCorrectProvider) { "Unexpected correction provider owner" }
    if (current !== NemaCorrectionProvider) ProviderManager.addExtensionProvider(
        MessageCorrectExtension.ELEMENT, MessageCorrectExtension.NAMESPACE, NemaCorrectionProvider)
}

// Acquisition only. A claim and target locator never imply an accepted correction link.
internal fun Message.mucEventFacts(
    attempt: SessionAttemptIdentity, facts: RoomConsumerFacts?, evidence: MucOccupantEvidence,
): MucEventFacts? {
    val sender = from?.takeIf { it.isEntityFullJid } ?: return null
    if (type != Message.Type.groupchat || facts?.lease?.attempt != attempt ||
        facts.lease.authority != sender.asBareJid().toString()) return null
    val replacements = getExtensions(MessageCorrectExtension.ELEMENT, MessageCorrectExtension.NAMESPACE)
    val replace = (replacements.singleOrNull() as? NemaCorrectionElement)?.takeIf { it.structurallyValid }
    val claim = when {
        replacements.isEmpty() -> MucClaimState.NONE
        replace != null -> MucClaimState.VALID
        else -> MucClaimState.INVALID
    }
    val occupant = nemaOccupantId().takeIf { facts.occupantIds }
    return MucEventFacts(stanzaId?.takeIf(String::isNotEmpty), replace?.idInitialMessage, claim, occupant,
        if (occupant == null) MucOccupantEvidence.UNKNOWN else evidence,
        if (!body.isNullOrBlank() && extensions.all { (it.namespace to it.elementName) in mucPlainMetadata })
            MucPayloadState.PLAIN else MucPayloadState.UNSUPPORTED)
}

internal fun StableIdMessageDecision.retainLiveMucFacts(
    envelope: org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope,
    facts: RoomConsumerFacts?, currentAttempt: SessionAttemptIdentity?, liveEpoch: String,
): org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope? {
    if (attempt != currentAttempt) return null
    if (carbonDirection != null || sentTimeSource != null ||
        org.jivesoftware.smackx.delay.packet.DelayInformation.from(message) != null) return envelope
    val retained = message.mucEventFacts(attempt, facts, MucOccupantEvidence.LIVE_ROOM) ?: return envelope
    return envelope.copy(mucFacts = retained, messageId = retained.messageId,
        mucLiveOrderEpoch = liveEpoch.takeIf { retained.evidence == MucOccupantEvidence.LIVE_ROOM })
}

// Unknown extensions are unsupported rather than being stripped into apparently plain text.
private val mucPlainMetadata = setOf(
    "jabber:client" to "body", "jabber:client" to "thread",
    "urn:xmpp:sid:0" to "origin-id", "urn:xmpp:sid:0" to "stanza-id",
    "urn:xmpp:occupant-id:0" to "occupant-id", "urn:xmpp:message-correct:0" to "replace",
    "urn:xmpp:delay" to "delay", "urn:xmpp:mam:tmp" to "archived",
    "http://jabber.org/protocol/muc#user" to "x",
    "urn:xmpp:hints" to "store", "urn:xmpp:hints" to "no-store",
    "urn:xmpp:hints" to "no-copy", "urn:xmpp:hints" to "no-permanent-store",
)
