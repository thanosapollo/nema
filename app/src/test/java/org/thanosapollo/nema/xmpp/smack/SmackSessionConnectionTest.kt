package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.blocking.BlockingCommandManager
import org.jivesoftware.smackx.blocking.element.BlockContactsIQ
import org.jivesoftware.smackx.blocking.element.BlockListIQ
import org.jivesoftware.smackx.blocking.element.UnblockContactsIQ
import org.jivesoftware.smackx.carbons.packet.Carbon
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.disco.packet.DiscoverInfo
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionFailureReason
import org.thanosapollo.nema.session.SessionEvent
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.xmpp.chatstates.ChatActivity
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException
import org.jxmpp.jid.impl.JidCreate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmackSessionConnectionTest {
    @Test
    fun `group send enters only with current membership while direct is independent`() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val transport = RecordingXmppConnection()
        val session = session(transport)
        val first = attempt(1)
        session.updateAttempt(first)
        val registry = session.privateField("roomStableIdAuthorities") as RoomStableIdAuthorityRegistry
        val room = "room@conference.example.org"
        val group = org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope(
            ACCOUNT_ID, first.generation, 1, "operation", "origin", room, "body", null,
            kind = org.thanosapollo.nema.thread.MessageKind.GROUPCHAT,
        )
        var entries = 0
        suspend fun rejected(message: org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope) {
            val before = entries
            val sent = transport.events.toList()
            assertTrue(runCatching { session.send(message) { entries++ } }.exceptionOrNull() is SendNotAttemptedException)
            assertEquals(before, entries)
            assertEquals(sent, transport.events.toList())
        }
        rejected(group)
        val joining = requireNotNull(registry.beginJoin(first, room))
        rejected(group) // A join in flight is not membership.
        session.send(group.copy(recipient = PEER, kind = org.thanosapollo.nema.thread.MessageKind.CHAT)) { entries++ }
        assertTrue(registry.publish(joining, stableIds = false, occupantIds = false))
        session.send(group) { entries++ } // No feature capability is required.
        assertEquals(listOf("message:chat", "message:groupchat"), transport.events.toList())
        registry.revoke(joining)
        rejected(group)
        val rejoined = requireNotNull(registry.beginJoin(first, room))
        assertTrue(registry.publish(rejoined, stableIds = false, occupantIds = false))
        session.updateAttempt(attempt(2))
        rejected(group)
        rejected(group.copy(generation = attempt(2).generation))
        assertEquals(2, entries)
    }

    @Test
    fun `connection lifecycle routes every terminal path to roster retirement`() {
        listOf<(SmackSessionConnection) -> Unit>(
            { it.updateAttempt(attempt(2)) },
            SmackSessionConnection::revoke,
            { (it.privateField("connectionListener") as ConnectionListener).connectionClosed() },
            { (it.privateField("connectionListener") as ConnectionListener).connectionClosedOnError(IOException()) },
        ).forEach { action ->
            val handoff = RecordingRosterHandoff()
            val session = session(RecordingXmppConnection(), rosterHandoffFactory = { handoff })
            val lifecycle = session.privateField("rosterLifecycle") as RosterConnectionLifecycle
            lifecycle.load(attempt(1), handoff) { true }
            action(session)
            assertTrue(handoff.retired)
        }
    }

    @Test
    fun `capability discovery never enables carbons`() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val transport = RecordingXmppConnection(carbonsSupported = true)
        val session = session(transport)
        val attempt = attempt(1)
        session.updateAttempt(attempt)
        session.setPrivateField("carbonCapability", attempt to CarbonCapabilityState.ENABLED)

        val capabilities = session.discoverCapabilities(attempt.accountId, attempt.generation)

        assertEquals(0, transport.carbonEnableCalls)
        assertEquals(CarbonCapabilityState.ENABLED, capabilities.carbons)
    }

    @Test
    fun `carbon enable attempt records each terminal state`() {
        var enables = 0
        fun resolve(supported: () -> Boolean, enabled: () -> Boolean) =
            resolveCarbonCapability(supported, enabled) { enables++ }

        assertEquals(CarbonCapabilityState.UNSUPPORTED, resolve({ false }, { false }))
        assertEquals(CarbonCapabilityState.DISCOVERY_FAILED, resolve({ error("disco") }, { false }))
        assertEquals(CarbonCapabilityState.ENABLE_FAILED, resolve({ true }, { false }))
        assertEquals(CarbonCapabilityState.ENABLED, resolve({ true }, { enables == 2 }))
        assertEquals(2, enables)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            resolveCarbonCapability({ throw kotlinx.coroutines.CancellationException("disco") }, { false }) {}
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            resolveCarbonCapability({ true }, { throw kotlinx.coroutines.CancellationException("enabled") }) {}
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            resolveCarbonCapability({ true }, { false }) { throw kotlinx.coroutines.CancellationException("enable") }
        }
    }

    @Test
    fun `new attempt cannot read the previous carbon state`() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val session = session(RecordingXmppConnection())
        val previous = attempt(1)
        val replacement = attempt(2)
        session.updateAttempt(previous)
        session.setPrivateField("carbonCapability", previous to CarbonCapabilityState.ENABLED)

        session.updateAttempt(replacement)

        val failure = runCatching {
            session.discoverCapabilities(replacement.accountId, replacement.generation)
        }.exceptionOrNull()
        assertTrue(failure is SendNotAttemptedException)
    }

    @Test
    fun `sent carbon gone reaches its peer without sending a stanza`() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val transport = RecordingXmppConnection()
        val events = mutableListOf<SessionEvent>()
        val session = session(transport, events::add)
        val attempt = attempt(1)
        session.updateAttempt(attempt)
        val gone = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(ACCOUNT_BARE_JID))
            .to(JidCreate.entityBareFrom(PEER))
            .ofType(Message.Type.chat)
            .addExtension(StandardExtensionElement.builder("gone", "http://jabber.org/protocol/chatstates").build())
            .build()
        val carbon = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(ACCOUNT_BARE_JID))
            .addExtension(CarbonExtension(CarbonExtension.Direction.sent, Forwarded(gone)))
            .build()

        val listener = session.privateField("messageListener") as org.jivesoftware.smack.StanzaListener
        listener.processStanza(carbon)

        val state = events.filterIsInstance<SessionEvent.ChatState>().single().state
        assertEquals(PEER, state.peer)
        assertEquals(ChatActivity.GONE, state.activity)
        assertTrue(transport.events.isEmpty())
    }

    @Test
    fun `message listener emits direct and trusted received carbon failures for current attempt`() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val transport = RecordingXmppConnection()
        val events = mutableListOf<SessionEvent>()
        val session = session(transport, events::add)
        val attempt = attempt(1)
        session.updateAttempt(attempt)
        val direct = StanzaBuilder.buildMessage("operation")
            .from(JidCreate.entityFullFrom("peer@example.org/device"))
            .to(JidCreate.entityFullFrom("account@example.org/device"))
            .ofType(Message.Type.error)
            .setError(StanzaError.getBuilder(StanzaError.Condition.remote_server_timeout).build())
            .build()
        val carbon = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(ACCOUNT_BARE_JID))
            .to(JidCreate.entityFullFrom("$ACCOUNT_BARE_JID/test"))
            .addExtension(CarbonExtension(CarbonExtension.Direction.received, Forwarded(direct)))
            .build()
        val forgedCarbon = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(ACCOUNT_BARE_JID))
            .to(JidCreate.entityFullFrom("$ACCOUNT_BARE_JID/test"))
            .addExtension(CarbonExtension(CarbonExtension.Direction.received, Forwarded(direct)))
            .addExtension(CarbonExtension(CarbonExtension.Direction.received, Forwarded(direct)))
            .build()
        val listener = session.privateField("messageListener") as org.jivesoftware.smack.StanzaListener

        listener.processStanza(direct)
        listener.processStanza(forgedCarbon)
        listener.processStanza(carbon)
        session.revoke()
        listener.processStanza(direct)

        val failures = events.filterIsInstance<SessionEvent.OutgoingFailure>()
        assertEquals(2, failures.size)
        assertTrue(failures.all { it.attempt == attempt })
        assertTrue(failures.all { it.failure.operationId == "operation" })
    }

    @Test
    fun `blocking support is discovered on current generation without entity caps reuse`() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val transport = RecordingXmppConnection()
        val session = session(transport)
        val first = attempt(1)
        session.updateAttempt(first)

        transport.blockingSupported = true
        val supported = session.peerBlockingState(ACCOUNT_ID, first.generation, PEER)
        assertTrue(supported.supported)
        assertEquals(listOf("disco", "blocklist"), transport.events.toList())

        val second = attempt(2)
        session.updateAttempt(second)
        transport.blockingSupported = false
        val unsupported = session.peerBlockingState(ACCOUNT_ID, second.generation, PEER)

        assertFalse(unsupported.supported)
        assertEquals(listOf("disco", "blocklist", "disco"), transport.events.toList())
    }

    @Test
    fun `attempt replacement cannot publish inside old blocking IQ entry`() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val transport = RecordingXmppConnection(blockCommand = true)
        val session = session(transport)
        val first = attempt(1)
        val second = attempt(2)
        session.updateAttempt(first)

        val mutation = async(Dispatchers.Default) {
            session.setPeerBlocked(ACCOUNT_ID, first.generation, PEER, true) {}
        }
        assertTrue(transport.commandWaiting.await(5, TimeUnit.SECONDS))
        val update = Thread {
            session.updateAttempt(second)
            transport.events += "updated"
        }.apply { start() }
        waitUntilBlocked(update)

        transport.releaseCommand.countDown()
        mutation.await()
        update.join(5_000)
        assertFalse(update.isAlive)
        val events = transport.events.toList()
        val updateIndex = events.indexOf("updated")
        assertTrue(updateIndex >= 0)
        assertTrue(events.drop(updateIndex + 1).none { it.startsWith("send:") || it == "disco" || it == "blocklist" })

        val stale = runCatching {
            session.setPeerBlocked(ACCOUNT_ID, first.generation, PEER, false) {}
        }.exceptionOrNull()
        assertTrue(stale is SendNotAttemptedException)
        assertEquals(events, transport.events.toList())
    }

    @Test
    fun `blocking matches every exact and domain rule but not siblings`() {
        val peer = JidCreate.entityBareFrom("peer@example.org")
        val exact = JidCreate.entityBareFrom("peer@example.org")
        val domain = JidCreate.domainBareFrom("example.org")
        val sibling = JidCreate.entityBareFrom("other@example.org")
        val resourceOnly = JidCreate.entityFullFrom("peer@example.org/phone")
        val lookalike = JidCreate.domainBareFrom("notexample.org")

        val matches = listOf(sibling, resourceOnly, domain, exact, lookalike, exact)
            .blockingAddressesFor(peer)

        assertEquals(setOf(exact, domain), matches.toSet())
        assertEquals(2, matches.size)
    }

    @Test
    fun `revoked connection rejects connect before network entry`() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaMucUserProvider()
        installNemaMamResultProvider()
        val accountId = AccountId.require("account")
        val identity = SessionIdentity(accountId, ConnectionGeneration.require(1))
        val session = SmackSessionConnectionFactory().create(
            AccountConfiguration.create(
                id = accountId,
                bareJid = "account@example.org",
                authenticationId = "account",
                authorizationId = null,
                serviceDomain = "example.org",
                networkEndpoint = null,
            ),
            identity,
        ) {}
        session.revoke()

        val failure = runCatching {
            session.connect(
                "secret".toCharArray(),
                SessionAttemptIdentity(
                    accountId,
                    identity.generation,
                    ConnectionAttempt.require(1),
                    LifecycleEpoch.require(1),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse(session.isUsable)
    }

    @Test
    fun `production disables stream management and registers only blocking message listener`() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaMucUserProvider()
        installNemaMamResultProvider()
        val accountId = AccountId.require("account")
        val session = SmackSessionConnectionFactory().create(
            AccountConfiguration.create(
                id = accountId,
                bareJid = "account@example.org",
                authenticationId = "account",
                authorizationId = null,
                serviceDomain = "example.org",
                networkEndpoint = null,
            ),
            SessionIdentity(accountId, ConnectionGeneration.require(1)),
        ) {}
        val connection = session.privateField("connection") as XMPPTCPConnection
        val messageListener = session.privateField("messageListener")
        val blockingListeners = connection.privateField(
            "recvListeners",
            AbstractXMPPConnection::class.java,
        ) as Map<*, *>
        val syncListeners = connection.privateField(
            "syncRecvListeners",
            AbstractXMPPConnection::class.java,
        ) as Map<*, *>
        val asyncListeners = connection.privateField(
            "asyncRecvListeners",
            AbstractXMPPConnection::class.java,
        ) as Map<*, *>

        assertFalse(connection.privateField("useSm") as Boolean)
        assertFalse(connection.privateField("useSmResumption") as Boolean)
        assertSame(NemaParsingExceptionCallback, connection.parsingExceptionCallback)
        assertTrue(blockingListeners.containsKey(messageListener))
        assertFalse(syncListeners.containsKey(messageListener))
        assertFalse(asyncListeners.containsKey(messageListener))
        assertTrue((connection.privateField("stanzaAcknowledgedListeners") as Collection<*>).isEmpty())
        assertTrue((connection.privateField("stanzaDroppedListeners") as Collection<*>).isEmpty())
    }

    @Test
    fun `room lifecycle routes only through room view handoff`() {
        val session = session(RecordingXmppConnection())

        assertTrue(session.privateField("roomViewHandoff") is RoomViewHandoff<*, *, *, *>)
        assertThrows(NoSuchFieldException::class.java) { session.privateField("watchedRooms") }
        assertThrows(NoSuchFieldException::class.java) { session.privateField("roomStatusHandoff") }
        assertThrows(ClassNotFoundException::class.java) { Class.forName("org.thanosapollo.nema.xmpp.smack.RoomStatusHandoff") }
    }

    @Test
    fun `parsing callback isolates only aligned malformed MAM results`() {
        handleNemaParsingException(MalformedMamResultException("malformed MAM result"))

        val failure = runCatching {
            handleNemaParsingException(IOException("other parser failure"))
        }.exceptionOrNull()

        assertTrue(failure is NemaProtocolParsingException)
        assertTrue(failure?.cause is IOException)
        assertEquals(SessionFailureReason.PROTOCOL, classifySmackFailure(failure as Exception))
    }

    @Test
    fun `resumable disconnect is not usable even while Smack reports connected and authenticated`() {
        var losses = 0
        val usable = {
            isSmackSessionUsable(
                expectedBareJid = "person@example.org",
                boundBareJid = "person@example.org",
                connected = true,
                authenticated = true,
                disconnectedButResumable = true,
            )
        }
        val notifier = ConnectionLossNotifier(usable) { losses++ }

        assertFalse(usable())
        notifier.remoteClosed(SessionFailureReason.NETWORK)
        notifier.remoteClosed(SessionFailureReason.NETWORK)

        assertEquals(1, losses)
    }

    @Test
    fun `bound account identity is required after every authentication path`() {
        assertTrue(
            isSmackSessionUsable(
                expectedBareJid = "person@example.org",
                boundBareJid = "person@example.org",
                connected = true,
                authenticated = true,
                disconnectedButResumable = false,
            ),
        )
        assertFalse(
            isSmackSessionUsable(
                expectedBareJid = "person@example.org",
                boundBareJid = "other@example.org",
                connected = true,
                authenticated = true,
                disconnectedButResumable = false,
            ),
        )
        assertFalse(
            isSmackSessionUsable(
                expectedBareJid = "person@example.org",
                boundBareJid = null,
                connected = true,
                authenticated = true,
                disconnectedButResumable = false,
            ),
        )
    }

    @Test
    fun `connected unauthenticated transport always resets`() {
        assertTrue(
            shouldResetSmackTransport(
                connected = true,
                authenticated = false,
            ),
        )
        assertFalse(
            shouldResetSmackTransport(
                connected = true,
                authenticated = true,
            ),
        )
    }

    @Test
    fun `certificate identity matches service domain but never endpoint`() {
        val identities = CertificateIdentities(
            dnsNames = listOf("example.org"),
            xmppAddresses = emptyList(),
            srvNames = emptyList(),
        )

        assertTrue(XmppCertificateIdentity.matches("example.org", identities))
        assertFalse(XmppCertificateIdentity.matches("gateway.invalid", identities))
        assertFalse(
            XmppCertificateIdentity.matches(
                "example.org",
                identities.copy(dnsNames = listOf("gateway.invalid")),
            ),
        )
    }

    @Test
    fun `certificate identity accepts XMPP address SRV identity and strict wildcard`() {
        assertTrue(
            XmppCertificateIdentity.matches(
                "example.org",
                CertificateIdentities(xmppAddresses = listOf("example.org")),
            ),
        )
        assertTrue(
            XmppCertificateIdentity.matches(
                "example.org",
                CertificateIdentities(srvNames = listOf("_xmpp-client.example.org")),
            ),
        )
        assertTrue(
            XmppCertificateIdentity.matches(
                "chat.example.org",
                CertificateIdentities(dnsNames = listOf("*.example.org")),
            ),
        )
        assertFalse(
            XmppCertificateIdentity.matches(
                "deep.chat.example.org",
                CertificateIdentities(dnsNames = listOf("*.example.org")),
            ),
        )
    }

    private fun Any.privateField(name: String, owner: Class<*> = javaClass): Any? =
        owner.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.get(this)
        }

    private fun Any.setPrivateField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.set(this, value)
        }
    }

    private fun session(
        connection: RecordingXmppConnection,
        event: (SessionEvent) -> Unit = {},
        rosterHandoffFactory: (SessionAttemptIdentity) -> RosterAttemptHandoff = { RecordingRosterHandoff() },
    ) = SmackSessionConnection(
        connection = connection,
        authenticationId = "account",
        expectedBareJid = ACCOUNT_BARE_JID,
        event = event,
        rosterHandoffFactory = rosterHandoffFactory,
    )

    private class RecordingRosterHandoff : RosterAttemptHandoff {
        var retired = false
        override fun load(admitted: () -> Boolean) = RosterLoadResult.Unavailable
        override fun retire() { retired = true }
    }

    private fun attempt(generation: Long) = SessionAttemptIdentity(
        ACCOUNT_ID,
        ConnectionGeneration.require(generation),
        ConnectionAttempt.require(1),
        LifecycleEpoch.require(1),
    )

    private fun waitUntilBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private class RecordingXmppConnection(
        private val blockCommand: Boolean = false,
        private val carbonsSupported: Boolean = false,
    ) : XMPPTCPConnection(
        XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(JidCreate.domainBareFrom("example.org"))
            .setUsernameAndPassword("account", null)
            .build(),
    ) {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val commandWaiting = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        var blockingSupported = true
        var carbonEnableCalls = 0
        private val blocked = mutableListOf<org.jxmpp.jid.Jid>()

        init {
            connected = true
            authenticated = true
            user = JidCreate.entityFullFrom("$ACCOUNT_BARE_JID/test")
        }

        override fun throwNotConnectedExceptionIfAppropriate() = Unit

        override fun sendStanzaInternal(packet: Stanza) {
            if (packet is Message) {
                events += "message:${packet.type}"
                return
            }
            val request = packet as IQ
            val response = when (request) {
                is DiscoverInfo -> {
                    events += "disco"
                    DiscoverInfo.builder(request.stanzaId)
                        .ofType(IQ.Type.result)
                        .from(request.to ?: xmppServiceDomain)
                        .apply {
                            if (blockingSupported) addFeature(BlockingCommandManager.NAMESPACE)
                            if (carbonsSupported) addFeature(CarbonExtension.NAMESPACE)
                        }
                        .build()
                }
                is Carbon.Enable -> {
                    carbonEnableCalls++
                    IQ.createResultIQ(request).apply { from = xmppServiceDomain }
                }
                is BlockListIQ -> {
                    events += "blocklist"
                    BlockListIQ(blocked.toList()).apply {
                        type = IQ.Type.result
                        stanzaId = request.stanzaId
                        from = xmppServiceDomain
                    }
                }
                is BlockContactsIQ -> {
                    events += "send:block"
                    if (blockCommand) {
                        commandWaiting.countDown()
                        check(releaseCommand.await(5, TimeUnit.SECONDS)) { "blocked command was not released" }
                    }
                    blocked += request.jids
                    IQ.createResultIQ(request).apply { from = xmppServiceDomain }
                }
                is UnblockContactsIQ -> {
                    events += "send:unblock"
                    blocked.removeAll(request.jids.toSet())
                    IQ.createResultIQ(request).apply { from = xmppServiceDomain }
                }
                else -> error("Unexpected IQ ${request.javaClass.simpleName}")
            }
            processStanza(response)
        }
    }

    companion object {
        private val ACCOUNT_ID = AccountId.require("account")
        private const val ACCOUNT_BARE_JID = "account@example.org"
        private const val PEER = "peer@example.org"
    }
}
