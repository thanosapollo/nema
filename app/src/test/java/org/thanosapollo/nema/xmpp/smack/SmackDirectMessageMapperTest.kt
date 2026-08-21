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
import org.jivesoftware.smackx.message_correct.element.MessageCorrectExtension
import org.jivesoftware.smackx.receipts.DeliveryReceipt
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
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
import org.thanosapollo.nema.xmpp.markers.installNemaChatMarkerProviders
import org.thanosapollo.nema.xmpp.chatstates.ChatActivity
import org.thanosapollo.nema.xmpp.chatstates.CHAT_STATES_NAMESPACE
import org.thanosapollo.nema.xmpp.chatstates.installNemaChatStateProviders
import org.thanosapollo.nema.xmpp.reactions.REACTIONS_NAMESPACE
import org.thanosapollo.nema.xmpp.reactions.installNemaReactionProviders
import org.thanosapollo.nema.xmpp.rtt.RTT_NAMESPACE
import org.thanosapollo.nema.xmpp.rtt.applyRttActions
import org.thanosapollo.nema.xmpp.rtt.installNemaRttProviders
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
import org.thanosapollo.nema.xmpp.transport.OutgoingChatState
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage
import org.thanosapollo.nema.xmpp.transport.MessageSignalProtocol
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmackDirectMessageMapperTest {
    @Before
    fun initializeSmack() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaReplyProviders()
        installNemaChatMarkerProviders()
        installNemaChatStateProviders()
        installNemaReactionProviders()
        installNemaRttProviders()
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
        assertTrue(DeliveryReceiptRequest.from(message) != null)
        assertTrue(message.getExtensionElement("markable", "urn:xmpp:chat-markers:0") != null)
        assertEquals(
            "active",
            message.getExtensionElement("active", CHAT_STATES_NAMESPACE)?.elementName,
        )
    }

    @Test
    fun bodylessOutboundChatStateIsTypeChat() {
        val composing = OutgoingChatState(
            accountId = AccountId.require("account"),
            generation = ConnectionGeneration.require(3),
            recipient = "peer@example.org",
            activity = ChatActivity.COMPOSING,
        ).toSmackMessage()
        assertEquals(Message.Type.chat, composing.type)
        assertNull(composing.body)
        assertEquals("composing", composing.getExtensionElement("composing", CHAT_STATES_NAMESPACE)?.elementName)
        assertNull(composing.getExtensionElement("paused", CHAT_STATES_NAMESPACE))
    }

    @Test
    fun `direct correction retains one typed replacement reference on send and receive`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val outgoing = OutgoingMessageEnvelope(
            accountId = attempt.accountId,
            generation = attempt.generation,
            attempt = 1,
            operationId = "correction-operation",
            originId = "correction-origin",
            recipient = "peer@example.org",
            body = "corrected body",
            thread = null,
            replaceId = "original-wire-id",
        ).toSmackMessage()
        val incoming = StanzaBuilder.buildMessage("correction-message")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("peer corrected body")
            .addExtension(MessageCorrectExtension("peer-original-id"))
            .build()

        assertEquals(
            "original-wire-id",
            MessageCorrectExtension.from(outgoing).idInitialMessage,
        )
        assertEquals(
            "peer-original-id",
            requireNotNull(incoming.toIncomingEnvelope(attempt, "account@example.org")).replaceId,
        )
    }

    @Test
    fun `correction authority requires one typed direct replacement`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun message(
            type: Message.Type,
            vararg extensions: org.jivesoftware.smack.packet.ExtensionElement,
        ): Message {
            val builder = StanzaBuilder.buildMessage()
                .from(JidCreate.entityFullFrom("peer@example.org/device"))
                .to(JidCreate.entityFullFrom("account@example.org/device"))
                .ofType(type)
                .setBody("body")
            extensions.forEach(builder::addExtension)
            return builder.build()
        }
        val duplicate = message(
            Message.Type.chat,
            MessageCorrectExtension("original"),
            MessageCorrectExtension("other"),
        )
        val spoofed = message(
            Message.Type.chat,
            StandardExtensionElement.builder("replace", MessageCorrectExtension.NAMESPACE)
                .addAttribute("id", "original")
                .build(),
        )
        val mixedDuplicate = message(
            Message.Type.chat,
            MessageCorrectExtension("original"),
            StandardExtensionElement.builder("replace", MessageCorrectExtension.NAMESPACE)
                .addAttribute("id", "other")
                .build(),
        )
        val groupChat = message(Message.Type.groupchat, MessageCorrectExtension("original"))
        val normal = message(Message.Type.normal, MessageCorrectExtension("original"))

        assertNull(requireNotNull(duplicate.toIncomingEnvelope(attempt, "account@example.org")).replaceId)
        assertNull(requireNotNull(spoofed.toIncomingEnvelope(attempt, "account@example.org")).replaceId)
        assertNull(requireNotNull(mixedDuplicate.toIncomingEnvelope(attempt, "account@example.org")).replaceId)
        assertNull(requireNotNull(groupChat.toIncomingEnvelope(attempt, "account@example.org")).replaceId)
        assertNull(requireNotNull(normal.toIncomingEnvelope(attempt, "account@example.org")).replaceId)
    }

    @Test
    fun `bodyless direct receipts and markers map without creating timeline content`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun control(extension: org.jivesoftware.smack.packet.ExtensionElement) =
            StanzaBuilder.buildMessage()
                .from(JidCreate.entityFullFrom("peer@example.org/device"))
                .to(JidCreate.entityFullFrom("account@example.org/device"))
                .ofType(Message.Type.chat)
                .addExtension(extension)
                .build()
        val received = control(DeliveryReceipt("sent-operation"))
        val displayed = control(
            StandardExtensionElement.builder("displayed", "urn:xmpp:chat-markers:0")
                .addAttribute("id", "sent-operation")
                .build(),
        )

        val receiptSignal = requireNotNull(received.toIncomingSignal(attempt, "account@example.org"))
        val displaySignal = requireNotNull(displayed.toIncomingSignal(attempt, "account@example.org"))

        assertEquals("peer@example.org", receiptSignal.peer)
        assertEquals("peer@example.org", receiptSignal.sender)
        assertEquals("sent-operation", receiptSignal.targetId)
        assertEquals("RECEIVED", receiptSignal.stage.name)
        assertEquals("DISPLAYED", displaySignal.stage.name)
        assertEquals(MessageSignalProtocol.DELIVERY_RECEIPT, receiptSignal.protocol)
        assertEquals(MessageSignalProtocol.CHAT_MARKER, displaySignal.protocol)
        assertNull(received.toIncomingEnvelope(attempt, "account@example.org"))
        assertNull(displayed.toIncomingEnvelope(attempt, "account@example.org"))
    }

    @Test
    fun `receipt controls with message content remain ordinary messages`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val message = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("must survive")
            .addExtension(DeliveryReceipt("sent-operation"))
            .build()

        assertNull(message.toIncomingSignal(attempt, "account@example.org"))
        assertEquals(
            "must survive",
            requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org")).body,
        )
    }

    @Test
    fun `incoming direct request metadata and outgoing acknowledgements map exactly`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val incoming = StanzaBuilder.buildMessage("peer-message")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("hello")
            .addExtension(DeliveryReceiptRequest())
            .addExtension(
                StandardExtensionElement.builder("markable", "urn:xmpp:chat-markers:0").build(),
            )
            .build()

        val envelope = requireNotNull(incoming.toIncomingEnvelope(attempt, "account@example.org"))
        assertTrue(envelope.receiptRequested)
        assertTrue(envelope.markable)
        assertEquals("peer-message", envelope.messageId)

        val receipt = OutgoingMessageSignal(
            accountId = attempt.accountId,
            generation = attempt.generation,
            recipient = envelope.peer,
            targetId = requireNotNull(envelope.messageId),
            stage = MessageReceiptStage.RECEIVED,
            protocol = MessageSignalProtocol.DELIVERY_RECEIPT,
        ).toSmackMessage()
        assertEquals("peer-message", DeliveryReceipt.from(receipt)?.id)
        assertEquals(Message.Type.chat, receipt.type)
        assertEquals("peer@example.org", receipt.to.toString())
    }

    @Test
    fun `request metadata requires typed direct chat extensions`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun message(type: Message.Type, request: org.jivesoftware.smack.packet.ExtensionElement) =
            StanzaBuilder.buildMessage("peer-message")
                .from(JidCreate.entityBareFrom("peer@example.org"))
                .to(JidCreate.entityBareFrom("account@example.org"))
                .ofType(type)
                .setBody("hello")
                .addExtension(request)
                .addExtension(
                    StandardExtensionElement.builder("markable", "urn:xmpp:chat-markers:0").build(),
                )
                .build()

        val normal = requireNotNull(
            message(Message.Type.normal, DeliveryReceiptRequest())
                .toIncomingEnvelope(attempt, "account@example.org"),
        )
        val untyped = requireNotNull(
            message(
                Message.Type.chat,
                StandardExtensionElement.builder("request", "urn:xmpp:receipts").build(),
            ).toIncomingEnvelope(attempt, "account@example.org"),
        )

        assertEquals(false, normal.receiptRequested)
        assertEquals(false, normal.markable)
        assertEquals(false, untyped.receiptRequested)
        assertTrue(untyped.markable)
    }

    @Test
    fun `ambiguous or non-direct controls fail closed`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val duplicate = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom("peer@example.org"))
            .to(JidCreate.entityBareFrom("account@example.org"))
            .ofType(Message.Type.chat)
            .addExtension(DeliveryReceipt("one"))
            .addExtension(
                StandardExtensionElement.builder("displayed", "urn:xmpp:chat-markers:0")
                    .addAttribute("id", "one")
                    .build(),
            )
            .build()
        val room = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("room@conference.example.org/nick"))
            .ofType(Message.Type.groupchat)
            .addExtension(DeliveryReceipt("one"))
            .build()
        val empty = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom("peer@example.org"))
            .to(JidCreate.entityBareFrom("account@example.org"))
            .ofType(Message.Type.chat)
            .addExtension(DeliveryReceipt(""))
            .build()

        assertNull(duplicate.toIncomingSignal(attempt, "account@example.org"))
        assertNull(room.toIncomingSignal(attempt, "account@example.org"))
        assertNull(empty.toIncomingSignal(attempt, "account@example.org"))
    }

    @Test
    fun `own displayed marker maps to the peer conversation and ignores own receipts`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun own(extension: org.jivesoftware.smack.packet.ExtensionElement) =
            StanzaBuilder.buildMessage()
                .from(JidCreate.entityFullFrom("account@example.org/other"))
                .to(JidCreate.entityFullFrom("peer@example.org/device"))
                .ofType(Message.Type.chat)
                .addExtension(extension)
                .build()
        val displayed = own(
            StandardExtensionElement.builder("displayed", "urn:xmpp:chat-markers:0")
                .addAttribute("id", "inbound-wire")
                .build(),
        )
        val received = own(DeliveryReceipt("inbound-wire"))

        val signal = requireNotNull(displayed.toIncomingSignal(attempt, "account@example.org"))
        assertEquals("peer@example.org", signal.peer)
        assertEquals("account@example.org", signal.sender)
        assertEquals("inbound-wire", signal.targetId)
        assertEquals(MessageReceiptStage.DISPLAYED, signal.stage)
        assertEquals(MessageSignalProtocol.CHAT_MARKER, signal.protocol)
        assertNull(displayed.toIncomingEnvelope(attempt, "account@example.org"))
        assertNull(received.toIncomingSignal(attempt, "account@example.org"))
    }

    @Test
    fun `outgoing public groupchat omits direct receipt and marker requests`() {
        val envelope = OutgoingMessageEnvelope(
            accountId = AccountId.require("account"),
            generation = ConnectionGeneration.require(3),
            attempt = 1,
            operationId = "operation",
            originId = "origin",
            recipient = "room@conference.example.org",
            body = "hello room",
            thread = null,
            kind = org.thanosapollo.nema.thread.MessageKind.GROUPCHAT,
        )

        val message = envelope.toSmackMessage()

        assertNull(DeliveryReceiptRequest.from(message))
        assertNull(message.getExtensionElement("markable", "urn:xmpp:chat-markers:0"))
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
    fun `encrypted fallback requires exact user content`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun parse(extension: String) = PacketParserUtils.parseStanza(
            """<message xmlns='jabber:client' from='peer@example.org/device' type='chat'>$extension</message>""",
        ) as Message

        listOf("eu.siacs.conversations.axolotl", "urn:xmpp:omemo:2").forEach { namespace ->
            val control = parse("<encrypted xmlns='$namespace'><header sid='1'/></encrypted>")
            val content = parse(
                "<encrypted xmlns='$namespace'><header sid='1'/><payload>AA==</payload></encrypted>",
            )
            assertNull(control.toIncomingEnvelope(attempt, "account@example.org"))
            assertEquals(
                "Encrypted message",
                requireNotNull(content.toIncomingEnvelope(attempt, "account@example.org")).body,
            )
        }

        val wrongElement = parse(
            "<devices xmlns='urn:xmpp:omemo:2'><payload>AA==</payload></devices>",
        )
        val emptyXep27 = parse("<x xmlns='jabber:x:encrypted'/>")
        val contentXep27 = parse("<x xmlns='jabber:x:encrypted'>ciphertext</x>")
        assertNull(wrongElement.toIncomingEnvelope(attempt, "account@example.org"))
        assertNull(emptyXep27.toIncomingEnvelope(attempt, "account@example.org"))
        assertEquals(
            "Encrypted message",
            requireNotNull(contentXep27.toIncomingEnvelope(attempt, "account@example.org")).body,
        )
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
    fun `Nema advertises semantic reply and correction support`() {
        val connection = XMPPTCPConnection(
            XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(JidCreate.domainBareFrom("example.org"))
                .setUsernameAndPassword("account", null)
                .build(),
        )

        advertiseNemaFeatures(connection)

        assertTrue(ServiceDiscoveryManager.getInstanceFor(connection).includesFeature(REPLY_NAMESPACE))
        assertTrue(
            ServiceDiscoveryManager.getInstanceFor(connection)
                .includesFeature(MessageCorrectExtension.NAMESPACE),
        )
        assertTrue(ServiceDiscoveryManager.getInstanceFor(connection).includesFeature(REACTIONS_NAMESPACE))
        assertTrue(ServiceDiscoveryManager.getInstanceFor(connection).includesFeature(RTT_NAMESPACE))
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
            .addExtension(MessageCorrectExtension("received-original"))
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
        val receivedCarbon = requireNotNull(
            wrapper("account@example.org", CarbonExtension.Direction.received, received)
                .toTrustedCarbonMessage("account@example.org"),
        )
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        assertEquals(
            "received-original",
            requireNotNull(
                receivedCarbon.message.toIncomingEnvelope(
                    attempt,
                    "account@example.org",
                    suppliedSentAtEpochMs = receivedCarbon.sentAtEpochMs,
                    suppliedSentTimeSource = receivedCarbon.sentTimeSource,
                ),
            ).replaceId,
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
        val firstLive = gate.accept(
            first,
            TrustedIncomingStanza(message("A"), 1_234L, MessageTimeSource.CARBON, 2_000L),
        ).single()
        assertEquals("A", firstLive.message.stanzaId)
        assertEquals(false, firstLive.trustStableIds)
        assertEquals(1_234L, firstLive.sentAtEpochMs)
        assertEquals(MessageTimeSource.CARBON, firstLive.sentTimeSource)
        assertEquals("B", gate.accept(first, message("B")).single().message.stanzaId)
        drainStableIdGate(gate, first, supported = true) { }
        val direct = gate.accept(first, message("D")).single()
        assertEquals("D", direct.message.stanzaId)
        assertEquals(true, direct.trustStableIds)
        val envelope = requireNotNull(
            direct.message.toIncomingEnvelope(
                direct.attempt,
                "account@example.org",
                direct.trustStableIds,
            ),
        )
        assertEquals(listOf("trusted"), envelope.stanzaIds.map { it.id })

        gate.begin(second)
        assertEquals("stale", gate.accept(second, message("stale")).single().message.stanzaId)
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
        gate.begin(attempt)
        assertEquals("A", gate.accept(attempt, message).single().message.stanzaId)
        gate.retire(attempt)
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
        assertEquals("A", gate.accept(attempt, message("A")).single().message.stanzaId)

        val observed = runCatching<Unit> {
            resolveStableIdGateOnCapabilityFailure(gate, attempt, { delivered += it }) {
                throw failure
            }
        }.exceptionOrNull()
        delivered += gate.accept(attempt, message("B"))

        assertSame(failure, observed)
        assertEquals(listOf("B"), delivered.map { it.message.stanzaId })
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
    fun `MAM bodyless marker remains a control result and not timeline content`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val control = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .addExtension(
                StandardExtensionElement.builder("displayed", "urn:xmpp:chat-markers:0")
                    .addAttribute("id", "sent-operation")
                    .build(),
            )
            .build()
        val carrier = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom("account@example.org"))
            .build()
        val result = NemaMamResultExtension(
            queryId = "query",
            id = "marker-result",
            actualMessage = control,
            delay = DelayInformation(Date.from(Instant.ofEpochMilli(1_000))),
        )

        val archived = normalizeMamResults(
            carriers = listOf(carrier),
            results = listOf(result),
            attempt = attempt,
            expectedArchiveAuthority = "account@example.org",
            trustStableIds = true,
        ).single()

        assertNull(archived.message)
        assertEquals(MessageReceiptStage.DISPLAYED, archived.signal?.stage)
        assertEquals("sent-operation", archived.signal?.targetId)
        requireMamPageBoundaries("marker-result", "marker-result", listOf(archived))
    }

    @Test
    fun `account MAM preserves direct correction metadata and trusted time`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val correction = StanzaBuilder.buildMessage("correction")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("corrected body")
            .addExtension(MessageCorrectExtension("original-wire-id"))
            .build()
        val archived = normalizeMamResults(
            carriers = listOf(
                StanzaBuilder.buildMessage()
                    .from(JidCreate.entityBareFrom("account@example.org"))
                    .build(),
            ),
            results = listOf(
                NemaMamResultExtension(
                    queryId = "query",
                    id = "correction-result",
                    actualMessage = correction,
                    delay = DelayInformation(Date.from(Instant.ofEpochMilli(2_000))),
                ),
            ),
            attempt = attempt,
            expectedArchiveAuthority = "account@example.org",
            trustStableIds = true,
        ).single()

        assertEquals("original-wire-id", archived.message?.replaceId)
        assertEquals("corrected body", archived.message?.body)
        assertEquals(2_000L, archived.message?.sentAtEpochMs)
        assertEquals(MessageTimeSource.MAM, archived.message?.sentTimeSource)
        assertNull(archived.signal)
    }

    @Test
    fun `room MAM cannot grant direct correction authority`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val correction = StanzaBuilder.buildMessage("forged-correction")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .setBody("forged corrected body")
            .addExtension(MessageCorrectExtension("direct-target"))
            .build()
        val archived = normalizeMamResults(
            carriers = listOf(
                StanzaBuilder.buildMessage()
                    .from(JidCreate.entityBareFrom("room@conference.example.org"))
                    .build(),
            ),
            results = listOf(
                NemaMamResultExtension(
                    queryId = "query",
                    id = "room-correction-result",
                    actualMessage = correction,
                    delay = DelayInformation(Date.from(Instant.ofEpochMilli(1_000))),
                ),
            ),
            attempt = attempt,
            expectedArchiveAuthority = "room@conference.example.org",
            mappingBareJid = "account@example.org",
            trustStableIds = false,
        ).single()

        assertNull(archived.message)
        assertNull(archived.signal)
    }

    @Test
    fun `room MAM retains groupchat content under room authority`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val roomMessage = StanzaBuilder.buildMessage("room-message")
            .from(JidCreate.entityFullFrom("room@conference.example.org/alice"))
            .ofType(Message.Type.groupchat)
            .setBody("room body")
            .build()
        val archived = normalizeMamResults(
            carriers = listOf(
                StanzaBuilder.buildMessage()
                    .from(JidCreate.entityBareFrom("room@conference.example.org"))
                    .build(),
            ),
            results = listOf(
                NemaMamResultExtension(
                    queryId = "query",
                    id = "room-message-result",
                    actualMessage = roomMessage,
                    delay = DelayInformation(Date.from(Instant.ofEpochMilli(1_000))),
                ),
            ),
            attempt = attempt,
            expectedArchiveAuthority = "room@conference.example.org",
            mappingBareJid = "account@example.org",
            trustStableIds = false,
        ).single()

        assertEquals(org.thanosapollo.nema.thread.MessageKind.GROUPCHAT, archived.message?.kind)
        assertEquals("room body", archived.message?.body)
        assertNull(archived.signal)
    }

    @Test
    fun `room MAM bodyless direct marker remains cursor-only`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val control = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.chat)
            .addExtension(
                StandardExtensionElement.builder("displayed", "urn:xmpp:chat-markers:0")
                    .addAttribute("id", "direct-operation")
                    .build(),
            )
            .build()
        val result = NemaMamResultExtension(
            queryId = "query",
            id = "room-control-result",
            actualMessage = control,
            delay = DelayInformation(Date.from(Instant.ofEpochMilli(1_000))),
        )
        val archived = normalizeMamResults(
            carriers = listOf(
                StanzaBuilder.buildMessage()
                    .from(JidCreate.entityBareFrom("room@conference.example.org"))
                    .build(),
            ),
            results = listOf(result),
            attempt = attempt,
            expectedArchiveAuthority = "room@conference.example.org",
            mappingBareJid = "account@example.org",
            trustStableIds = false,
        ).single()

        assertNull(archived.signal)
        assertNull(archived.message)
        requireMamPageBoundaries("room-control-result", "room-control-result", listOf(archived))
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

    @Test
    fun `live composing maps and own states are dropped`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val composing = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("peer@example.org/phone"))
            .to(JidCreate.entityBareFrom("account@example.org"))
            .ofType(Message.Type.chat)
            .addExtension(
                StandardExtensionElement.builder("composing", CHAT_STATES_NAMESPACE).build(),
            )
            .build()
        val own = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom("account@example.org"))
            .to(JidCreate.entityBareFrom("peer@example.org"))
            .ofType(Message.Type.chat)
            .addExtension(
                StandardExtensionElement.builder("composing", CHAT_STATES_NAMESPACE).build(),
            )
            .build()
        val room = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("room@conference.example.org/debacle"))
            .ofType(Message.Type.groupchat)
            .addExtension(
                StandardExtensionElement.builder("composing", CHAT_STATES_NAMESPACE).build(),
            )
            .build()
        val selfNick = StanzaBuilder.buildMessage()
            .from(JidCreate.entityFullFrom("room@conference.example.org/me"))
            .ofType(Message.Type.groupchat)
            .addExtension(
                StandardExtensionElement.builder("composing", CHAT_STATES_NAMESPACE).build(),
            )
            .build()

        val direct = requireNotNull(composing.toIncomingChatState(attempt, "account@example.org"))
        assertEquals("peer@example.org", direct.peer)
        assertEquals(ChatActivity.COMPOSING, direct.activity)
        assertFalse(direct.groupChat)
        assertNull(own.toIncomingChatState(attempt, "account@example.org"))
        val muc = requireNotNull(room.toIncomingChatState(attempt, "account@example.org"))
        assertEquals("room@conference.example.org", muc.peer)
        assertEquals("debacle", muc.actor)
        assertTrue(muc.groupChat)
        assertNull(selfNick.toIncomingChatState(attempt, "account@example.org", ownRoomNick = "me"))
    }

    @Test
    fun `wire composing parses after smack native provider`() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        val xml = """
            <message xmlns='jabber:client'
                     from='talos@chat.example.org/bot'
                     to='account@example.org/nema'
                     type='chat'>
              <composing xmlns='http://jabber.org/protocol/chatstates'/>
            </message>
        """.trimIndent()
        val message = PacketParserUtils.parseStanza(xml) as Message
        val mapped = requireNotNull(message.toIncomingChatState(attempt, "account@example.org"))
        assertEquals("talos@chat.example.org", mapped.peer)
        assertEquals(ChatActivity.COMPOSING, mapped.activity)
        assertFalse(mapped.groupChat)
    }

    @Test
    fun liveAndOwnCarbonReactionsMapAndGroupchatIsDropped() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun reaction(from: String, to: String) = StanzaBuilder.buildMessage()
            .from(JidCreate.from(from))
            .to(JidCreate.from(to))
            .ofType(Message.Type.chat)
            .addExtension(
                StandardExtensionElement.builder("reactions", REACTIONS_NAMESPACE)
                    .addAttribute("id", "origin-1")
                    .addElement("reaction", "👍")
                    .build(),
            )
            .build()
        val inbound = requireNotNull(
            reaction("peer@example.org/phone", "account@example.org/nema")
                .toIncomingReaction(attempt, "account@example.org"),
        )
        assertEquals("peer@example.org", inbound.peer)
        assertEquals("peer@example.org", inbound.senderBareJid)
        assertEquals("origin-1", inbound.targetId)
        assertEquals(listOf("👍"), inbound.emojis)
        val own = requireNotNull(
            reaction("account@example.org/nema", "peer@example.org/phone")
                .toIncomingReaction(attempt, "account@example.org"),
        )
        assertEquals("peer@example.org", own.peer)
        assertEquals("account@example.org", own.senderBareJid)
        assertNull(
            StanzaBuilder.buildMessage()
                .from(JidCreate.from("room@conference.example.org/nick"))
                .ofType(Message.Type.groupchat)
                .addExtension(
                    StandardExtensionElement.builder("reactions", REACTIONS_NAMESPACE)
                        .addAttribute("id", "origin-1")
                        .addElement("reaction", "👍")
                        .build(),
                )
                .build()
                .toIncomingReaction(attempt, "account@example.org"),
        )
    }

    @Test
    fun liveRttPreservesActionOrderAndDropsOwnOrGroupchat() {
        val attempt = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(4),
            ConnectionAttempt.require(2),
            LifecycleEpoch.require(1),
        )
        fun parse(from: String, type: String, inner: String) = (
            PacketParserUtils.parseStanza(
                """<message xmlns='jabber:client' from='$from' to='account@example.org/nema' type='$type'>$inner</message>""",
            ) as Message
        ).toIncomingRtt(attempt, "account@example.org")
        fun text(from: String, inner: String, start: String = "") =
            applyRttActions(start, requireNotNull(parse(from, "chat", inner)?.element).actions)
        assertEquals(
            "Hello there, World",
            text(
                "talos@chat.example.org/bot",
                """<rtt xmlns='urn:xmpp:rtt:0' seq='3' event='new'><t>Helo</t><e/><t>lo...planet</t><e n='6'/><t> World</t><e n='3' p='8'/><t p='5'> there,</t></rtt>""",
            ),
        )
        assertEquals(
            "ac",
            text("talos@chat.example.org/bot", """<rtt xmlns='urn:xmpp:rtt:0' seq='1' event='new'><t>ab</t><e/><t>c</t></rtt>"""),
        )
        assertEquals(
            "hello ",
            text("talos@chat.example.org/bot", """<rtt xmlns='urn:xmpp:rtt:0' seq='4'><t> </t></rtt>""", "hello"),
        )
        assertNull(parse("account@example.org/nema", "chat", """<rtt xmlns='urn:xmpp:rtt:0' seq='1' event='new'><t>x</t></rtt>"""))
        assertNull(parse("room@conference.example.org/nick", "groupchat", """<rtt xmlns='urn:xmpp:rtt:0' seq='1' event='new'><t>x</t></rtt>"""))
    }
}
