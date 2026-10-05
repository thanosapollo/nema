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

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class CarbonMessageMappingTest {
    @org.junit.Before fun initialize() {
        SmackAndroid.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        installNemaOmemoProviders()
        installNemaCarbonProvider()
    }
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
            inner("peer@example.org/phone", "$own/device", body = null, extension = unknown),
            inner("peer@example.org/phone", "$own/device", Message.Type.headline),
        ).forEach { assertNull(mapped(CarbonExtension.Direction.received, it)) }
        assertEquals("body", mapped(CarbonExtension.Direction.received,
            inner("peer@example.org/phone", "$own/device", extension = unknown))?.message?.body)
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
                inner("peer@example.org/phone", "$own/device", body = null, extension = extension("wrong", namespace)),
            ))
            assertEquals("wrong element beside a body in $namespace", "body", mapped(
                CarbonExtension.Direction.received,
                inner("peer@example.org/phone", "$own/device", extension = extension("wrong", namespace)),
            )?.message?.body)
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
    fun `incumbent encrypted placeholder survives carbon mapping`() {
        val payload = StandardExtensionElement.builder("x", "jabber:x:encrypted").setText("cipher").build()
        val carbon = requireNotNull(mapped(CarbonExtension.Direction.received,
            inner("peer@example.org/phone", "$own/device", body = null, extension = payload)))
        val envelope = requireNotNull(carbon.message.toIncomingEnvelope(ProtectedFixtures.attempt, own,
            carbonDirection = carbon.carbonDirection, protectedCarrier = carbon.protectedCarrier))
        assertEquals("Encrypted message", envelope.body)
        assertNull(envelope.protection)
    }

    private fun nativeCarbon(direction: String, content: String): Message {
        val from = if (direction == "sent") "$own/laptop" else "peer@example.org/phone"
        val to = if (direction == "sent") "peer@example.org/phone" else "$own/device"
        return org.jivesoftware.smack.util.PacketParserUtils.parseStanza(
            "<message xmlns='jabber:client' from='$own' to='$own/device'>" +
                "<$direction xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' type='chat' from='$from' to='$to' id='wire'>$content</message>" +
                "</forwarded></$direction></message>")
    }

    @Test
    fun `exact native protected carbons retain evidence and untouched fallback`() {
        for (protocol in org.thanosapollo.nema.xmpp.omemo.OmemoProtocol.entries) {
            for (direction in listOf("sent", "received")) for (fallback in listOf("", "untouched fallback")) {
                val cases = listOf(
                    ProtectedFixtures.encrypted(protocol) to "UNSUPPORTED_PAYLOAD",
                    ProtectedFixtures.encrypted(protocol, null) to "UNSUPPORTED_HEADER_ONLY",
                    "<encrypted xmlns='${protocol.namespace}'/>" to "REJECTED",
                )
                for ((content, state) in cases) {
                    val body = if (fallback.isEmpty()) "" else "<body>$fallback</body>"
                    val carbon = requireNotNull(nativeCarbon(direction, content + body)
                        .classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own))
                    val envelope = requireNotNull(carbon.message.toIncomingEnvelope(ProtectedFixtures.attempt, own,
                        carbonDirection = carbon.carbonDirection, protectedCarrier = carbon.protectedCarrier))
                    val evidence = requireNotNull(envelope.protection)
                    assertEquals(fallback, envelope.body)
                    assertEquals(state, evidence.state.name)
                    assertEquals(setOf(protocol), evidence.protocols)
                    assertEquals(carbon.protectedCarrier, evidence.carriers.single())
                    assertEquals(direction == "sent", envelope.outbound)
                    if (state == "REJECTED") {
                        assertNull(evidence.content)
                        assertEquals(org.thanosapollo.nema.xmpp.omemo.ProtectedRejection.MALFORMED, evidence.rejection)
                    } else {
                        assertNull(evidence.rejection)
                        assertEquals(ProtectedFixtures.envelope(protocol,
                            if (state == "UNSUPPORTED_PAYLOAD") "AQID" else null).protection!!.content, evidence.content)
                    }
                }
            }
        }
    }

    private val eme = "<encryption xmlns='urn:xmpp:eme:0' namespace='eu.siacs.conversations.axolotl' name='OMEMO'/>"

    @Test
    fun `authentic native carbons admit a body beside unknown inert extensions like live delivery`() {
        val decorations = listOf(
            eme,
            "<future xmlns='urn:example:future'><child>opaque</child></future>",
            "$eme<store xmlns='urn:xmpp:hints'/><future xmlns='urn:example:future'/>",
        )
        for (direction in listOf("sent", "received")) for (decoration in decorations) {
            val content = "<body>hello</body>$decoration"
            val carbon = requireNotNull(nativeCarbon(direction, content)
                .classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own)) { "$direction $decoration" }
            val envelope = requireNotNull(carbon.message.toIncomingEnvelope(ProtectedFixtures.attempt, own,
                carbonDirection = carbon.carbonDirection, protectedCarrier = carbon.protectedCarrier))
            val direct = requireNotNull(ProtectedFixtures.parse(content).toIncomingEnvelope(ProtectedFixtures.attempt, own))
            assertEquals("hello", envelope.body)
            assertEquals(direct.body, envelope.body)
            assertNull(envelope.protection)
            assertNull(envelope.replaceId)
            assertEquals(false, envelope.receiptRequested)
            assertEquals(direction == "sent", envelope.outbound)
        }
    }

    @Test
    fun `unknown extensions gain no carbon admission or signal authority on their own`() {
        val impostors = listOf(
            "<future xmlns='urn:example:future'/>",
            eme,
            "<received xmlns='urn:example:future' id='wire'/>",
            "<replace xmlns='urn:example:future' id='wire'/>",
            "<reactions xmlns='urn:example:future' id='wire'><reaction>x</reaction></reactions>",
        )
        for (direction in listOf("sent", "received")) for (impostor in impostors) {
            assertNull("$direction $impostor", nativeCarbon(direction, impostor)
                .classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own))
            val bodied = requireNotNull(nativeCarbon(direction, "<body>text</body>$impostor")
                .classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own))
            val envelope = requireNotNull(bodied.message.toIncomingEnvelope(ProtectedFixtures.attempt, own,
                carbonDirection = bodied.carbonDirection, protectedCarrier = bodied.protectedCarrier))
            assertEquals("text", envelope.body)
            assertNull(envelope.replaceId)
            assertNull(bodied.message.toIncomingSignal(ProtectedFixtures.attempt, own))
        }
        val composing = requireNotNull(nativeCarbon("received",
            "<composing xmlns='http://jabber.org/protocol/chatstates'/>$eme").classifyCarrier(own, "$own/device"))
        assertEquals(BodylessCarbonEffect.CHAT_STATE, composing.bodylessCarbonEffect())
    }

    @Test
    fun `unknown extensions never relax carbon addressing private or muc exclusions`() {
        val content = "<body>hello</body>$eme"
        fun raw(outerFrom: String, outerTo: String, direction: String = "received", extra: String = "") =
            org.jivesoftware.smack.util.PacketParserUtils.parseStanza<Message>(
                "<message xmlns='jabber:client' from='$outerFrom' to='$outerTo'>" +
                    "<$direction xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                    "<message xmlns='jabber:client' type='chat' from='peer@example.org/phone' to='$own/device'>" +
                    "$content$extra</message></forwarded></$direction></message>")
        val refused = listOf(
            raw("peer@example.org", "$own/device"),
            raw("$own/other", "$own/device"),
            raw(own, "$own/elsewhere"),
            raw("other@example.org", "other@example.org/device"),
            raw(own, "$own/device", extra = "<private xmlns='urn:xmpp:carbons:2'/>"),
            raw(own, "$own/device", extra = "<x xmlns='http://jabber.org/protocol/muc#user'/>"),
        )
        refused.forEachIndexed { index, message ->
            assertNull("case $index", message.classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own))
        }
        assertNotNull(raw(own, "$own/device").classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own))
        val wrongAccount = nativeCarbon("sent", content).classifyCarrier("other@example.org", "other@example.org/device")
        assertNull(wrongAccount.toTrustedCarbonMessage("other@example.org"))
    }

    @Test
    fun `unknown versions gain no carbon protection and fall back like live delivery`() {
        for (namespace in listOf("urn:xmpp:omemo:1", "urn:xmpp:omemo:99")) {
            for (direction in listOf("sent", "received")) for (body in listOf("", "<body>ordinary fallback</body>")) {
                val content = "<encrypted xmlns='$namespace'><payload>AQID</payload></encrypted>"
                val carbon = nativeCarbon(direction, content + body)
                    .classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own)
                val direct = ProtectedFixtures.parse(content + body).toIncomingEnvelope(ProtectedFixtures.attempt, own)
                if (body.isEmpty()) {
                    assertNull(carbon)
                    assertNull(direct)
                } else {
                    val mapped = requireNotNull(requireNotNull(carbon).message.toIncomingEnvelope(ProtectedFixtures.attempt,
                        own, carbonDirection = carbon.carbonDirection, protectedCarrier = carbon.protectedCarrier))
                    for (envelope in listOf(mapped, requireNotNull(direct))) {
                        assertEquals("ordinary fallback", envelope.body)
                        assertNull(envelope.protection)
                    }
                }
            }
        }
        assertNotNull(nativeCarbon("received", "<body>ordinary fallback</body>")
            .classifyCarrier(own, "$own/device").toTrustedCarbonMessage(own))
    }
}
