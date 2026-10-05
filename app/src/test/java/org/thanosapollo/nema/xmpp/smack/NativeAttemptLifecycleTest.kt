package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.AsyncButOrdered
import org.thanosapollo.nema.account.AccountTransportPolicy
import org.thanosapollo.nema.xmpp.httpupload.AccountHttpTransfer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.account.NetworkEndpoint
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.transport.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeAttemptLifecycleTest {
    private val account = AccountId.require("native-attempt")
    private fun attempt(n: Long) = SessionAttemptIdentity(account, ConnectionGeneration.require(n),
        ConnectionAttempt.require(n), LifecycleEpoch.require(1))

    @Test fun actualServerProbeAcceptsPongAndRetiresBlackholedSocket() = runBlocking {
        Fixture().use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            assertTrue(f.session.probe(attempt(1)))
            assertTrue(f.session.isUsable)
            assertEquals(1, f.pings.get())
            f.events.clear()
            f.blackholePing.set(true)
            val started = System.nanoTime()
            assertFalse(f.session.probe(attempt(1)))
            assertTrue("native probe must complete within its bounded timeout plus scheduling allowance",
                System.nanoTime() - started < 15_000_000_000L)
            assertFalse(f.transports.single().second.isConnected)
            assertEquals(2, f.pings.get())
            assertEquals(1, f.events.filterIsInstance<SessionEvent.ConnectionLost>().size)
            f.session.reconnect(attempt(2))
            assertFalse(f.session.probe(attempt(1)))
            f.assertOwner(attempt(2))
            assertEquals(2, f.pings.get())
        }
    }

    @Test fun pingFailureRetiresHealthyFlagsOnceAndStaleListenerCannotTouchSuccessor() = runBlocking {
        Fixture().use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            val native = f.transports.single().second
            val manager = org.jivesoftware.smackx.ping.PingManager.getInstanceFor(native)
            @Suppress("UNCHECKED_CAST")
            val listeners = manager.javaClass.getDeclaredField("pingFailedListeners").apply { isAccessible = true }
                .get(manager) as Set<org.jivesoftware.smackx.ping.PingFailedListener>
            assertEquals("physical attempt must install one ping failure owner", 1, listeners.size)
            val old = listeners.single()
            f.events.clear()
            assertTrue(native.isConnected && native.isAuthenticated)
            old.pingFailed()
            assertFalse(f.session.isUsable)
            assertFalse("the exact physical socket must be closed", native.isConnected)
            old.pingFailed()
            assertEquals(1, f.events.filterIsInstance<SessionEvent.ConnectionLost>().size)
            assertEquals(attempt(1), f.events.filterIsInstance<SessionEvent.ConnectionLost>().single().attempt)
            assertEquals(SessionFailureReason.NETWORK, f.events.filterIsInstance<SessionEvent.ConnectionLost>().single().reason)
            assertTrue(listeners.isEmpty())
            f.session.reconnect(attempt(2))
            f.events.clear()
            old.pingFailed()
            f.assertOwner(attempt(2))
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun heldOldNativeErrorCannotTearDownAuthenticatedSuccessor() = heldNativeError(false)
    @Test fun heldOldNativeWriterErrorCannotTearDownAuthenticatedSuccessor() = heldNativeError(true)

    @Test fun releasedOldNativeErrorBeforeReplacementAllowsSuccessor() = heldNativeError(false, true)

    private fun heldNativeError(writer: Boolean, early: Boolean = false) = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val credentials = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        SocksFixture { socket, _ -> serveXmpp(socket, credentials, messages) }.use { proxy ->
            val echoed = LinkedBlockingQueue<String>()
            val transports = CopyOnWriteArrayList<Pair<TorSocketFactory, OnionXmppConnection>>()
            val configuration = AccountConfiguration.create(account, "fixture@$VALID_ONION", "fixture", null, VALID_ONION, null)
            val session = SmackSessionConnectionFactory { "fixture-only".toCharArray() }.createSession(configuration,
                SessionIdentity(account, attempt(1).generation), {}) { owner ->
                val route = TorSocketFactory(NetworkEndpoint.create(VALID_ONION, 5222), proxy.address, 2_000, owner, VALID_ONION)
                val native = OnionXmppConnection(SmackSessionConnectionFactory.configurationFor(configuration, route), route)
                    .apply { replyTimeout = 3_000 }
                native.addAsyncStanzaListener({ stanza ->
                    if ((stanza as org.jivesoftware.smack.packet.Message).body == "fixture-message") echoed.add("echo")
                }, org.jivesoftware.smack.filter.StanzaTypeFilter.MESSAGE)
                transports += route to native
                route to native
            }
            val heldThreads = mutableListOf<Thread>()
            val releaseWriter = CountDownLatch(1)
            val writerTimedOut = AtomicBoolean()
            try {
                session.connect("fixture-only".toCharArray(), attempt(1))
                assertTrue(session.isUsable)
                session.send(message(attempt(1))) {}
                assertEquals("echo", echoed.poll(3, TimeUnit.SECONDS))
                val (oldRoute, oldNative) = transports.single()
                val monitor = requireNotNull(AbstractXMPPConnection::class.java.getDeclaredField("notifyConnectionErrorMonitor")
                    .apply { isAccessible = true }.get(oldNative))
                val reader = org.jivesoftware.smack.tcp.XMPPTCPConnection::class.java
                    .getDeclaredField(if (writer) "packetWriter" else "packetReader").apply { isAccessible = true }.get(oldNative)
                val readerName = reader.javaClass.getDeclaredField("threadName").apply { isAccessible = true }.get(reader)
                if (writer) {
                    val writerEntered = CountDownLatch(1)
                    oldNative.setBundleandDeferCallback {
                        writerEntered.countDown()
                        writerTimedOut.set(!releaseWriter.await(3, TimeUnit.SECONDS))
                        0
                    }
                    session.send(message(attempt(1))) {}
                    assertTrue("actual native writer must reach its pre-write callback", writerEntered.await(3, TimeUnit.SECONDS))
                }
                fun successor() {
                    session.updateAttempt(attempt(2))
                    runBlocking { session.reconnect(attempt(2)) }
                    val (route, native) = transports.last()
                    assertTrue(native.isConnected && native.isAuthenticated)
                    assertEquals("fixture@$VALID_ONION", native.user.asBareJid().toString())
                    assertTrue(route.hasOnionProof(attempt(2)))
                    assertTrue(session.isUsable)
                    assertEquals(attempt(2), rosterAttempt(physical(session)))
                    runBlocking { session.send(message(attempt(2))) {} }
                    assertEquals("echo", echoed.poll(3, TimeUnit.SECONDS))
                }
                synchronized(monitor) {
                    oldRoute.invalidateAttempt()
                    releaseWriter.countDown()
                    // Capture an actual old native I/O thread at the error boundary, not a running flag.
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (heldThreads.isEmpty() && System.nanoTime() < deadline) {
                        heldThreads += Thread.getAllStackTraces().entries.filter { (thread, stack) ->
                            thread.name == readerName && thread.state == Thread.State.BLOCKED &&
                                stack.any { it.className == AbstractXMPPConnection::class.java.name && it.methodName == "notifyConnectionError" } &&
                                stack.any { it.methodName == if (writer) "writePackets" else "parsePackets" } &&
                                blockedOn(thread, monitor, Thread.currentThread().id)
                        }.map { it.key }
                        Thread.yield()
                    }
                    assertTrue("old native error must enter the held monitor", heldThreads.isNotEmpty())
                    oldNative.instantShutdown()
                    if (!early) successor()
                }
                heldThreads.forEach { it.join(3_000); assertFalse("old error thread must finish", it.isAlive) }
                if (early) successor()
                assertTrue("old native error must not shut down successor", session.isUsable)
                assertTrue(transports.last().first.hasOnionProof(attempt(2)))
                assertEquals(attempt(2), rosterAttempt(physical(session)))
                session.send(message(attempt(2))) {}
                assertEquals("echo", echoed.poll(3, TimeUnit.SECONDS))
                assertFalse("writer gate timed out", writerTimedOut.get())
                assertEquals(2, credentials.size)
            } finally { releaseWriter.countDown(); session.revoke(); session.disconnect() }
        }
    }

    @Test fun delayedOldErrorCallbackCannotRetireSuccessorRoster() = heldCallback(false)
    @Test fun revokeWhileOldErrorCallbackIsHeldDeniesAllEntry() = heldCallback(true)

    private fun heldCallback(revoke: Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callbackTimedOut = AtomicBoolean()
        val blocker = object : ConnectionListener {
            override fun connectionClosedOnError(error: Exception) {
                entered.countDown()
                callbackTimedOut.set(!release.await(3, TimeUnit.SECONDS))
            }
        }
        Fixture(before = { native, first -> if (first) native.addConnectionListener(blocker) }).use { f ->
            val oldPhysical = physical(f.session)
            val oldNative = f.transports.single().second
            oldNative.addConnectionListener(object : ConnectionListener {
                override fun connectionClosedOnError(error: Exception) { finished.countDown() }
            })
            try {
                val input = "fixture-only".toCharArray()
                f.session.connect(input, attempt(1))
                input.fill('x')
                assertFalse(f.session.javaClass.declaredFields.any { it.type == CharArray::class.java })
                f.events.clear()
                f.transports.single().first.invalidateAttempt()
                assertTrue("native old error callback entered", entered.await(3, TimeUnit.SECONDS))
                assertFalse(oldNative.isConnected)
                f.session.reconnect(attempt(2))
                val successor = physical(f.session)
                assertNotSame(oldPhysical, successor)
                f.assertOwner(attempt(2))
                assertNull(rosterAttempt(oldPhysical))
                if (revoke) {
                    f.session.revoke()
                    assertFalse(f.session.isUsable)
                    assertFalse(f.transports.last().first.hasOnionProof(attempt(2)))
                    assertNull(rosterAttempt(successor))
                    var stanzaEntry = false
                    assertFails<SendNotAttemptedException> { f.session.send(message(attempt(2))) { stanzaEntry = true } }
                    assertFails<SendNotAttemptedException> {
                        f.session.fetchHttpFile(account, attempt(2).generation, "https://example.org/fixture")
                    }
                    assertFails<kotlinx.coroutines.CancellationException> { f.session.reconnect(attempt(3)) }
                    assertFalse(stanzaEntry)
                    assertEquals(0, f.httpEntries.get())
                }
                assertEquals("old callback must remain held through successor ownership", 1L, finished.count)
                release.countDown()
                assertTrue("old native callback iteration finished", finished.await(3, TimeUnit.SECONDS))
                assertFalse("callback gate timed out", callbackTimedOut.get())
                if (!revoke) f.assertOwner(attempt(2))
                assertTrue(f.events.none { it is SessionEvent.ConnectionLost })
                assertEquals(2, f.credentials.size)
                assertEquals(2, f.transports.size)
            } finally { release.countDown() }
        }
    }

    @Test fun legitimateCurrentErrorAndStreamCloseRetireCurrentOwner() = runBlocking {
        for (streamClose in listOf(false, true)) Fixture().use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            val owner = physical(f.session)
            f.assertOwner(attempt(1))
            f.events.clear()
            if (streamClose) f.closeStream() else f.transports.single().first.invalidateAttempt()
            val deadline = System.nanoTime() + 3_000_000_000L
            var loss: SessionEvent.ConnectionLost? = null
            while (loss == null && System.nanoTime() < deadline) {
                loss = f.events.poll(10, TimeUnit.MILLISECONDS) as? SessionEvent.ConnectionLost
            }
            assertNotNull("current native close/error must publish loss", loss)
            assertEquals(attempt(1), loss!!.attempt)
            assertFalse(f.session.isUsable)
            assertNull(rosterAttempt(owner))
            f.session.reconnect(attempt(2))
            f.assertOwner(attempt(2))
        }
    }

    @Test fun queuedOldNativeStreamCloseCannotDisconnectSuccessor() = runBlocking {
        Fixture().use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            val old = f.transports.single().second
            @Suppress("UNCHECKED_CAST")
            val ordered = AbstractXMPPConnection::class.java.getDeclaredField("ASYNC_BUT_ORDERED")
                .apply { isAccessible = true }.get(null) as AsyncButOrdered<AbstractXMPPConnection>
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val drained = CountDownLatch(1)
            val queueTimedOut = AtomicBoolean()
            ordered.performAsyncButOrdered(old) { entered.countDown(); queueTimedOut.set(!release.await(3, TimeUnit.SECONDS)) }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                f.closeStream()
                awaitNative("old reader must enqueue stream close") {
                    AbstractXMPPConnection::class.java.getDeclaredField("closingStreamReceived")
                        .apply { isAccessible = true }.getBoolean(old)
                }
                // Native shutdown joins the reader; its already queued disconnect still owns old.
                old.instantShutdown()
                ordered.performAsyncButOrdered(old) { drained.countDown() }
                f.session.reconnect(attempt(2))
                f.assertOwner(attempt(2))
                assertEquals("old ordered close must remain queued through successor ownership", 1L, drained.count)
                release.countDown()
                assertTrue("queued native close must finish", drained.await(3, TimeUnit.SECONDS))
                assertFalse("ordered gate timed out", queueTimedOut.get())
                f.assertOwner(attempt(2))
            } finally { release.countDown() }
        }
    }

    @Test fun replacementRejectsForeignAndRegressedOwnersAndAllowsSameGenerationNextAttempt() = runBlocking {
        Fixture().use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            val second = attempt(2).copy(generation = attempt(1).generation)
            f.session.reconnect(second)
            f.assertOwner(second)
            for (invalid in listOf(second.copy(accountId = AccountId.require("other-account")),
                second.copy(epoch = LifecycleEpoch.require(2)), attempt(1))) {
                assertFails<IllegalArgumentException> { f.session.updateAttempt(invalid) }
                f.assertOwner(second)
            }
            f.session.reconnect(attempt(3))
            f.assertOwner(attempt(3))
            assertFails<IllegalArgumentException> { f.session.updateAttempt(attempt(4).copy(generation = attempt(2).generation)) }
            var entered = false
            assertFails<SendNotAttemptedException> { f.session.send(message(second)) { entered = true } }
            assertFails<SendNotAttemptedException> { f.session.send(message(attempt(3)).copy(accountId = AccountId.require("other-account"))) { entered = true } }
            assertFalse(entered)
            f.assertOwner(attempt(3))
            assertEquals(3, f.credentials.size)
        }
    }

    @Test fun heldCredentialCompletionCannotAuthorizeRevokedReplacedOrCancelledAttempt() = runBlocking {
        for (action in listOf("revoke", "replace", "cancel", "foreign")) {
            val entered = CompletableDeferred<SessionAttemptIdentity>()
            val release = CompletableDeferred<Unit>()
            val loaded = "fixture-only".toCharArray()
            Fixture(loader = { owner ->
                entered.complete(owner)
                withContext(NonCancellable) { release.await() }
                loaded
            }).use { f ->
                val supplied = "fixture-only".toCharArray()
                f.session.connect(supplied, attempt(1))
                assertEquals("fixture-only", supplied.concatToString())
                val pending = async(Dispatchers.IO) { runCatching { f.session.reconnect(attempt(2)) } }
                try {
                    assertEquals(attempt(2), withTimeout(3_000) { entered.await() })
                    assertEquals(1, f.credentials.size)
                    assertFalse(f.session.isUsable)
                    when (action) {
                        "revoke" -> f.session.revoke()
                        "replace" -> f.session.updateAttempt(attempt(3))
                        "cancel" -> pending.cancel()
                        "foreign" -> assertFails<IllegalArgumentException> { f.session.updateAttempt(attempt(3).copy(accountId = AccountId.require("foreign"))) }
                    }
                    release.complete(Unit)
                    runCatching { pending.await() }
                    pending.join()
                    assertTrue("stale acquired array must be wiped", loaded.all { it == '\u0000' })
                    if (action == "foreign") {
                        f.assertOwner(attempt(2))
                        assertEquals(2, f.credentials.size)
                    } else {
                        assertEquals("no stale credentials may enter wire", 1, f.credentials.size)
                        assertFalse(f.session.isUsable)
                        assertFalse(f.transports.last().second.isConnected)
                    }
                } finally { release.complete(Unit); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun freshCredentialProviderIsQualifiedWipedAndMissingIsTerminalAuthentication() = runBlocking {
        val owners = mutableListOf<SessionAttemptIdentity>()
        val arrays = mutableListOf<CharArray>()
        Fixture(loader = { owner ->
            owners += owner
            if (owner == attempt(3)) null else "fixture-only".toCharArray().also(arrays::add)
        }).use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            assertTrue(owners.isEmpty())
            assertFails<IllegalArgumentException> { f.session.reconnect(attempt(2).copy(accountId = AccountId.require("foreign"))) }
            assertTrue(owners.isEmpty())
            f.session.reconnect(attempt(2))
            f.assertOwner(attempt(2))
            assertEquals(listOf(attempt(2)), owners)
            assertTrue(arrays.single().all { it == '\u0000' })
            val failure = runCatching { f.session.reconnect(attempt(3)) }.exceptionOrNull()
            assertTrue(failure is SessionFailure)
            assertEquals(SessionFailureReason.AUTHENTICATION, (failure as SessionFailure).reason)
            assertFalse(f.session.isUsable)
            assertFalse(f.transports.last().second.isConnected)
            assertEquals(2, f.credentials.size)
        }
    }

    @Test fun revokedDuringConstructionAndFailedFactoryNeverResurrectOldOwner() = runBlocking {
        for (action in listOf("revoke", "disconnect", "fail")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val acquired = AtomicInteger()
            val constructions = AtomicInteger()
            Fixture(loader = { acquired.incrementAndGet(); "fixture-only".toCharArray() }, before = { _, first ->
                if (!first) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    if (action == "fail" && constructions.incrementAndGet() == 1) throw IllegalStateException("synthetic factory failure")
                }
            }).use { f ->
                f.session.connect("fixture-only".toCharArray(), attempt(1))
                val old = f.transports.single()
                val pending = async(Dispatchers.IO) { runCatching { f.session.reconnect(attempt(2)) } }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                when (action) {
                    "revoke" -> f.session.revoke()
                    "disconnect" -> f.session.disconnect()
                }
                release.countDown()
                assertTrue(pending.await().isFailure)
                assertEquals(0, acquired.get())
                assertEquals(1, f.credentials.size)
                assertFalse(old.first.hasOnionProof(attempt(1)))
                assertFalse(f.session.isUsable)
                assertTrue(f.transports.all { !it.second.isConnected })
                if (action != "fail") assertFails<java.io.IOException> { f.transports.last().first.beginAttempt(attempt(3)) }
                if (action == "fail") {
                    f.session.reconnect(attempt(3))
                    f.assertOwner(attempt(3))
                    assertEquals(1, acquired.get())
                    assertEquals(2, f.credentials.size)
                }
            }
        }
    }

    @Test fun missingReacquisitionTerminatesRealControllerWithoutAnonymousRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loads = AtomicInteger()
        var emit: (SessionEvent) -> Unit = {}
        Fixture(loader = { loads.incrementAndGet(); null }, event = { emit(it) }).use { f ->
            val controller = ActiveSessionController(scope,
                SessionConnectionFactory { _, _, callback -> emit = callback; f.session }, retryWait = {})
            try {
                controller.start(AccountConfiguration.create(account, "fixture@$VALID_ONION", "fixture", null, VALID_ONION, null),
                    "fixture-only".toCharArray())
                assertTrue(controller.state.value is ConnectionState.Connected)
                f.transports.single().first.invalidateAttempt()
                awaitNative("missing reconnect credential must terminate authentication") {
                    controller.state.value is ConnectionState.Failed
                }
                assertEquals(SessionFailureReason.AUTHENTICATION, (controller.state.value as ConnectionState.Failed).reason)
                assertEquals(1, loads.get())
                assertEquals(1, f.credentials.size)
                assertFalse(f.session.isUsable)
            } finally { controller.stop(); scope.cancel() }
        }
    }

    @Test fun runtimeDefaultFactoryReacquiresOnlyCapturedAccountAndHonorsVaultInvalidation() = runBlocking {
        val values = mutableMapOf<AccountId, org.thanosapollo.nema.credentials.WrappedCredential>()
        val reads = mutableListOf<AccountId>()
        var invalid = false
        val vault = org.thanosapollo.nema.credentials.CredentialVault(
            object : org.thanosapollo.nema.credentials.CredentialBlobStore {
                override fun read(accountId: AccountId): org.thanosapollo.nema.credentials.WrappedCredential? {
                    reads += accountId
                    return values[accountId]
                }
                override fun write(accountId: AccountId, credential: org.thanosapollo.nema.credentials.WrappedCredential) { values[accountId] = credential }
                override fun delete(accountId: AccountId) { values.remove(accountId) }
            }, object : org.thanosapollo.nema.credentials.CredentialCipher {
                override fun encrypt(accountId: AccountId, plaintext: ByteArray) = org.thanosapollo.nema.credentials.WrappedCredential(byteArrayOf(1), plaintext.copyOf())
                override fun decrypt(accountId: AccountId, credential: org.thanosapollo.nema.credentials.WrappedCredential): ByteArray {
                    if (invalid) throw org.thanosapollo.nema.credentials.CredentialInvalidatedException()
                    return credential.ciphertext.copyOf()
                }
                override fun deleteKey(accountId: AccountId) = Unit
            })
        vault.store(account, "fixture-only".toCharArray())
        vault.store(AccountId.require("unrelated"), "must-not-be-loaded".toCharArray())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "credential-wiring-${java.util.UUID.randomUUID()}.db"
        val database = org.thanosapollo.nema.storage.NemaDatabase.create(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtime = org.thanosapollo.nema.service.SessionRuntime(
            org.thanosapollo.nema.storage.AccountRepository(database.accountDao()), vault,
            org.thanosapollo.nema.storage.MessageStore(database),
            org.thanosapollo.nema.storage.PeerIdentityStore(database.messageDao()), scope)
        try {
            val factory = field(requireNotNull(field(runtime, "controller")), "factory") as SmackSessionConnectionFactory
            Fixture(factory = factory).use { f ->
                f.session.connect("fixture-only".toCharArray(), attempt(1))
                f.session.reconnect(attempt(2))
                f.assertOwner(attempt(2))
                assertEquals(listOf(account), reads)
                invalid = true
                val failure = runCatching { f.session.reconnect(attempt(3)) }.exceptionOrNull() as SessionFailure
                assertEquals(SessionFailureReason.AUTHENTICATION, failure.reason)
                assertEquals(listOf(account, account), reads)
                assertFalse(values.containsKey(account))
                assertTrue(values.containsKey(AccountId.require("unrelated")))
                assertEquals(2, f.credentials.size)
            }
        } finally { runtime.stop(); scope.coroutineContext[Job]!!.cancelAndJoin(); database.close(); context.deleteDatabase(name) }
    }

    @Test fun factoryFailureAfterRouteAcquisitionClosesCapturedRoute() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        SocksFixture { _, _ -> Unit }.use { proxy ->
            val config = AccountConfiguration.create(account, "fixture@$VALID_ONION", "fixture", null, VALID_ONION, null)
            lateinit var route: TorSocketFactory
            lateinit var native: OnionXmppConnection
            assertFails<IllegalStateException> {
                SmackSessionConnectionFactory().createSession(config, SessionIdentity(account, attempt(1).generation), {},
                    httpTransfer = { throw IllegalStateException("synthetic construction failure") }) { owner ->
                    route = TorSocketFactory(NetworkEndpoint.create(VALID_ONION, 5222), proxy.address, 2_000, owner, VALID_ONION)
                    native = OnionXmppConnection(SmackSessionConnectionFactory.configurationFor(config, route), route)
                    route to native
                }
            }
            assertFalse(native.isConnected)
            assertFails<java.io.IOException> { route.beginAttempt(attempt(1)) }
            assertTrue(proxy.destinations.isEmpty())
        }
    }

    @Test fun cancellationAtDispatcherReturnDisposesPhysicalOwner() = runBlocking {
        val work = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { work.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val acquired = CountDownLatch(1)
        val buffer = "fixture-only".toCharArray()
        Fixture(loader = { acquired.countDown(); buffer }).use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            val pending = scope.async { f.session.reconnect(attempt(2)) }
            fun drain() {
                val deadline = System.nanoTime() + 3_000_000_000L
                while (!pending.isCompleted && System.nanoTime() < deadline) work.poll(10, TimeUnit.MILLISECONDS)?.run()
                assertTrue("cancelled invocation must finish cleanup", pending.isCompleted)
            }
            try {
                requireNotNull(work.poll(3, TimeUnit.SECONDS)).run()
                var resume = requireNotNull(work.poll(3, TimeUnit.SECONDS))
                while (acquired.count != 0L) {
                    resume.run()
                    resume = requireNotNull(work.poll(3, TimeUnit.SECONDS))
                }
                pending.cancel()
                resume.run()
                drain()
                assertTrue(buffer.all { it == '\u0000' })
                assertFalse("cancelled dispatcher return must not leave an owned live transport", f.session.isUsable)
                assertFalse(f.transports.last().second.isConnected)
            } finally {
                pending.cancel()
                drain()
                scope.cancel()
            }
        }
    }

    @Test fun providerFailureIsSanitizedTerminalAuthentication() = runBlocking {
        Fixture(loader = { throw IllegalStateException("synthetic-private-detail") }).use { f ->
            f.session.connect("fixture-only".toCharArray(), attempt(1))
            val failure = runCatching { f.session.reconnect(attempt(2)) }.exceptionOrNull() as SessionFailure
            assertEquals(SessionFailureReason.AUTHENTICATION, failure.reason)
            assertNull(failure.cause)
            assertEquals(1, f.credentials.size)
            assertFalse(f.session.isUsable)
        }
    }

    private inner class Fixture(
        loader: suspend (SessionAttemptIdentity) -> CharArray? = { "fixture-only".toCharArray() },
        factory: SmackSessionConnectionFactory? = null,
        event: (SessionEvent) -> Unit = {},
        before: (OnionXmppConnection, Boolean) -> Unit = { _, _ -> }) : AutoCloseable {
        val credentials = CopyOnWriteArrayList<String>()
        val peers = CopyOnWriteArrayList<Socket>()
        val transports = CopyOnWriteArrayList<Pair<TorSocketFactory, OnionXmppConnection>>()
        val events = LinkedBlockingQueue<SessionEvent>()
        val httpEntries = AtomicInteger()
        val pings = AtomicInteger()
        val blackholePing = AtomicBoolean()
        val proxy = SocksFixture { socket, _ ->
            peers += socket
            try { serveXmpp(socket, credentials, mutableListOf(), onPing = {
                pings.incrementAndGet(); !blackholePing.get()
            }) } catch (failure: Exception) {
                println("NATIVE_PEER_FAILURE ${failure.javaClass.name} ${failure.stackTrace.toList()}")
                throw failure
            }
        }
        val session: SessionConnection
        init {
            SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
            val config = AccountConfiguration.create(account, "fixture@$VALID_ONION", "fixture", null, VALID_ONION, null)
            session = (factory ?: SmackSessionConnectionFactory(loader)).createSession(config, SessionIdentity(account, attempt(1).generation),
                { events.add(it); event(it) }, httpTransfer = {
                    AccountHttpTransfer(AccountTransportPolicy.TOR, proxy.address) {
                        eventListener(object : okhttp3.EventListener() {
                            override fun callStart(call: okhttp3.Call) { httpEntries.incrementAndGet() }
                        })
                    }
                }) { owner ->
                val route = TorSocketFactory(NetworkEndpoint.create(VALID_ONION, 5222), proxy.address, 2_000, owner, VALID_ONION)
                val native = OnionXmppConnection(SmackSessionConnectionFactory.configurationFor(config, route), route)
                    .apply { replyTimeout = 3_000 }
                before(native, transports.isEmpty())
                transports += route to native
                route to native
            }
        }
        fun closeStream() { peers.last().outputStream.apply { write("</stream:stream>".toByteArray()); flush() } }
        fun assertOwner(attempt: SessionAttemptIdentity) {
            val (route, native) = transports.last()
            assertTrue(native.isConnected && native.isAuthenticated)
            assertTrue(route.hasOnionProof(attempt))
            assertTrue(session.isUsable)
            assertEquals(attempt, rosterAttempt(physical(session)))
        }
        override fun close() { runBlocking { session.revoke(); session.disconnect() }; proxy.close() }
    }

    // Reflection keeps JVM-only diagnostics out of the Android production/API surface.
    private fun blockedOn(thread: Thread, monitor: Any, owner: Long): Boolean {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val bean = factory.getMethod("getThreadMXBean").invoke(null)
        val info = Class.forName("java.lang.management.ThreadMXBean")
            .getMethod("getThreadInfo", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(bean, thread.id, 64) ?: return false
        val type = Class.forName("java.lang.management.ThreadInfo")
        val lock = type.getMethod("getLockInfo").invoke(info) ?: return false
        return type.getMethod("getLockOwnerId").invoke(info) == owner &&
            Class.forName("java.lang.management.LockInfo").getMethod("getIdentityHashCode").invoke(lock) == System.identityHashCode(monitor)
    }

    private fun message(owner: SessionAttemptIdentity) = OutgoingMessageEnvelope(owner.accountId, owner.generation,
        1, "fixture-op", "fixture-origin", "peer@$VALID_ONION", "fixture-message", null)
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun physical(session: SessionConnection): Any = requireNotNull(field(requireNotNull(field(session, "current")), "connection"))
    private fun rosterAttempt(owner: Any): Any? {
        val active = field(requireNotNull(field(owner, "rosterLifecycle")), "active") as AtomicReference<*>
        return active.get()?.let { field(it, "attempt") }
    }
    private inline fun <reified T : Throwable> assertFails(action: () -> Unit) {
        try { action(); fail("Expected ${T::class.java.simpleName}") } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
    private fun awaitNative(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(description, condition())
    }
}
