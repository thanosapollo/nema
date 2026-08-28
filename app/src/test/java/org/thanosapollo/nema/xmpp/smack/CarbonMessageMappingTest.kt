package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jxmpp.jid.impl.JidCreate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class CarbonMessageMappingTest {
    private val own = "account@example.org"

    private fun inner(
        from: String,
        to: String,
        type: Message.Type = Message.Type.chat,
        body: String? = "body",
        extension: StandardExtensionElement? = null,
    ): Message = StanzaBuilder.buildMessage()
        .from(JidCreate.from(from))
        .to(JidCreate.from(to))
        .ofType(type)
        .apply { body?.let(::setBody) }
        .apply { extension?.let(::addExtension) }
        .build()

    private fun mapped(
        direction: CarbonExtension.Direction,
        message: Message,
        joinedRoom: (String) -> Boolean = { false },
    ) = StanzaBuilder.buildMessage()
        .from(JidCreate.entityBareFrom(own))
        .to(JidCreate.entityFullFrom("$own/device"))
        .addExtension(CarbonExtension(direction, Forwarded(message)))
        .build()
        .classifyCarrier(own, "$own/device")
        .toTrustedCarbonMessage(own, joinedRoom = joinedRoom)

    private fun extension(element: String, namespace: String) =
        StandardExtensionElement.builder(element, namespace).build()

    @Test
    fun `conversation matrix maps only direct self and sent groupchat`() {
        val cases = listOf(
            Triple(CarbonExtension.Direction.received, inner("peer@example.org/phone", "$own/device"), true),
            Triple(CarbonExtension.Direction.sent, inner("$own/laptop", "peer@example.org/phone"), true),
            Triple(CarbonExtension.Direction.sent, inner("$own/laptop", "$own/device"), true),
            Triple(CarbonExtension.Direction.received, inner("$own/laptop", "$own/device"), true),
            Triple(CarbonExtension.Direction.sent, inner("$own/laptop", "room@conference.example.org", Message.Type.groupchat), true),
            Triple(CarbonExtension.Direction.received, inner("room@conference.example.org/nick", "$own/device", Message.Type.groupchat), false),
            Triple(CarbonExtension.Direction.received, inner("room@conference.example.org/nick", "$own/device"), true),
            Triple(CarbonExtension.Direction.sent, inner("$own/laptop", "room@conference.example.org/nick"), true),
            Triple(CarbonExtension.Direction.received, inner("full@example.org/resource", "$own/device"), true),
        )
        cases.forEachIndexed { index, (direction, message, expected) ->
            assertEquals("case $index", expected, mapped(direction, message) != null)
        }
        val group = requireNotNull(mapped(CarbonExtension.Direction.sent, cases[4].second))
        val row = requireNotNull(group.message.toIncomingEnvelope(
            SessionAttemptIdentity(AccountId.require("a"), ConnectionGeneration.require(1),
                ConnectionAttempt.require(1), LifecycleEpoch.require(1)), own,
            carbonDirection = group.carbonDirection,
        ))
        assertEquals("room@conference.example.org", row.peer)
        assertEquals(true, row.outbound)
        val joined: (String) -> Boolean = { it == "room@conference.example.org" }
        assertNull(mapped(CarbonExtension.Direction.received, cases[6].second, joined))
        assertNull(mapped(CarbonExtension.Direction.sent, cases[7].second, joined))
    }

    @Test
    fun `muc user and ineligible message classes are rejected`() {
        val mucUser = extension("x", "http://jabber.org/protocol/muc#user")
        val private = extension("private", CarbonExtension.NAMESPACE)
        val unknown = extension("future", "urn:example:future")
        listOf(
            inner("room@conference.example.org/nick", "$own/device", extension = mucUser),
            inner("peer@example.org/phone", "$own/device", extension = private),
            inner("peer@example.org/phone", "$own/device", extension = unknown),
            inner("peer@example.org/phone", "$own/device", Message.Type.headline),
        ).forEach { assertNull(mapped(CarbonExtension.Direction.received, it)) }
    }

    @Test
    fun `exact payload pair matrix rejects a wrong element per namespace family`() {
        val families = listOf(
            "http://jabber.org/protocol/chatstates" to listOf("active", "composing", "gone", "inactive", "paused"),
            "urn:xmpp:receipts" to listOf("request", "received"),
            "urn:xmpp:chat-markers:0" to listOf("acknowledged", "displayed", "markable", "received"),
            "urn:xmpp:reactions:0" to listOf("reactions"),
            "urn:xmpp:rtt:0" to listOf("rtt"),
            "urn:xmpp:sid:0" to listOf("origin-id", "stanza-id"),
            "urn:xmpp:reply:0" to listOf("reply"),
            "urn:xmpp:fallback:0" to listOf("fallback"),
            "urn:xmpp:hints" to listOf("store", "no-store", "no-permanent-store", "no-copy"),
            "urn:xmpp:delay" to listOf("delay"),
            "urn:xmpp:occupant-id:0" to listOf("occupant-id"),
            "urn:xmpp:message-correct:0" to listOf("replace"),
            "urn:xmpp:mam:tmp" to listOf("archived"),
            "jabber:x:oob" to listOf("x"),
            "eu.siacs.conversations.axolotl" to listOf("encrypted"),
            "urn:xmpp:omemo:1" to listOf("encrypted"),
            "urn:xmpp:omemo:2" to listOf("encrypted"),
            "jabber:x:encrypted" to listOf("x"),
        )
        families.forEach { (namespace, elements) ->
            elements.forEach { element ->
                assertNotNull("$element $namespace", mapped(
                    CarbonExtension.Direction.received,
                    inner("peer@example.org/phone", "$own/device", body = null, extension = extension(element, namespace)),
                ))
            }
            assertNull("wrong element in $namespace", mapped(
                CarbonExtension.Direction.received,
                inner("peer@example.org/phone", "$own/device", extension = extension("wrong", namespace)),
            ))
        }
    }

    @Test
    fun `ejabberd archived metadata does not suppress a body carbon`() {
        val message = StanzaBuilder.buildMessage("ejabberd-carbon")
            .from(JidCreate.entityFullFrom("peer@example.org/phone"))
            .to(JidCreate.entityFullFrom("$own/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(extension("archived", "urn:xmpp:mam:tmp"))
            .build()

        assertEquals("body", requireNotNull(mapped(CarbonExtension.Direction.received, message)).message.body)
    }

    @Test
    fun `body carbon with receipt request maps once without live receipt authority`() {
        val message = StanzaBuilder.buildMessage("carbon-body")
            .from(JidCreate.entityFullFrom("peer@example.org/phone"))
            .to(JidCreate.entityFullFrom("$own/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(DeliveryReceiptRequest())
            .build()
        val carbon = requireNotNull(mapped(CarbonExtension.Direction.received, message))
        assertEquals("body", carbon.message.body)
        assertEquals(true, carbon.forwarded)
        assertEquals(false, carbon.isRawLive(mamCarrier = false))
        val row = requireNotNull(carbon.message.toIncomingEnvelope(
            SessionAttemptIdentity(AccountId.require("a"), ConnectionGeneration.require(1),
                ConnectionAttempt.require(1), LifecycleEpoch.require(1)), own,
            carbonDirection = carbon.carbonDirection,
        ))
        assertEquals(false, row.receiptRequested)
    }

    @Test
    fun `encrypted placeholder families survive carbon mapping`() {
        val encrypted = listOf(
            StandardExtensionElement.builder("encrypted", "eu.siacs.conversations.axolotl")
                .addElement("payload", "cipher").build(),
            StandardExtensionElement.builder("encrypted", "urn:xmpp:omemo:1")
                .addElement("payload", "cipher").build(),
            StandardExtensionElement.builder("encrypted", "urn:xmpp:omemo:2")
                .addElement("payload", "cipher").build(),
            StandardExtensionElement.builder("x", "jabber:x:encrypted").setText("cipher").build(),
        )
        encrypted.forEach { payload ->
            val carbon = requireNotNull(mapped(
                CarbonExtension.Direction.received,
                inner("peer@example.org/phone", "$own/device", body = null, extension = payload),
            ))
            assertEquals("Encrypted message", carbon.message.encryptedMessagePlaceholder())
        }
    }
}
