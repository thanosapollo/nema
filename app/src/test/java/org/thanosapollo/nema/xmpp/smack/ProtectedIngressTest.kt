package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.omemo.*
import org.thanosapollo.nema.xmpp.transport.*

internal object ProtectedFixtures {
    val attempt = SessionAttemptIdentity(AccountId.require("account"), ConnectionGeneration.require(1),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1))
    const val self = "account@example.org"
    const val peer = "peer@example.org"
    fun encrypted(protocol: OmemoProtocol, payload: String? = "AQID"): String {
        val key = "<key rid='2'>BAUG</key>"
        val header = if (protocol == OmemoProtocol.LEGACY) "$key<iv>BwgJ</iv>" else "<keys jid='$self'>$key</keys>"
        return "<encrypted xmlns='${protocol.namespace}'><header sid='1'>$header</header>" +
            (payload?.let { "<payload>$it</payload>" } ?: "") + "</encrypted>"
    }
    fun parse(content: String, id: String = "wire", from: String = peer, to: String = self): Message =
        PacketParserUtils.parseStanza("<message xmlns='jabber:client' type='chat' from='$from' to='$to' id='$id'>$content</message>") as Message
    fun envelope(protocol: OmemoProtocol, payload: String? = "AQID", id: String = "wire"): IncomingMessageEnvelope =
        requireNotNull(parse(encrypted(protocol, payload), id).toIncomingEnvelope(attempt, self))
    fun carried(
        protocol: OmemoProtocol,
        carrier: ProtectedCarrierKind,
        outbound: Boolean = false,
        resultId: String = "r1",
        protected: Boolean = true,
        id: String = "wire",
        payload: String = "AQID",
    ): IncomingMessageEnvelope {
        val from = if (outbound) self else peer
        val to = if (outbound) peer else self
        val content = "<body>fallback</body>" + if (protected) encrypted(protocol, payload) else ""
        val inner = "<message xmlns='jabber:client' type='chat' from='$from' to='$to' id='$id'>$content</message>"
        if (carrier == ProtectedCarrierKind.LIVE) return requireNotNull(
            PacketParserUtils.parseStanza<Message>(inner).toIncomingEnvelope(attempt, self))
        if (carrier == ProtectedCarrierKind.MAM) {
            installNemaMamResultProvider()
            val wrapper = PacketParserUtils.parseStanza<Message>(
                "<message xmlns='jabber:client' from='$self'><result xmlns='urn:xmpp:mam:2' queryid='query' id='$resultId'>" +
                    "<forwarded xmlns='urn:xmpp:forward:0'>$inner</forwarded></result></message>")
            val result = org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension.from(wrapper)
            return requireNotNull(normalizeMamResults(listOf(wrapper), listOf(result), attempt, self, false).single().message)
        }
        installNemaCarbonProvider()
        val direction = if (carrier == ProtectedCarrierKind.SENT_CARBON) "sent" else "received"
        val wrapper = PacketParserUtils.parseStanza<Message>(
            "<message xmlns='jabber:client' from='$self' to='$self/test'><$direction xmlns='urn:xmpp:carbons:2'>" +
                "<forwarded xmlns='urn:xmpp:forward:0'>$inner</forwarded></$direction></message>")
        val admitted = requireNotNull(wrapper.classifyCarrier(self, "$self/test").toTrustedCarbonMessage(self))
        return requireNotNull(admitted.message.toIncomingEnvelope(attempt, self,
            suppliedSentAtEpochMs = admitted.sentAtEpochMs, suppliedSentTimeSource = admitted.sentTimeSource,
            carbonDirection = admitted.carbonDirection, protectedCarrier = admitted.protectedCarrier))
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectedIngressTest {
    @Before fun initialize() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaOmemoProviders()
        installNemaCarbonProvider()
        installNemaMamResultProvider()
    }

    @Test fun exactPresencePreemptsAllOuterControlsIncludingBodylessRejectedAndHeaderOnly() {
        for (protocol in OmemoProtocol.entries) for (encrypted in listOf(
            ProtectedFixtures.encrypted(protocol), ProtectedFixtures.encrypted(protocol, null),
            "<encrypted xmlns='${protocol.namespace}'/>",
            ProtectedFixtures.encrypted(protocol).repeat(2),
        )) {
            val message = ProtectedFixtures.parse(encrypted + """
                <received xmlns='urn:xmpp:receipts' id='ordinary'/>
                <displayed xmlns='urn:xmpp:chat-markers:0' id='ordinary'/>
                <active xmlns='http://jabber.org/protocol/chatstates'/>
                <rtt xmlns='urn:xmpp:rtt:0' seq='0'><t>typing</t></rtt>
                <reactions xmlns='urn:xmpp:reactions:0' id='ordinary'><reaction>👍</reaction></reactions>
                <request xmlns='urn:xmpp:receipts'/><markable xmlns='urn:xmpp:chat-markers:0'/>
                <replace xmlns='urn:xmpp:message-correct:0' id='ordinary'/>
                <x xmlns='jabber:x:oob'><url>https://example.org/untrusted</url></x>
                <reply xmlns='urn:xmpp:reply:0' id='ordinary'/>
            """.trimIndent())
            assertNull(message.toIncomingSignal(ProtectedFixtures.attempt, ProtectedFixtures.self))
            assertNull(message.toIncomingChatState(ProtectedFixtures.attempt, ProtectedFixtures.self))
            assertNull(message.toIncomingRtt(ProtectedFixtures.attempt, ProtectedFixtures.self))
            assertNull(message.toIncomingReaction(ProtectedFixtures.attempt, ProtectedFixtures.self))
            val envelope = requireNotNull(message.toIncomingEnvelope(ProtectedFixtures.attempt, ProtectedFixtures.self))
            assertNotNull(envelope.protection)
            assertEquals("", envelope.body)
            assertFalse(envelope.receiptRequested)
            assertFalse(envelope.markable)
            assertNull(envelope.replaceId)
            assertNull(envelope.attachmentUrl)
            assertNull(envelope.reply)
        }
        assertNotNull(ProtectedFixtures.parse("<received xmlns='urn:xmpp:receipts' id='ordinary'/>")
            .toIncomingSignal(ProtectedFixtures.attempt, ProtectedFixtures.self))
    }

    @Test fun budgetRejectionConsumesOnlyItsSubtreeAndRetainsNextSibling() {
        val encrypted = "<encrypted xmlns='urn:xmpp:omemo:2'><header sid='1'>" +
            "<keys jid='account@example.org'>" + "<key rid='2'>AAAA</key>".repeat(1025) + "</keys></header></encrypted>"
        val message = ProtectedFixtures.parse(encrypted + "<body>exact fallback</body>")
        val envelope = requireNotNull(message.toIncomingEnvelope(ProtectedFixtures.attempt, ProtectedFixtures.self))
        assertEquals(ProtectedRejection.BUDGET, envelope.protection?.rejection)
        assertEquals("exact fallback", envelope.body)
        assertEquals("ordinary", ProtectedFixtures.parse("<body>ordinary</body>").body)
    }

    @Test fun splitTextAndModernAndLegacyCiphertextSurviveNativeParsing() {
        for (protocol in OmemoProtocol.entries) {
            val input = ProtectedFixtures.encrypted(protocol).replace("AQID", "AQ<![CDATA[I]]>D")
            val envelope = requireNotNull(ProtectedFixtures.parse(input).toIncomingEnvelope(ProtectedFixtures.attempt, ProtectedFixtures.self))
            assertEquals("AQID", envelope.protection?.content?.payload)
            assertEquals(protocol, envelope.protection?.content?.protocol)
            assertEquals(envelope.protection, ProtectedContentCodec.decode(envelope.protection!!.state.name,
                ProtectedContentCodec.encode(envelope.protection)))
        }
    }

    @Test fun nearMaximumCiphertextAndEmptyLegacyPayloadRoundTripWithoutTruncation() {
        for (protocol in OmemoProtocol.entries) {
            val payload = "A".repeat(1_040_000)
            val evidence = requireNotNull(ProtectedFixtures.envelope(protocol, payload).protection)
            val encoded = ProtectedContentCodec.encode(evidence)
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size < ProtectedContentCodec.MAX_ENCODED)
            assertEquals(payload, ProtectedContentCodec.decode(evidence.state.name, encoded)?.content?.payload)
            val exhausted = ProtectedFixtures.envelope(protocol, "A".repeat(1_048_576)).protection!!
            assertEquals(ProtectedRejection.BUDGET, exhausted.rejection)
            assertNull(exhausted.content)
        }
        val empty = ProtectedFixtures.envelope(OmemoProtocol.LEGACY, "").protection!!
        assertEquals(ProtectedState.UNSUPPORTED_PAYLOAD, empty.state)
        assertEquals("", empty.content!!.payload)
    }

    @Test fun allAcceptedRecipientKeysAndBoundedCarrierEscapingAreRetained() {
        for (protocol in OmemoProtocol.entries) {
            val flag = if (protocol == OmemoProtocol.LEGACY) "prekey" else "kex"
            val keys = (1..512).joinToString("") { "<key rid='$it' $flag='${it % 2 == 0}'>AQID</key>" }
            val header = if (protocol == OmemoProtocol.LEGACY) "$keys<iv>BAUG</iv>"
                else "<keys jid='account@example.org'>$keys</keys>"
            val wire = "<encrypted xmlns='${protocol.namespace}'><header sid='7'>$header</header><payload>AQID</payload></encrypted>"
            val parsed = requireNotNull(ProtectedFixtures.parse(wire).toIncomingEnvelope(ProtectedFixtures.attempt, ProtectedFixtures.self)?.protection)
            val escaped = "\u0001".repeat(4096)
            val evidence = parsed.copy(carriers = ProtectedCarrierKind.entries.map {
                ProtectedCarrier(it, escaped, escaped, escaped, escaped, escaped, escaped, escaped)
            })
            val encoded = ProtectedContentCodec.encode(evidence)
            val decoded = requireNotNull(ProtectedContentCodec.decode(evidence.state.name, encoded))
            assertEquals(512, decoded.content!!.keys.size)
            assertEquals(evidence, decoded)
            assertTrue(encoded.toByteArray(Charsets.UTF_8).size < ProtectedContentCodec.MAX_ENCODED)
        }
    }

    @Test fun mixedDuplicatesForeignAttributesDepthAndMalformedBase64RemainPresent() {
        val cases = listOf(
            ProtectedFixtures.encrypted(OmemoProtocol.LEGACY) + ProtectedFixtures.encrypted(OmemoProtocol.MODERN),
            "<encrypted xmlns='urn:xmpp:omemo:2' xmlns:foreign='urn:foreign' foreign:sid='1'/>",
            "<encrypted xmlns='urn:xmpp:omemo:2'><a><b><c><d/></c></b></a></encrypted>",
            ProtectedFixtures.encrypted(OmemoProtocol.MODERN, "not-base64"),
        )
        for (wire in cases) {
            val message = ProtectedFixtures.parse(wire)
            val evidence = requireNotNull(message.toIncomingEnvelope(ProtectedFixtures.attempt, ProtectedFixtures.self)?.protection)
            assertEquals(ProtectedState.REJECTED, evidence.state)
            assertNull(evidence.content)
            val decoded = ProtectedContentCodec.decode(evidence.state.name, ProtectedContentCodec.encode(evidence))
            assertEquals(evidence.protocols, decoded?.protocols)
            assertEquals(evidence.rejection, decoded?.rejection)
        }
    }

    @Test fun unknownVersionCannotAuthorizeCarbonAndValidatedCarbonCarriesItsOwnProvenance() {
        for (protocol in OmemoProtocol.entries) {
            val xml = "<message xmlns='jabber:client' from='${ProtectedFixtures.self}' to='${ProtectedFixtures.self}/test'>" +
                "<received xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' type='chat' from='${ProtectedFixtures.peer}' to='${ProtectedFixtures.self}'>" +
                ProtectedFixtures.encrypted(protocol) + "</message></forwarded></received></message>"
            val wrapper = PacketParserUtils.parseStanza(xml) as Message
            val carrier = wrapper.classifyCarrier(ProtectedFixtures.self, "${ProtectedFixtures.self}/test")
            val trusted = requireNotNull(carrier.toTrustedCarbonMessage(ProtectedFixtures.self))
            val mapped = requireNotNull(trusted.message.toIncomingEnvelope(ProtectedFixtures.attempt, ProtectedFixtures.self,
                carbonDirection = trusted.carbonDirection, protectedCarrier = trusted.protectedCarrier))
            assertEquals(ProtectedCarrierKind.RECEIVED_CARBON, mapped.protection?.carriers?.single()?.kind)
            assertEquals(ProtectedFixtures.self, mapped.protection?.carriers?.single()?.outerFrom)
        }
        val unknown = ProtectedFixtures.parse("<encrypted xmlns='urn:xmpp:omemo:99'/>")
        assertFalse(unknown.hasProtectedContent())
        assertNull(MessageCarrier.Carbon(CarbonCarrier.Direction.RECEIVED, unknown, null)
            .toTrustedCarbonMessage(ProtectedFixtures.self))
    }
}
