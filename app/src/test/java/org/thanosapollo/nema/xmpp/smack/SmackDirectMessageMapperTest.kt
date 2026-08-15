package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.Date
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jivesoftware.smackx.sid.element.StanzaIdElement
import org.jxmpp.jid.impl.JidCreate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.oob.oobShare
import org.thanosapollo.nema.xmpp.reply.installNemaReplyProviders
import org.thanosapollo.nema.xmpp.reply.parseReplyBody
import org.thanosapollo.nema.xmpp.reply.REPLY_NAMESPACE
import org.thanosapollo.nema.xmpp.reply.replyFallbackRange
import org.thanosapollo.nema.xmpp.reply.replyReference
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ArchivePageDirection
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.MessageReplyEnvelope
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmackDirectMessageMapperTest {
    @Before
    fun initializeSmack() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaReplyProviders()
    }

    @Test
    fun `outgoing direct message retains operation origin and thread metadata`() {
        val envelope = OutgoingMessageEnvelope(
            accountId = AccountId.require("account"),
            generation = ConnectionGeneration.require(3),
            attempt = 2,
            operationId = "operation",
            originId = "origin",
            recipient = "peer@example.org",
            body = "body",
            thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent")),
        )

        val message = envelope.toSmackMessage()

        assertEquals(Message.Type.chat, message.type)
        assertEquals("operation", message.stanzaId)
        assertEquals(2, envelope.attempt)
        assertEquals("peer@example.org", message.to.toString())
        assertEquals("body", message.body)
        assertEquals("origin", message.getExtension(OriginIdElement::class.java).id)
        assertEquals(envelope.thread, message.toThreadRef())
    }

    @Test
    fun `outgoing groupchat file share uses groupchat type and OOB`() {
        val envelope = OutgoingMessageEnvelope(
            accountId = AccountId.require("account"),
            generation = ConnectionGeneration.require(3),
            attempt = 1,
            operationId = "operation",
            originId = "origin",
            recipient = "room@conference.example.org",
            body = "https://upload.example.org/file",
            thread = null,
            kind = org.thanosapollo.nema.thread.MessageKind.GROUPCHAT,
            attachmentUrl = "https://upload.example.org/file",
            attachmentName = "notes.pdf",
        )

        val message = envelope.toSmackMessage()
        val share = message.oobShare()

        assertEquals(Message.Type.groupchat, message.type)
        assertEquals("https://upload.example.org/file", message.body)
        assertEquals("https://upload.example.org/file", share?.url)
        assertEquals("notes.pdf", share?.description)
    }

    @Test
    fun `outgoing semantic reply carries XEP-0461 metadata and compatibility fallback`() {
        val envelope = OutgoingMessageEnvelope(
            accountId = AccountId.require("account"),
            generation = ConnectionGeneration.require(3),
            attempt = 1,
            operationId = "operation",
            originId = "origin",
            recipient = "peer@example.org",
            body = "Great idea!",
            thread = null,
            reply = MessageReplyEnvelope(
                id = "target-origin-id",
                to = "peer@example.org/device",
                fallbackBody = "We should bake a cake",
                fallbackSender = "Anna",
            ),
        )

        val message = envelope.toSmackMessage()
        val reply = requireNotNull(message.replyReference())
        val fallback = requireNotNull(message.replyFallbackRange())

        assertEquals("target-origin-id", reply.id)
        assertEquals("peer@example.org/device", reply.to)
        assertEquals("> Anna wrote:\n> We should bake a cake\nGreat idea!", message.body)
        assertEquals("> Anna wrote:\n> We should bake a cake\n", message.body.substring(fallback))
    }

    @Test
    fun `incoming direct message retains attempt identity and rejects bodyless stanza`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("incoming")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(OriginIdElement("peer-controlled"))
            .build()
        val bodyless = StanzaBuilder.buildMessage("bodyless")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .build()

        val envelope = requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org"))

        assertEquals(attempt.accountId, envelope.accountId)
        assertEquals(attempt.generation, envelope.generation)
        assertEquals("peer@example.org", envelope.peer)
        assertEquals("peer@example.org", envelope.sender)
        assertEquals(false, envelope.outbound)
        assertEquals("peer-controlled", envelope.originId)
        assertEquals("incoming", envelope.messageId)
        assertEquals("body", envelope.body)
        assertNull(bodyless.toIncomingEnvelope(attempt, "account@example.org"))
    }

    @Test
    fun `normal direct messages map while unsupported archive records are skippable`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val normal = StanzaBuilder.buildMessage("normal")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.normal)
            .setBody("body")
            .build()
        val group = StanzaBuilder.buildMessage("group")
            .from(JidCreate.entityFullFrom("room@example.org/member"))
            .ofType(Message.Type.groupchat)
            .setBody("body")
            .build()

        assertEquals("body", requireNotNull(normal.toIncomingEnvelope(attempt, "account@example.org")).body)
        val mappedGroup = requireNotNull(group.toIncomingEnvelope(attempt, "account@example.org"))
        assertEquals("room@example.org", mappedGroup.peer)
        assertEquals("room@example.org/member", mappedGroup.sender)
        assertEquals(org.thanosapollo.nema.thread.MessageKind.GROUPCHAT, mappedGroup.kind)
    }

    @Test
    fun `incoming semantic reply strips only declared fallback and retains reference`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val prefix = "> Anna wrote:\n> We should bake a cake\n"
        val reply = StandardExtensionElement.builder("reply", "urn:xmpp:reply:0")
            .addAttribute("id", "target-origin-id")
            .addAttribute("to", "anna@example.org/tablet")
            .build()
        val fallbackBody = StandardExtensionElement.builder("body", "urn:xmpp:fallback:0")
            .addAttribute("start", "0")
            .addAttribute("end", prefix.length.toString())
            .build()
        val fallback = StandardExtensionElement.builder("fallback", "urn:xmpp:fallback:0")
            .addAttribute("for", "urn:xmpp:reply:0")
            .addElement(fallbackBody)
            .build()
        val message = StanzaBuilder.buildMessage("reply")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody(prefix + "Great idea!")
            .addExtension(reply)
            .addExtension(fallback)
            .build()

        val envelope = requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org"))

        assertEquals("Great idea!", envelope.body)
        assertEquals("target-origin-id", envelope.reply?.id)
        assertEquals("anna@example.org/tablet", envelope.reply?.to)
        assertEquals("We should bake a cake", envelope.reply?.fallbackBody)
    }

    @Test
    fun `raw XEP reply XML is parsed through Smack unknown extension support`() {
        val message = PacketParserUtils.parseMessage(
            PacketParserUtils.getParserFor(
                """
                <message xmlns='jabber:client' from='peer@example.org/device' type='chat'>
                  <body>&gt; old body&#10;answer</body>
                  <reply xmlns='urn:xmpp:reply:0' id='target-id' to='peer@example.org/device'/>
                  <fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:reply:0'>
                    <body start='0' end='11'/>
                  </fallback>
                </message>
                """.trimIndent(),
            ),
        )

        assertEquals("target-id", message.replyReference()?.id)
        val parsed = message.parseReplyBody()
        assertEquals("answer", parsed.body)
        assertEquals("old body", parsed.fallbackBody)
    }

    @Test
    fun `whole body XEP fallback forms are suppressed`() {
        fun message(fallback: StandardExtensionElement) = StanzaBuilder.buildMessage()
            .setBody("> quoted body")
            .addExtension(fallback)
            .build()
        val bodyRegion = StandardExtensionElement.builder("body", "urn:xmpp:fallback:0").build()
        val withBody = StandardExtensionElement.builder("fallback", "urn:xmpp:fallback:0")
            .addAttribute("for", REPLY_NAMESPACE)
            .addElement(bodyRegion)
            .build()
        val childless = StandardExtensionElement.builder("fallback", "urn:xmpp:fallback:0")
            .addAttribute("for", REPLY_NAMESPACE)
            .build()

        listOf(message(withBody), message(childless)).forEach {
            assertEquals("> quoted body", it.body.substring(requireNotNull(it.replyFallbackRange())))
            assertEquals("", it.parseReplyBody().body)
            assertEquals("quoted body", it.parseReplyBody().fallbackBody)
        }
    }

    @Test
    fun `Nema advertises semantic reply support`() {
        val connection = XMPPTCPConnection(
            XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(JidCreate.domainBareFrom("example.org"))
                .setUsernameAndPassword("account", null)
                .build(),
        )

        advertiseNemaFeatures(connection)

        assertTrue(ServiceDiscoveryManager.getInstanceFor(connection).includesFeature(REPLY_NAMESPACE))
    }

    @Test
    fun `groupchat trusts only room issued stanza ID for reply references`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("occupant-controlled")
            .from(JidCreate.entityFullFrom("room@conference.example.org/alice"))
            .ofType(Message.Type.groupchat)
            .setBody("body")
            .addExtension(StanzaIdElement("room-issued", "room@conference.example.org"))
            .addExtension(StanzaIdElement("foreign", "archive.example.org"))
            .build()

        val envelope = requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org"))

        assertEquals(listOf("room-issued"), envelope.stanzaIds.map { it.id })
        assertEquals(listOf("room@conference.example.org"), envelope.stanzaIds.map { it.by })
    }

    @Test
    fun `groupchat own-message attribution uses the joined room nick`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("own")
            .from(JidCreate.entityFullFrom("room@conference.example.org/ChosenNick"))
            .ofType(Message.Type.groupchat)
            .setBody("body")
            .build()

        assertEquals(
            false,
            requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org")).outbound,
        )
        assertEquals(
            true,
            requireNotNull(
                message.toIncomingEnvelope(
                    attempt,
                    "account@example.org",
                    ownRoomNick = "ChosenNick",
                ),
            ).outbound,
        )
    }

    @Test
    fun `authoritative own-message echo retains outbound routing and origin`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("echo")
            .from(JidCreate.entityFullFrom("account@example.org/device"))
            .to(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(OriginIdElement("origin"))
            .build()

        val envelope = requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org"))

        assertEquals("peer@example.org", envelope.peer)
        assertEquals("account@example.org", envelope.sender)
        assertEquals(true, envelope.outbound)
        assertEquals("origin", envelope.originId)
    }

    @Test
    fun `carbon unwrap requires own wrapper and matching inner direction`() {
        val sentAt = Instant.parse("2026-08-10T10:00:00Z").toEpochMilli()
        val delay = DelayInformation(Date.from(Instant.ofEpochMilli(sentAt)))
        val sent = StanzaBuilder.buildMessage("sent")
            .from(JidCreate.entityFullFrom("account@example.org/device"))
            .to(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("sent")
            .build()
        val received = StanzaBuilder.buildMessage("received")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("received")
            .build()
        fun wrapper(
            from: String,
            direction: CarbonExtension.Direction,
            forwarded: Message,
            forwardedDelay: DelayInformation? = null,
        ) = StanzaBuilder.buildMessage()
            .from(JidCreate.from(from))
            .addExtension(CarbonExtension(direction, Forwarded(forwarded, forwardedDelay)))
            .build()

        assertEquals(
            sent,
            wrapper("account@example.org", CarbonExtension.Direction.sent, sent, delay)
                .toTrustedCarbonMessage("account@example.org")?.message,
        )
        val delayedCarbon = requireNotNull(
            wrapper("account@example.org", CarbonExtension.Direction.sent, sent, delay)
                .toTrustedCarbonMessage("account@example.org"),
        )
        assertEquals(sentAt, delayedCarbon.sentAtEpochMs)
        assertEquals(MessageTimeSource.CARBON, delayedCarbon.sentTimeSource)
        assertEquals(
            received,
            wrapper("account@example.org", CarbonExtension.Direction.received, received)
                .toTrustedCarbonMessage("account@example.org")?.message,
        )
        assertNull(
            wrapper("peer@example.org", CarbonExtension.Direction.sent, sent)
                .toTrustedCarbonMessage("account@example.org"),
        )
        assertNull(
            wrapper("account@example.org/other-device", CarbonExtension.Direction.received, received)
                .toTrustedCarbonMessage("account@example.org"),
        )
        assertNull(
            wrapper("account@example.org", CarbonExtension.Direction.sent, received)
                .toTrustedCarbonMessage("account@example.org"),
        )

        val dual = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom("account@example.org"))
            .addExtension(CarbonExtension(CarbonExtension.Direction.sent, Forwarded(sent)))
            .addExtension(CarbonExtension(CarbonExtension.Direction.received, Forwarded(received)))
            .build()
        assertNull(dual.toTrustedCarbonMessage("account@example.org"))
    }

    @Test
    fun `top level delay survives direct message mapping`() {
        val sentAt = Instant.parse("2026-08-10T09:00:00Z").toEpochMilli()
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("delayed")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("offline")
            .addExtension(DelayInformation(Date.from(Instant.ofEpochMilli(sentAt))))
            .build()

        val envelope = requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org"))

        assertEquals(sentAt, envelope.sentAtEpochMs)
        assertEquals(MessageTimeSource.DELAYED, envelope.sentTimeSource)
    }

    @Test
    fun `future delay stamps are clamped to receipt time without losing provenance`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("future")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("future")
            .addExtension(DelayInformation(Date(2_000L)))
            .build()

        val delayed = requireNotNull(
            message.toIncomingEnvelope(
                attempt,
                "account@example.org",
                receivedAtEpochMs = 1_000L,
            ),
        )
        val mam = requireNotNull(
            message.toIncomingEnvelope(
                attempt,
                "account@example.org",
                suppliedSentAtEpochMs = 3_000L,
                suppliedSentTimeSource = MessageTimeSource.MAM,
                receivedAtEpochMs = 1_000L,
            ),
        )

        assertEquals(1_000L, delayed.sentAtEpochMs)
        assertEquals(MessageTimeSource.DELAYED, delayed.sentTimeSource)
        assertEquals(1_000L, mam.sentAtEpochMs)
        assertEquals(MessageTimeSource.MAM, mam.sentTimeSource)
    }

    @Test
    fun `direct and received carbon errors map only with exact inbound authority`() {
        val direct = StanzaBuilder.buildMessage("operation")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.error)
            .setError(StanzaError.getBuilder(StanzaError.Condition.remote_server_timeout).build())
            .build()
        fun wrapper(
            from: String,
            direction: CarbonExtension.Direction,
            forwarded: Message,
        ) = StanzaBuilder.buildMessage()
            .from(JidCreate.from(from))
            .addExtension(CarbonExtension(direction, Forwarded(forwarded)))
            .build()

        val mappedDirect = direct.classifyOutgoingFailure("account@example.org")
        val mappedCarbon = wrapper(
            "account@example.org",
            CarbonExtension.Direction.received,
            direct,
        ).classifyOutgoingFailure("account@example.org")

        listOf(mappedDirect, mappedCarbon).forEach { decision ->
            assertTrue(decision.consumed)
            assertEquals("operation", decision.failure?.operationId)
            assertEquals("peer@example.org", decision.failure?.peer)
            assertEquals("remote-server-timeout", decision.failure?.reason)
        }
        val sent = wrapper("account@example.org", CarbonExtension.Direction.sent, direct)
            .classifyOutgoingFailure("account@example.org")
        assertTrue(sent.consumed)
        assertNull(sent.failure)
        assertNull(
            wrapper("peer@example.org", CarbonExtension.Direction.received, direct)
                .classifyOutgoingFailure("account@example.org").failure,
        )
        val siblingResource = wrapper(
            "account@example.org/other-device",
            CarbonExtension.Direction.received,
            direct,
        ).classifyOutgoingFailure("account@example.org")
        assertTrue(siblingResource.consumed)
        assertNull(siblingResource.failure)
        val wrongRoute = StanzaBuilder.buildMessage("wrong-route")
                .from(JidCreate.entityBareFrom("peer@example.org"))
                .to(JidCreate.entityBareFrom("other@example.org"))
                .ofType(Message.Type.error)
                .setError(StanzaError.getBuilder(StanzaError.Condition.forbidden).build())
                .build()
                .classifyOutgoingFailure("account@example.org")
        assertTrue(wrongRoute.consumed)
        assertNull(wrongRoute.failure)
    }

    @Test
    fun `stanza IDs require advertised exact own authority`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("stable")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(StanzaIdElement("trusted", "account@example.org"))
            .addExtension(StanzaIdElement("foreign", "archive.example.org"))
            .build()

        val trusted = requireNotNull(
            message.toIncomingEnvelope(attempt, "account@example.org", trustedStableIdAuthority = true),
        )
        val undiscovered = requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org"))

        assertEquals(listOf("trusted"), trusted.stanzaIds.map { it.id })
        assertTrue(undiscovered.stanzaIds.isEmpty())
    }

    @Test
    fun `stable ID discovery gate drains backlog before live input and then opens`() {
        val first = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
        val second = first.copy(
            generation = ConnectionGeneration.require(5),
            attempt = ConnectionAttempt.require(2),
        )
        fun message(id: String) = StanzaBuilder.buildMessage(id)
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(StanzaIdElement("trusted", "account@example.org"))
            .addExtension(StanzaIdElement("foreign", "foreign@example.org"))
            .build()
        val gate = StableIdDiscoveryGate()

        gate.begin(first)
        assertTrue(
            gate.accept(
                first,
                TrustedIncomingStanza(message("A"), 1_234L, MessageTimeSource.CARBON, 2_000L),
            ).isEmpty(),
        )
        assertTrue(gate.accept(first, message("B")).isEmpty())
        val delivered = mutableListOf<String>()
        drainStableIdGate(gate, first, supported = true) { decision ->
            delivered += requireNotNull(decision.message.stanzaId)
            if (decision.message.stanzaId == "A") {
                assertEquals(1_234L, decision.sentAtEpochMs)
                assertEquals(MessageTimeSource.CARBON, decision.sentTimeSource)
                assertEquals(2_000L, decision.receivedAtEpochMs)
                assertTrue(gate.accept(first, message("C")).isEmpty())
            }
        }
        assertEquals(listOf("A", "B", "C"), delivered)

        val direct = gate.accept(first, message("D")).single()
        assertEquals("D", direct.message.stanzaId)
        val envelope = requireNotNull(
            direct.message.toIncomingEnvelope(
                direct.attempt,
                "account@example.org",
                direct.trustStableIds,
            ),
        )
        assertEquals(listOf("trusted"), envelope.stanzaIds.map { it.id })

        gate.begin(second)
        assertTrue(gate.accept(second, message("stale")).isEmpty())
        assertTrue(gate.complete(first, supported = true).isEmpty())
        gate.retire(second)
        assertTrue(gate.complete(second, supported = true).isEmpty())
    }

    @Test
    fun `stable ID drain cancellation retires exact attempt without opening`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage("A")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .build()
        val gate = StableIdDiscoveryGate()
        val cancellation = kotlinx.coroutines.CancellationException("stop")
        gate.begin(attempt)
        gate.accept(attempt, message)

        val observed = runCatching {
            drainStableIdGate(gate, attempt, supported = true) { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, observed)
        assertTrue(gate.accept(attempt, message).isEmpty())
        assertTrue(gate.drain(attempt).isEmpty())
    }

    @Test
    fun `capability discovery failure drains queued and later input conservatively`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
        fun message(id: String) = StanzaBuilder.buildMessage(id)
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("body")
            .addExtension(StanzaIdElement("untrusted", "account@example.org"))
            .build()
        val gate = StableIdDiscoveryGate()
        val failure = IllegalStateException("injected discovery failure")
        val delivered = mutableListOf<StableIdMessageDecision>()
        gate.begin(attempt)
        gate.accept(attempt, message("A"))

        val observed = runCatching<Unit> {
            resolveStableIdGateOnCapabilityFailure(gate, attempt, { delivered += it }) {
                throw failure
            }
        }.exceptionOrNull()
        delivered += gate.accept(attempt, message("B"))

        assertSame(failure, observed)
        assertEquals(listOf("A", "B"), delivered.map { it.message.stanzaId })
        assertTrue(delivered.none(StableIdMessageDecision::trustStableIds))
        assertEquals(false, gate.support(attempt))
    }

    @Test
    fun `MAM normalization retains redacted UID positions and never maps sentinel content`() {
        val sentAt = Instant.parse("2026-08-10T10:00:00Z").toEpochMilli()
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
        fun content(id: String) = StanzaBuilder.buildMessage(id)
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody(id)
            .build()
        fun carrier() = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom("account@example.org"))
            .build()

        repeat(3) { redactedIndex ->
            val results = listOf("first", "middle", "last").mapIndexed { index, id ->
                NemaMamResultExtension(
                    queryId = "query",
                    id = id,
                    actualMessage = content(id).takeUnless { index == redactedIndex },
                    delay = DelayInformation(Date.from(Instant.ofEpochMilli(sentAt + index))),
                )
            }
            val normalized = normalizeMamResults(
                carriers = List(3) { carrier() },
                results = results,
                attempt = attempt,
                expectedArchiveAuthority = "account@example.org",
                trustStableIds = true,
            )

            assertEquals(listOf("first", "middle", "last"), normalized.map { it.resultId })
            assertNull(normalized[redactedIndex].message)
            assertEquals(2, normalized.count { it.message != null })
            normalized.filter { it.message != null }.forEachIndexed { _, archived ->
                assertEquals(MessageTimeSource.MAM, archived.message?.sentTimeSource)
                assertEquals(sentAt + normalized.indexOf(archived), archived.message?.sentAtEpochMs)
            }
            requireMamPageBoundaries("first", "last", normalized)
        }
    }

    @Test
    fun `MAM result carriers require exact archive authority`() {
        fun carrier(source: String?): Message {
            val builder = StanzaBuilder.buildMessage()
            source?.let { builder.from(JidCreate.entityBareFrom(it)) }
            return builder.build()
        }
        val trusted = carrier("account@example.org")

        assertTrue(listOf(trusted).haveArchiveAuthority("account@example.org"))
        assertFalse(
            listOf(trusted, carrier("foreign@example.org"))
                .haveArchiveAuthority("account@example.org"),
        )
        assertFalse(listOf(carrier(null)).haveArchiveAuthority("account@example.org"))
    }

    @Test
    fun `MAM paging keeps earlier state when server omits index and caps page`() {
        assertTrue(
            archiveHasEarlier(
                direction = ArchivePageDirection.BOOTSTRAP,
                complete = false,
                messageCount = 20,
                firstIndex = -1,
            ),
        )
        assertTrue(
            archiveHasEarlier(
                direction = ArchivePageDirection.BEFORE,
                complete = false,
                messageCount = 20,
                firstIndex = -1,
            ),
        )
        assertFalse(
            archiveHasEarlier(
                direction = ArchivePageDirection.BEFORE,
                complete = true,
                messageCount = 0,
                firstIndex = -1,
            ),
        )
        assertFalse(
            archiveHasEarlier(
                direction = ArchivePageDirection.BEFORE,
                complete = false,
                messageCount = 20,
                firstIndex = 0,
            ),
        )
    }
}
