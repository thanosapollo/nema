package org.thanosapollo.nema.xmpp.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

class XmppTransportTest {
    private val accountId = AccountId.require("account-1")
    private val generation = ConnectionGeneration.require(7)

    @Test
    fun `connection callbacks retain account generation and state`() {
        val envelope = ConnectionEnvelope(
            accountId = accountId,
            generation = generation,
            state = TransportConnectionState.CONNECTED,
        )
        val transport = FakeTransport(envelope)

        assertSame(envelope, transport.connectionStates.value)
    }

    @Test
    fun `message callbacks retain immutable transport metadata`() = runBlocking {
        val thread = ThreadRef(
            id = ThreadId.require("child-thread"),
            parentId = ThreadId.require("parent-thread"),
        )
        val envelope = IncomingMessageEnvelope(
            accountId = accountId,
            generation = generation,
            peer = "sender",
            sender = "sender",
            outbound = false,
            originId = null,
            body = "message-body",
            thread = thread,
        )
        val transport = FakeTransport(
            ConnectionEnvelope(accountId, generation, TransportConnectionState.CONNECTED),
        )

        val received = async(start = CoroutineStart.UNDISPATCHED) {
            transport.incomingMessages.first()
        }
        transport.recordIncoming(envelope)

        assertEquals(envelope, received.await())
    }

    @Test
    fun `transport exposes Flow callbacks and suspend commands without Smack types`() {
        val transport: XmppTransport = FakeTransport(
            ConnectionEnvelope(accountId, generation, TransportConnectionState.DISCONNECTED),
        )

        val states: StateFlow<ConnectionEnvelope> = transport.connectionStates
        val messages: Flow<IncomingMessageEnvelope> = transport.incomingMessages

        assertEquals(TransportConnectionState.DISCONNECTED, states.value.state)
        assertSame(messages, transport.incomingMessages)
    }

    private class FakeTransport(initial: ConnectionEnvelope) : XmppTransport {
        override val connectionStates = MutableStateFlow(initial)
        private val mutableIncoming = MutableSharedFlow<IncomingMessageEnvelope>()
        override val incomingMessages: Flow<IncomingMessageEnvelope> = mutableIncoming

        override suspend fun send(message: OutgoingMessageEnvelope) = Unit

        override suspend fun close() = Unit

        suspend fun recordIncoming(message: IncomingMessageEnvelope) {
            mutableIncoming.emit(message)
        }
    }
}
