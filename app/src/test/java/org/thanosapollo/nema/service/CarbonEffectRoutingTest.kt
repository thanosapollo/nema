package org.thanosapollo.nema.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.jxmpp.jid.impl.JidCreate
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.chatstates.ChatActivity
import org.thanosapollo.nema.xmpp.chatstates.ChatStateHub
import org.thanosapollo.nema.xmpp.smack.CarbonCarrier
import org.thanosapollo.nema.xmpp.smack.MessageCarrier
import org.thanosapollo.nema.xmpp.smack.bodylessCarbonEffect
import org.thanosapollo.nema.xmpp.smack.classifyOutgoingFailure
import org.thanosapollo.nema.xmpp.smack.toIncomingChatState
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class CarbonEffectRoutingTest {
    @Test
    fun `sent and received bodyless gone clear only their conversation`() = runBlocking {
        val hub = ChatStateHub()
        hub.apply(state(PEER, ChatActivity.COMPOSING))
        hub.apply(state(OTHER, ChatActivity.COMPOSING))

        val sentGone = carrier(CarbonCarrier.Direction.SENT, OWN, PEER)
            .toIncomingChatState(ATTEMPT, OWN)
        val receivedGone = carrier(CarbonCarrier.Direction.RECEIVED, PEER, OWN)
            .toIncomingChatState(ATTEMPT, OWN)

        hub.apply(requireNotNull(sentGone))
        assertEquals(emptyList<String>(), hub.observe(ATTEMPT.accountId.value, PEER).first())
        assertEquals(listOf(OTHER), hub.observe(ATTEMPT.accountId.value, OTHER).first())
        hub.apply(requireNotNull(receivedGone))
        assertEquals(emptyList<String>(), hub.observe(ATTEMPT.accountId.value, PEER).first())
    }

    @Test
    fun `sent Carbon errors and uncorrelated received errors are inert`() {
        val sent = errorCarrier(CarbonCarrier.Direction.SENT, "operation")
        val uncorrelated = errorCarrier(CarbonCarrier.Direction.RECEIVED, null)
        val received = errorCarrier(CarbonCarrier.Direction.RECEIVED, "operation")

        assertNull(sent.classifyOutgoingFailure(OWN).failure)
        assertNull(uncorrelated.classifyOutgoingFailure(OWN).failure)
        assertEquals("operation", received.classifyOutgoingFailure(OWN).failure?.operationId)
    }

    @Test
    fun `body-bearing own-sender chat state is not a Carbon effect`() {
        val message = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(OWN))
            .to(JidCreate.entityBareFrom(PEER))
            .ofType(Message.Type.chat)
            .setBody("content")
            .addExtension(StandardExtensionElement.builder("gone", CHAT_STATES).build())
            .build()

        assertNull(
            MessageCarrier.Carbon(CarbonCarrier.Direction.SENT, message, null)
                .toIncomingChatState(ATTEMPT, OWN),
        )
        assertNull(MessageCarrier.Carbon(CarbonCarrier.Direction.SENT, message, null).bodylessCarbonEffect())
    }

    @Test
    fun `competing bodyless controls are inert`() {
        val message = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(OWN))
            .to(JidCreate.entityBareFrom(PEER))
            .ofType(Message.Type.chat)
            .addExtension(StandardExtensionElement.builder("gone", CHAT_STATES).build())
            .addExtension(StandardExtensionElement.builder("received", "urn:xmpp:receipts").build())
            .build()

        assertNull(MessageCarrier.Carbon(CarbonCarrier.Direction.SENT, message, null).bodylessCarbonEffect())
    }

    private fun carrier(direction: CarbonCarrier.Direction, from: String, to: String) =
        MessageCarrier.Carbon(direction, message(from, to), null)

    private fun message(from: String, to: String) = StanzaBuilder.buildMessage()
        .from(JidCreate.entityBareFrom(from))
        .to(JidCreate.entityBareFrom(to))
        .ofType(Message.Type.chat)
        .addExtension(StandardExtensionElement.builder("gone", CHAT_STATES).build())
        .build()

    private fun errorCarrier(direction: CarbonCarrier.Direction, id: String?): MessageCarrier.Carbon {
        val builder = (if (id == null) StanzaBuilder.buildMessage() else StanzaBuilder.buildMessage(id))
            .from(JidCreate.entityFullFrom("$PEER/device"))
            .to(JidCreate.entityFullFrom("$OWN/device"))
            .ofType(Message.Type.error)
            .setError(StanzaError.getBuilder(StanzaError.Condition.remote_server_timeout).build())
        return MessageCarrier.Carbon(direction, builder.build(), null)
    }

    private fun state(peer: String, activity: ChatActivity) =
        org.thanosapollo.nema.xmpp.transport.IncomingChatState(
            ATTEMPT.accountId,
            ATTEMPT.generation,
            peer,
            peer,
            false,
            activity,
        )

    companion object {
        private const val OWN = "account@example.org"
        private const val PEER = "peer@example.org"
        private const val OTHER = "other@example.org"
        private const val CHAT_STATES = "http://jabber.org/protocol/chatstates"
        private val ATTEMPT = SessionAttemptIdentity(
            AccountId.require("account"),
            ConnectionGeneration.require(1),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
    }
}
