package org.thanosapollo.nema.session

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.service.stopServiceRuntime
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingFailureEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSessionControllerTest {
    @Test
    fun `HTTP downloads cannot cross account switch or retired generation`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val old = requireNotNull(controller.lifecycle.value.dispatchLease()).identity
        assertEquals("ok", controller.fetchHttpFile(old.accountId, old.generation, "https://fixture.invalid/a")?.decodeToString())
        val first = factory.created.single()
        controller.switchTo(account("second"), "secret".toCharArray()) {}
        assertTrue(first.revoked)
        assertTrue(runCatching { controller.fetchHttpFile(old.accountId, old.generation, "https://fixture.invalid/b") }.isFailure)
        assertEquals(listOf("https://fixture.invalid/a"), first.httpQueries)
        assertTrue(factory.created.last().httpQueries.isEmpty())
        val current = requireNotNull(controller.lifecycle.value.dispatchLease()).identity
        assertEquals("ok", controller.fetchHttpFile(current.accountId, current.generation, "https://fixture.invalid/c")?.decodeToString())
        controller.stop()
        assertTrue(runCatching { controller.fetchHttpFile(current.accountId, current.generation, "https://fixture.invalid/d") }.isFailure)
        assertEquals(listOf("https://fixture.invalid/c"), factory.created.last().httpQueries)
    }

    @Test
    fun `blocking uses exact account generation and cannot cross a switch`() = runTest {
        val factory = FakeFactory()
        factory.next.blocking = PeerBlockingState(true, listOf("peer@example.org"))
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val firstLease = requireNotNull(controller.lifecycle.value.dispatchLease())
        val first = factory.created.single()

        assertEquals(
            PeerBlockingState(true, listOf("peer@example.org")),
            controller.peerBlockingState(
                firstLease.identity.accountId,
                firstLease.identity.generation,
                "peer@example.org",
            ),
        )
        assertEquals(listOf("peer@example.org"), first.blockingQueries)

        controller.switchTo(account("second"), "secret".toCharArray()) {}
        val stale = controller.setPeerBlocked(
            firstLease.identity.accountId,
            firstLease.identity.generation,
            "peer@example.org",
            true,
        )

        assertEquals(PeerBlockingMutationResult.NotAttempted, stale)
        assertTrue(first.blockingMutations.isEmpty())
        assertTrue(factory.created.last().blockingMutations.isEmpty())
    }

    @Test
    fun `durable message and roster handlers accept only current session attempt`() = runTest {
        val received = mutableListOf<IncomingMessageEnvelope>()
        val rosters = mutableListOf<String>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            durableEvent = { event ->
                if (event is SessionEvent.Incoming) received += event.message
                if (event is SessionEvent.RosterSnapshot) rosters += event.snapshot.accountId
            },
            retryWait = {},
        )
        controller.start(account("first"), "secret".toCharArray())
        val first = factory.created.single()
        val firstAttempt = first.attemptIdentity

        first.emitIncoming("current")
        first.emitRoster(firstAttempt)
        advanceUntilIdle()
        controller.switchTo(account("second"), "secret".toCharArray()) {}
        first.emitIncoming("stale", firstAttempt)
        first.emitRoster(firstAttempt)
        val second = factory.created.last()
        second.emitIncoming("replacement")
        second.emitRoster(second.attemptIdentity)
        advanceUntilIdle()

        assertEquals(listOf("current", "replacement"), received.map(IncomingMessageEnvelope::body))
        assertEquals(listOf("first", "second"), rosters)
    }

    @Test
    fun `durable protocol failure accepts only current session attempt`() = runTest {
        val failures = mutableListOf<OutgoingFailureEnvelope>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            durableEvent = { event ->
                if (event is SessionEvent.OutgoingFailure) failures += event.failure
            },
            retryWait = {},
        )
        controller.start(account("first"), "secret".toCharArray())
        val first = factory.created.single()
        val staleAttempt = first.attemptIdentity

        first.emitFailure("current")
        controller.switchTo(account("second"), "secret".toCharArray()) {}
        first.emitFailure("stale", staleAttempt)
        factory.created.last().emitFailure("replacement")
        advanceUntilIdle()

        assertEquals(listOf("current", "replacement"), failures.map(OutgoingFailureEnvelope::operationId))
    }

    @Test
    fun `blocking inbound gate commits messages in callback order`() = runTest {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondCalling = CompletableDeferred<Unit>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            durableEvent = { event ->
                if (event is SessionEvent.Incoming) {
                    order += event.message.body
                    if (event.message.body == "A") {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                }
            },
            retryWait = {},
        )
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        val first = launch(Dispatchers.Default) { connection.emitIncoming("A") }
        firstEntered.await()
        val second = launch(Dispatchers.Default) {
            secondCalling.complete(Unit)
            connection.emitIncoming("B")
        }
        secondCalling.await()
        yield()
        assertEquals(listOf("A"), order.toList())

        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("A", "B"), order.toList())
    }

    @Test
    fun `blocked admitted inbound cannot delay owner and lease fencing`() = runTest {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            durableEvent = { event ->
                if (event is SessionEvent.Incoming) {
                    persistenceStarted.complete(Unit)
                    releasePersistence.await()
                    persisted += event.message.body
                }
            },
            retryWait = {},
        )
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        val incoming = launch(Dispatchers.Default) { connection.emitIncoming("admitted") }
        persistenceStarted.await()

        val stop = async { controller.stop() }
        runCurrent()

        try {
            assertTrue(controller.state.value is ConnectionState.Disconnecting)
            assertEquals(null, controller.lifecycle.value.dispatchLease())
            assertFalse(stop.isCompleted)
        } finally {
            releasePersistence.complete(Unit)
            incoming.join()
            stop.await()
        }

        assertEquals(listOf("admitted"), persisted)
        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test
    fun `archive commit linearizes before retirement and stale lease cannot enter`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            retryWait = {},
        )
        controller.start(account("first"), "secret".toCharArray())
        val identity = requireNotNull(controller.lifecycle.value.dispatchLease()).identity

        val commit = async(Dispatchers.Default) {
            controller.commitIfConnected(identity, isAuthoritative = { true }) {
                entered.complete(Unit)
                release.await()
                "committed"
            }
        }
        entered.await()
        val stop = async(Dispatchers.Default) { controller.stop() }
        yield()
        assertFalse(stop.isCompleted)

        release.complete(Unit)
        assertEquals("committed", commit.await())
        stop.await()

        var staleEntered = false
        val stale = controller.commitIfConnected(identity, isAuthoritative = { true }) {
            staleEntered = true
        }
        assertEquals(null, stale)
        assertFalse(staleEntered)
    }

    @Test
    fun `inbound persistence failure poisons owner and rejects later callback`() = runTest {
        val received = mutableListOf<String>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            durableEvent = { event ->
                if (event is SessionEvent.Incoming) {
                    if (event.message.body == "bad") error("injected storage failure")
                    received += event.message.body
                }
            },
            retryWait = {},
        )
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        connection.emitIncoming("bad")
        connection.emitIncoming("later")
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(1),
                SessionFailureReason.LOCAL_STORAGE,
            ),
            controller.state.value,
        )
        assertEquals(1, connection.disconnectCalls)
    }

    @Test
    fun `loss during connect cannot be erased by late success`() = runTest {
        val factory = FakeFactory().apply {
            next.emitLossDuringConnect = true
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})

        controller.start(account("first"), "secret".toCharArray())
        advanceUntilIdle()

        assertEquals(1, factory.created.single().reconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)
    }

    @Test
    fun `every reconnect advances generation`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        connection.emitLoss()
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)

        connection.emitLoss()
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(3)), controller.state.value)
        assertEquals(generation(3), connection.attemptIdentity.generation)
    }

    @Test
    fun `accepted loss retires exact dispatch lease before replacement connects`() = runTest {
        val retired = mutableListOf<DispatchLease>()
        var settlements = 0
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            retryWait = {},
            revokeDispatch = { lease ->
                retired += lease
                object : RevokedDispatch {
                    override fun freezeUnknownEntry() = Unit

                    override suspend fun finish(): DispatchRevocationResult {
                        settlements++
                        return DispatchRevocationResult.COMPLETE
                    }
                }
            },
        )
        controller.start(account("first"), "secret".toCharArray())
        val oldLease = requireNotNull(controller.lifecycle.value.dispatchLease())

        factory.created.single().emitLoss()
        advanceUntilIdle()

        assertEquals(listOf(oldLease), retired)
        assertEquals(1, settlements)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)
        assertEquals(generation(2), controller.lifecycle.value.dispatchLease()?.identity?.generation)
    }

    @Test
    fun `accepted loss withdraws lifecycle authority before revoking dispatch`() = runTest {
        val lifecycleAtRevocation = lifecycleAtDispatchRevocation { _, factory ->
            factory.created.single().emitLoss()
        }

        assertTrue(lifecycleAtRevocation.state is ConnectionState.ReconnectWait)
        assertEquals(null, lifecycleAtRevocation.dispatchLease())
    }

    @Test
    fun `terminal stop withdraws lifecycle authority before revoking dispatch`() = runTest {
        val lifecycleAtRevocation = lifecycleAtDispatchRevocation { controller, _ ->
            controller.stop()
        }

        assertTrue(lifecycleAtRevocation.state is ConnectionState.Disconnecting)
        assertEquals(null, lifecycleAtRevocation.dispatchLease())
    }

    @Test
    fun `early retained storage failure settles exact dispatch once`() = runTest {
        var revocations = 0
        var finishes = 0
        val attachment = object : RevokedDispatch {
            override fun freezeUnknownEntry() = Unit

            override suspend fun finish(): DispatchRevocationResult {
                finishes++
                return DispatchRevocationResult.LOCAL_STORAGE_FAILED
            }
        }
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            retryWait = {},
            revokeDispatch = {
                revocations++
                attachment
            },
        )
        controller.start(account("first"), "secret".toCharArray())

        factory.created.single().emitLoss()
        advanceUntilIdle()

        assertEquals(1, finishes)
        assertEquals(1, revocations)
        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(1),
                SessionFailureReason.LOCAL_STORAGE,
            ),
            controller.state.value,
        )
    }

    @Test
    fun `late retained storage failure after reconnect deadline fails current owner`() = runTest {
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        var finishes = 0
        var freezes = 0
        var revocations = 0
        val attachment = object : RevokedDispatch {
            override fun freezeUnknownEntry() {
                freezes++
            }

            override suspend fun finish(): DispatchRevocationResult {
                finishes++
                finishStarted.complete(Unit)
                withContext(NonCancellable) { releaseFinish.await() }
                return DispatchRevocationResult.LOCAL_STORAGE_FAILED
            }
        }
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            teardownTimeoutMillis = 100,
            nowMillis = { testScheduler.currentTime },
            revokeDispatch = { if (revocations++ == 0) attachment else null },
        )
        controller.start(account("first"), "secret".toCharArray())

        factory.created.single().emitLoss()
        finishStarted.await()
        advanceTimeBy(101)
        runCurrent()

        val stateAtDeadline = controller.state.value
        releaseFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, freezes)
        assertEquals(1, finishes)
        assertEquals(
            ConnectionState.Connected(AccountId.require("first"), generation(2)),
            stateAtDeadline,
        )
        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(2),
                SessionFailureReason.LOCAL_STORAGE,
            ),
            controller.state.value,
        )
    }

    @Test
    fun `late retained completion after reconnect deadline keeps replacement connected`() = runTest {
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        var finishes = 0
        var freezes = 0
        val attachment = object : RevokedDispatch {
            override fun freezeUnknownEntry() {
                freezes++
            }

            override suspend fun finish(): DispatchRevocationResult {
                finishes++
                finishStarted.complete(Unit)
                withContext(NonCancellable) { releaseFinish.await() }
                return DispatchRevocationResult.COMPLETE
            }
        }
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            teardownTimeoutMillis = 100,
            nowMillis = { testScheduler.currentTime },
            revokeDispatch = { attachment },
        )
        controller.start(account("first"), "secret".toCharArray())

        factory.created.single().emitLoss()
        finishStarted.await()
        advanceTimeBy(101)
        runCurrent()

        val replacementState = ConnectionState.Connected(AccountId.require("first"), generation(2))
        assertEquals(replacementState, controller.state.value)
        assertEquals(1, freezes)

        releaseFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, finishes)
        assertEquals(replacementState, controller.state.value)
        controller.destroy()
    }

    @Test
    fun `late retained storage failure cannot affect switched owner`() = runTest {
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        var finishes = 0
        val attachment = object : RevokedDispatch {
            override fun freezeUnknownEntry() = Unit

            override suspend fun finish(): DispatchRevocationResult {
                finishes++
                finishStarted.complete(Unit)
                releaseFinish.await()
                return DispatchRevocationResult.LOCAL_STORAGE_FAILED
            }
        }
        var revocations = 0
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            teardownTimeoutMillis = 100,
            nowMillis = { testScheduler.currentTime },
            revokeDispatch = { if (revocations++ == 0) attachment else null },
        )
        controller.start(account("first"), "first-secret".toCharArray())

        factory.created.single().emitLoss()
        finishStarted.await()
        advanceTimeBy(101)
        runCurrent()
        controller.switchTo(account("second"), "second-secret".toCharArray()) {}
        val replacement = factory.created.last()
        val replacementState = ConnectionState.Connected(AccountId.require("second"), generation(3))

        releaseFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(replacementState, controller.state.value)
        assertEquals(1, finishes)
        assertEquals(0, replacement.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `loss during active reconnect blocks late success for that attempt`() = runTest {
        val reconnectStarted = CompletableDeferred<Unit>()
        val reconnectGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.reconnectStarted = reconnectStarted
            next.reconnectGate = reconnectGate
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        connection.emitLoss()
        reconnectStarted.await()
        val reconnectAttempt = connection.attemptIdentity
        connection.emitLoss(reconnectAttempt)
        runCurrent()
        reconnectGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, connection.reconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(3)), controller.state.value)
    }

    @Test
    fun `delayed close from completed attempt cannot reconnect current attempt`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        val firstAttempt = connection.attemptIdentity

        connection.emitLoss(firstAttempt)
        advanceUntilIdle()
        val secondAttempt = connection.attemptIdentity
        assertNotEquals(firstAttempt, secondAttempt)

        connection.emitLoss(firstAttempt)
        advanceUntilIdle()

        assertEquals(1, connection.reconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)
    }

    @Test
    fun `duplicate close from current attempt schedules one reconnect`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        val attempt = connection.attemptIdentity

        connection.emitLoss(attempt)
        connection.emitLoss(attempt)
        advanceUntilIdle()

        assertEquals(1, connection.reconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)
    }

    @Test
    fun `repeated deterministic protocol loss opens circuit without reconnect`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        val attempt = connection.attemptIdentity

        connection.emitLoss(attempt, SessionFailureReason.PROTOCOL)
        connection.emitLoss(attempt, SessionFailureReason.PROTOCOL)
        advanceUntilIdle()

        assertEquals(0, connection.reconnectCalls)
        assertEquals(1, connection.disconnectCalls)
        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(1),
                SessionFailureReason.PROTOCOL,
            ),
            controller.state.value,
        )
    }

    @Test
    fun `explicit fresh start recovers after protocol input is corrected`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val failed = factory.created.single()

        failed.emitLoss(reason = SessionFailureReason.PROTOCOL)
        advanceUntilIdle()
        controller.start(account("first"), "secret".toCharArray())

        assertEquals(2, factory.created.size)
        assertEquals(0, failed.reconnectCalls)
        assertEquals(1, failed.disconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)
    }

    @Test
    fun `controller passes one plaintext array and reconnect uses Smack retained credential`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        val credential = "secret".toCharArray()

        controller.start(account("first"), credential)
        val connection = factory.created.single()
        assertSame(credential, connection.connectCredential)
        credential.fill('\u0000')

        connection.emitLoss()
        advanceUntilIdle()

        assertEquals(1, connection.reconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(2)), controller.state.value)
    }

    @Test
    fun `switch stops old owner before persistence and rejects stale callback`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val oldConnection = factory.created.single()
        val oldAttempt = oldConnection.attemptIdentity
        var oldWasClosedWhenPersisted = false

        controller.switchTo(account("second"), "second-secret".toCharArray()) {
            oldWasClosedWhenPersisted = oldConnection.closed
        }
        oldConnection.emitLoss(oldAttempt)
        advanceUntilIdle()

        assertTrue(oldWasClosedWhenPersisted)
        assertEquals(ConnectionState.Connected(AccountId.require("second"), generation(2)), controller.state.value)
        assertEquals(0, oldConnection.reconnectCalls)
    }

    @Test
    fun `switching state fences old dispatch until teardown then persists and starts target`() = runTest {
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            retryWait = {},
            revokeDispatch = {
                object : RevokedDispatch {
                    override fun freezeUnknownEntry() = Unit

                    override suspend fun finish(): DispatchRevocationResult {
                        effects += "dispatch"
                        finishStarted.complete(Unit)
                        releaseFinish.await()
                        return DispatchRevocationResult.COMPLETE
                    }
                }
            },
        )
        controller.start(account("first"), "first-secret".toCharArray())

        val switching = async {
            controller.switchTo(
                configuration = account("second"),
                credential = "second-secret".toCharArray(),
                switchingFrom = AccountId.require("first"),
                persistActive = { effects += "persist" },
            )
        }
        finishStarted.await()

        assertEquals(
            ConnectionState.Switching(AccountId.require("first"), AccountId.require("second")),
            controller.state.value,
        )
        assertEquals(listOf("dispatch"), effects)
        assertEquals(1, factory.created.size)
        assertFalse(switching.isCompleted)

        releaseFinish.complete(Unit)
        switching.await()

        assertEquals(listOf("dispatch", "persist"), effects)
        assertEquals(2, factory.created.size)
        assertEquals(ConnectionState.Connected(AccountId.require("second"), generation(2)), controller.state.value)
    }

    @Test
    fun `failed active account commit cannot start target generation`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())

        val failure = runCatching {
            controller.switchTo(
                configuration = account("second"),
                credential = "second-secret".toCharArray(),
                switchingFrom = AccountId.require("first"),
                persistActive = { error("commit failed") },
            )
        }.exceptionOrNull()

        assertEquals("commit failed", failure?.message)
        assertEquals(1, factory.created.size)
        assertTrue(factory.created.single().closed)
        assertEquals(
            ConnectionState.Switching(AccountId.require("first"), AccountId.require("second")),
            controller.state.value,
        )
    }

    @Test
    fun `cancellation before active account commit cannot start target generation`() = runTest {
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        var persisted = false
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            scope = this,
            factory = factory,
            retryWait = {},
            revokeDispatch = {
                object : RevokedDispatch {
                    override fun freezeUnknownEntry() = Unit

                    override suspend fun finish(): DispatchRevocationResult {
                        finishStarted.complete(Unit)
                        releaseFinish.await()
                        return DispatchRevocationResult.COMPLETE
                    }
                }
            },
        )
        controller.start(account("first"), "first-secret".toCharArray())

        val switch = launch {
            controller.switchTo(
                configuration = account("second"),
                credential = "second-secret".toCharArray(),
                switchingFrom = AccountId.require("first"),
                persistActive = { persisted = true },
            )
        }
        finishStarted.await()
        switch.cancelAndJoin()

        assertFalse(persisted)
        assertEquals(1, factory.created.size)
        releaseFinish.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `cancellation after active account commit leaves target durable without starting generation`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        var persisted = false

        val switch = launch {
            controller.switchTo(
                configuration = account("second"),
                credential = "second-secret".toCharArray(),
                switchingFrom = AccountId.require("first"),
                persistActive = {
                    persisted = true
                    currentCoroutineContext()[Job]?.cancel()
                },
            )
        }
        switch.join()

        assertTrue(persisted)
        assertTrue(switch.isCancelled)
        assertEquals(1, factory.created.size)
        assertEquals(
            ConnectionState.Switching(AccountId.require("first"), AccountId.require("second")),
            controller.state.value,
        )
    }

    @Test
    fun `blocked send does not hold controller lock or mutate replacement`() = runTest {
        val sendStarted = CompletableDeferred<Unit>()
        val sendGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.sendStarted = sendStarted
            next.sendGate = sendGate
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val oldConnection = factory.created.single()

        val send = async { controller.send(outgoing("first", 1)) {} }
        sendStarted.await()

        val replacement = async {
            controller.switchTo(account("second"), "second-secret".toCharArray()) {}
        }
        runCurrent()

        assertTrue(replacement.isCompleted)
        replacement.await()
        assertFalse(send.isCompleted)
        val replacementState = ConnectionState.Connected(AccountId.require("second"), generation(2))
        assertEquals(replacementState, controller.state.value)
        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(0, factory.created.last().disconnectCalls)

        sendGate.complete(Unit)
        send.await()

        assertEquals(replacementState, controller.state.value)
        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(0, factory.created.last().disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `entered blocking retains correlated result after account switch`() = runTest {
        val blockingStarted = CompletableDeferred<Unit>()
        val blockingGate = CompletableDeferred<Unit>()
        val confirmed = PeerBlockingMutationResult.Confirmed(
            PeerBlockingState(true, listOf("peer@example.org")),
        )
        val factory = FakeFactory().apply {
            next.blockingStarted = blockingStarted
            next.blockingGate = blockingGate
            next.blockingResult = confirmed
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val lease = requireNotNull(controller.lifecycle.value.dispatchLease())

        val mutation = async {
            controller.setPeerBlocked(
                lease.identity.accountId,
                lease.identity.generation,
                "peer@example.org",
                true,
            )
        }
        blockingStarted.await()
        controller.switchTo(account("second"), "second-secret".toCharArray()) {}

        assertFalse(mutation.isCompleted)
        val replacementState = ConnectionState.Connected(AccountId.require("second"), generation(2))
        assertEquals(replacementState, controller.state.value)

        blockingGate.complete(Unit)
        assertEquals(confirmed, mutation.await())
        assertEquals(replacementState, controller.state.value)
        assertTrue(factory.created.last().blockingMutations.isEmpty())
        controller.destroy()
    }

    @Test
    fun `stop and destruction cancel reconnect and release connection once`() = runTest {
        val retryGate = RetryGate()
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = retryGate::await)
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        connection.emitLoss()
        advanceUntilIdle()
        assertTrue(controller.state.value is ConnectionState.ReconnectWait)

        controller.destroy()
        retryGate.release()
        advanceUntilIdle()

        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertEquals(0, connection.reconnectCalls)
        assertFalse(connection.credentialRetained)
    }

    @Test
    fun `destruction cancels reconnect blocked inside transport`() = runTest {
        val reconnectStarted = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.reconnectStarted = reconnectStarted
            next.blockReconnect = true
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        connection.emitLoss()
        reconnectStarted.await()

        val destruction = async { controller.destroy() }
        advanceUntilIdle()

        assertTrue(destruction.isCompleted)
        destruction.await()
        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
    }

    @Test
    fun `stuck reconnect and disconnect share one teardown budget`() = runTest {
        val reconnectStarted = CompletableDeferred<Unit>()
        val reconnectGate = CompletableDeferred<Unit>()
        val disconnectStarted = CompletableDeferred<Unit>()
        val disconnectGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.reconnectStarted = reconnectStarted
            next.reconnectGate = reconnectGate
            next.ignoreReconnectCancellation = true
            next.disconnectStarted = disconnectStarted
            next.disconnectGate = disconnectGate
            next.ignoreDisconnectCancellation = true
        }
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            teardownTimeoutMillis = 100,
        )
        controller.start(account("first"), "secret".toCharArray())
        val oldConnection = factory.created.single()
        oldConnection.emitLoss()
        reconnectStarted.await()

        val stop = async { controller.stop() }
        runCurrent()

        assertTrue(disconnectStarted.isCompleted)
        assertTrue(controller.state.value is ConnectionState.Disconnecting)
        assertFalse(stop.isCompleted)
        advanceTimeBy(101)
        runCurrent()
        stop.await()
        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, oldConnection.disconnectCalls)

        controller.start(account("second"), "second-secret".toCharArray())
        val replacementState = ConnectionState.Connected(AccountId.require("second"), generation(3))
        assertEquals(replacementState, controller.state.value)

        reconnectGate.complete(Unit)
        disconnectGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(replacementState, controller.state.value)
        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(0, factory.created.last().disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `cancellation ignoring reconnect cannot reopen revoked transport after replacement`() = runTest {
        val reconnectStarted = CompletableDeferred<Unit>()
        val reconnectGate = CompletableDeferred<Unit>()
        val disconnectStarted = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.reconnectStarted = reconnectStarted
            next.reconnectGate = reconnectGate
            next.ignoreReconnectCancellation = true
            next.reopenAfterReconnect = true
            next.disconnectStarted = disconnectStarted
        }
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            teardownTimeoutMillis = 100,
        )
        controller.start(account("first"), "secret".toCharArray())
        val oldConnection = factory.created.single()
        oldConnection.emitLoss()
        reconnectStarted.await()

        val stop = async { controller.stop() }
        disconnectStarted.await()
        advanceTimeBy(101)
        runCurrent()
        stop.await()
        controller.start(account("second"), "second-secret".toCharArray())
        val replacementState = ConnectionState.Connected(AccountId.require("second"), generation(3))

        reconnectGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(oldConnection.closed)
        assertFalse(oldConnection.isUsable)
        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(replacementState, controller.state.value)
        assertEquals(0, factory.created.last().disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `teardown survives cancellation of initiating stop command`() = runTest {
        val disconnectStarted = CompletableDeferred<Unit>()
        val disconnectGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.disconnectStarted = disconnectStarted
            next.disconnectGate = disconnectGate
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        val stop = launch { controller.stop() }
        disconnectStarted.await()
        stop.cancelAndJoin()
        assertTrue(controller.state.value is ConnectionState.Disconnecting)

        disconnectGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertFalse(connection.credentialRetained)
        controller.destroy()
    }

    @Test
    fun `repeated stop reuses revocation and retained storage failure wins cancellation`() = runTest {
        val finishStarted = CompletableDeferred<Unit>()
        val releaseFinish = CompletableDeferred<Unit>()
        var revocations = 0
        var finishes = 0
        val attachment = object : RevokedDispatch {
            override fun freezeUnknownEntry() = Unit

            override suspend fun finish(): DispatchRevocationResult {
                finishes++
                finishStarted.complete(Unit)
                releaseFinish.await()
                return DispatchRevocationResult.LOCAL_STORAGE_FAILED
            }
        }
        val factory = FakeFactory()
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            revokeDispatch = {
                revocations++
                attachment
            },
        )
        controller.start(account("first"), "secret".toCharArray())
        val connected = controller.state.value as ConnectionState.Connected

        val firstStop = launch { controller.stop() }
        finishStarted.await()
        val fenced = controller.lifecycle.value
        val repeatedStop = launch { controller.stop() }
        val destruction = launch { controller.destroy() }
        runCurrent()

        assertEquals(fenced, controller.lifecycle.value)
        assertEquals(1, revocations)
        assertEquals(1, factory.created.single().disconnectCalls)
        firstStop.cancelAndJoin()
        releaseFinish.complete(Unit)
        repeatedStop.join()
        destruction.join()
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Failed(
                connected.accountId,
                connected.generation,
                SessionFailureReason.LOCAL_STORAGE,
            ),
            controller.state.value,
        )
        assertEquals(1, revocations)
        assertEquals(1, finishes)
        assertEquals(1, factory.created.single().disconnectCalls)
        withTimeout(100) { controllerJob(controller).join() }
    }

    @Test
    fun `current outbox storage failure detaches and closes exact owner once`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val observed = controller.state.value as ConnectionState.Connected
        val observation = controller.lifecycle.value
        val connection = factory.created.single()

        controller.outboxStorageFailed(observed.accountId, observed.generation, observation)
        controller.outboxStorageFailed(observed.accountId, observed.generation, observation)

        assertEquals(
            ConnectionState.Failed(
                observed.accountId,
                observed.generation,
                SessionFailureReason.LOCAL_STORAGE,
            ),
            controller.state.value,
        )
        assertEquals(1, connection.disconnectCalls)
        assertFalse(connection.credentialRetained)
        controller.destroy()
    }

    @Test
    fun `terminal local storage cleanup drops credential bearing owner and destroy completes`() = runTest {
        val factory = FakeFactory().apply {
            next.retainCredentialAfterDisconnect = true
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val observed = controller.state.value as ConnectionState.Connected
        val observation = controller.lifecycle.value
        val connection = factory.created.single()

        controller.outboxStorageFailed(observed.accountId, observed.generation, observation)

        val failed = ConnectionState.Failed(
            observed.accountId,
            observed.generation,
            SessionFailureReason.LOCAL_STORAGE,
        )
        assertEquals(failed, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertTrue(connection.credentialRetained)
        assertEquals(null, controllerField(controller, "current"))
        assertTrue((controllerField(controller, "revocations") as Map<*, *>).isEmpty())
        assertEquals(null, controllerField(controller, "latestRevocation"))

        controller.stop()
        assertEquals(failed, controller.state.value)
        controller.destroy()
        withTimeout(100) { controllerJob(controller).join() }
    }

    @Test
    fun `retired terminal storage observation cannot affect replacement`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val oldState = controller.state.value as ConnectionState.Connected
        val oldObservation = controller.lifecycle.value
        val oldConnection = factory.created.single()
        controller.outboxStorageFailed(oldState.accountId, oldState.generation, oldObservation)
        assertTrue((controllerField(controller, "revocations") as Map<*, *>).isEmpty())

        controller.start(account("second"), "second-secret".toCharArray())
        val replacement = factory.created.last()
        val replacementState = controller.state.value
        controller.outboxStorageFailed(oldState.accountId, oldState.generation, oldObservation)

        assertEquals(replacementState, controller.state.value)
        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(0, replacement.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `delayed old generation storage failure cannot affect replacement`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val oldState = controller.state.value as ConnectionState.Connected
        val oldObservation = controller.lifecycle.value
        val oldConnection = factory.created.single()
        controller.switchTo(account("second"), "second-secret".toCharArray()) {}
        val replacement = factory.created.last()
        val replacementState = controller.state.value

        controller.outboxStorageFailed(oldState.accountId, oldState.generation, oldObservation)

        assertEquals(replacementState, controller.state.value)
        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(0, replacement.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `completed detached storage failure is stale and cannot close again`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connected = controller.state.value as ConnectionState.Connected
        val connection = factory.created.single()
        controller.stop()
        val observation = controller.lifecycle.value

        controller.outboxStorageFailed(connected.accountId, connected.generation, observation)
        controller.outboxStorageFailed(connected.accountId, connected.generation, observation)

        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `delayed detached failure cannot overwrite replacement at same terminal state`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val firstState = controller.state.value as ConnectionState.Connected
        val first = factory.created.single()
        controller.stop()
        val observation = controller.lifecycle.value

        controller.start(account("second"), "second-secret".toCharArray())
        val replacement = factory.created.last()
        controller.stop()
        val replacementObservation = controller.lifecycle.value

        controller.outboxStorageFailed(
            firstState.accountId,
            firstState.generation,
            observation,
        )

        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(replacementObservation, controller.lifecycle.value)
        assertEquals(
            SessionIdentity(AccountId.require("second"), generation(2)),
            replacementObservation.owner,
        )
        assertNotEquals(observation, replacementObservation)
        assertEquals(1, first.disconnectCalls)
        assertEquals(1, replacement.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `stale reconnect failure cannot overwrite stopped epoch`() = runTest {
        val reconnectStarted = CompletableDeferred<Unit>()
        val reconnectGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.reconnectStarted = reconnectStarted
            next.reconnectGate = reconnectGate
            next.ignoreReconnectCancellation = true
            next.reconnectFailure = SessionFailure(SessionFailureReason.TLS_CERTIFICATE)
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        connection.emitLoss()
        reconnectStarted.await()

        val stop = async { controller.stop() }
        runCurrent()
        assertTrue(controller.state.value is ConnectionState.Disconnecting)
        reconnectGate.complete(Unit)
        stop.await()
        advanceUntilIdle()

        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `terminal reconnect failure retains generation for foreground release`() = runTest {
        val factory = FakeFactory().apply {
            next.reconnectFailure = SessionFailure(SessionFailureReason.AUTHENTICATION)
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        connection.emitLoss()
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(2),
                SessionFailureReason.AUTHENTICATION,
            ),
            controller.state.value,
        )
        assertEquals(1, connection.disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `switch after cancelled switch reuses in-flight teardown`() = runTest {
        val disconnectStarted = CompletableDeferred<Unit>()
        val disconnectGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.disconnectStarted = disconnectStarted
            next.disconnectGate = disconnectGate
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val oldConnection = factory.created.single()

        val cancelledSwitch = launch {
            controller.switchTo(account("second"), "second-secret".toCharArray()) {}
        }
        disconnectStarted.await()
        cancelledSwitch.cancelAndJoin()

        val switch = async {
            controller.switchTo(account("second"), "second-secret".toCharArray()) {}
        }
        runCurrent()
        assertFalse(switch.isCompleted)
        disconnectGate.complete(Unit)
        switch.await()

        assertEquals(1, oldConnection.disconnectCalls)
        assertEquals(2, factory.created.size)
        assertEquals(ConnectionState.Connected(AccountId.require("second"), generation(2)), controller.state.value)
        controller.destroy()
    }

    @Test
    fun `teardown timeout releases ownership and reaches stopped`() = runTest {
        val disconnectStarted = CompletableDeferred<Unit>()
        val disconnectGate = CompletableDeferred<Unit>()
        val factory = FakeFactory().apply {
            next.disconnectStarted = disconnectStarted
            next.disconnectGate = disconnectGate
            next.ignoreDisconnectCancellation = true
        }
        val controller = ActiveSessionController(
            this,
            factory,
            retryWait = {},
            teardownTimeoutMillis = 100,
        )
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        val stop = async { controller.stop() }
        disconnectStarted.await()
        advanceTimeBy(101)
        runCurrent()
        stop.await()

        assertEquals(ConnectionState.Stopped, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertFalse(connection.credentialRetained)

        controller.start(account("second"), "second-secret".toCharArray())
        val replacementState = ConnectionState.Connected(AccountId.require("second"), generation(2))
        disconnectGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(replacementState, controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertEquals(0, factory.created.last().disconnectCalls)
        controller.destroy()
    }

    @Test
    fun `stopped process owner can start reconnect and stop a new service generation`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "first-secret".toCharArray())
        val first = factory.created.single()
        controller.stop()

        controller.start(account("second"), "second-secret".toCharArray())
        val second = factory.created.last()
        second.emitLoss()
        advanceUntilIdle()

        assertEquals(1, first.disconnectCalls)
        assertEquals(1, second.reconnectCalls)
        assertEquals(ConnectionState.Connected(AccountId.require("second"), generation(3)), controller.state.value)

        controller.stop()
        assertEquals(1, second.disconnectCalls)
        assertEquals(ConnectionState.Stopped, controller.state.value)
        controller.destroy()
    }

    @Test
    fun `terminal TLS failure never authenticates and exposes only safe reason`() = runTest {
        val factory = FakeFactory().apply {
            next.connectFailure = SessionFailure(SessionFailureReason.TLS_CERTIFICATE)
        }
        val controller = ActiveSessionController(this, factory, retryWait = {})

        controller.start(account("first"), "secret".toCharArray())

        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(1),
                SessionFailureReason.TLS_CERTIFICATE,
            ),
            controller.state.value,
        )
        assertEquals(0, factory.created.single().authenticationCalls)
    }

    @Test
    fun `success from an unhealthy transport never becomes connected`() = runTest {
        val factory = FakeFactory().apply { next.usableAfterConnect = false }
        val controller = ActiveSessionController(this, factory, retryWait = {})

        controller.start(account("first"), "secret".toCharArray())

        assertTrue(controller.state.value is ConnectionState.ReconnectWait)
    }

    @Test
    fun `each failed fresh reconnect receives a new generation`() = runTest {
        val factory = FakeFactory().apply { next.usableAfterReconnect = false }
        val waits = mutableListOf<Int>()
        val controller = ActiveSessionController(this, factory, retryWait = { waits += it })
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        connection.emitLoss()
        advanceUntilIdle()

        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(6),
                SessionFailureReason.RETRY_EXHAUSTED,
            ),
            controller.state.value,
        )
        assertEquals(generation(6), connection.attemptIdentity.generation)
        assertEquals(listOf(1, 2, 3, 4, 5), waits)
    }

    @Test
    fun `loss emitted by final health check is preserved across fresh identity`() = runTest {
        val factory = FakeFactory().apply { next.emitLossOnReconnectHealthCheck = 1 }
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        connection.emitLoss()
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected(AccountId.require("first"), generation(3)), controller.state.value)
        assertEquals(2, connection.reconnectCalls)
    }

    @Test
    fun `missing replacement credential clears the live owner`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()

        val replacement = AccountId.require("second")
        controller.requireCredentials(replacement)

        assertEquals(ConnectionState.NeedsCredentials(replacement), controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertFalse(connection.credentialRetained)
    }

    @Test
    fun `service destruction preserves credential target after fencing active owner`() = runTest {
        val factory = FakeFactory()
        val controller = ActiveSessionController(this, factory, retryWait = {})
        controller.start(account("first"), "secret".toCharArray())
        val connection = factory.created.single()
        val replacement = AccountId.require("second")
        controller.requireCredentials(replacement)

        stopServiceRuntime(
            cancelCommands = {},
            stopRuntime = controller::serviceDestroyed,
        )

        assertEquals(ConnectionState.NeedsCredentials(replacement), controller.state.value)
        assertEquals(1, connection.disconnectCalls)
        assertFalse(connection.credentialRetained)
    }

    @Test
    fun `factory failure is an explicit configuration failure`() = runTest {
        val factory = FakeFactory().apply { createFailure = IllegalArgumentException("bad endpoint") }
        val controller = ActiveSessionController(this, factory, retryWait = {})

        controller.start(account("first"), "secret".toCharArray())

        assertEquals(
            ConnectionState.Failed(
                AccountId.require("first"),
                generation(1),
                SessionFailureReason.CONFIGURATION,
            ),
            controller.state.value,
        )
        assertTrue(factory.created.isEmpty())
    }

    private fun account(id: String) = AccountConfiguration.create(
        id = AccountId.require(id),
        bareJid = "$id@example.org",
        authenticationId = id,
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private suspend fun TestScope.lifecycleAtDispatchRevocation(
        trigger: suspend (ActiveSessionController, FakeFactory) -> Unit,
    ): SessionLifecycleObservation {
        val factory = FakeFactory()
        lateinit var controller: ActiveSessionController
        var lifecycleAtRevocation: SessionLifecycleObservation? = null
        controller = ActiveSessionController(
            scope = this,
            factory = factory,
            retryWait = {},
            revokeDispatch = {
                lifecycleAtRevocation = controller.lifecycle.value
                null
            },
        )
        controller.start(account("first"), "secret".toCharArray())

        trigger(controller, factory)
        advanceUntilIdle()

        return requireNotNull(lifecycleAtRevocation)
    }

    private fun generation(value: Long) = org.thanosapollo.nema.xmpp.transport.ConnectionGeneration.require(value)

    private fun controllerField(controller: ActiveSessionController, name: String): Any? =
        controller.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(controller)
        }

    private fun controllerJob(controller: ActiveSessionController): Job =
        controllerField(controller, "controllerJob") as Job

    private fun outgoing(accountId: String, generation: Long) = OutgoingMessageEnvelope(
        accountId = AccountId.require(accountId),
        generation = generation(generation),
        attempt = 1,
        operationId = "operation",
        originId = "origin",
        recipient = "peer@example.org",
        body = "body",
        thread = null,
    )

    private class RetryGate {
        private var continuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

        suspend fun await(attempt: Int) {
            assertEquals(1, attempt)
            kotlinx.coroutines.suspendCancellableCoroutine { continuation = it }
        }

        fun release() {
            continuation?.resume(Unit) { _, _, _ -> }
        }
    }

    private class FakeFactory : SessionConnectionFactory {
        var next = FakeConnection()
        var createFailure: Exception? = null
        val created = mutableListOf<FakeConnection>()

        override fun create(
            configuration: AccountConfiguration,
            identity: SessionIdentity,
            event: (SessionEvent) -> Unit,
        ): SessionConnection {
            createFailure?.let { throw it }
            return next.also {
                it.event = event
                created += it
                next = FakeConnection()
            }
        }
    }

    private class FakeConnection : SessionConnection {
        lateinit var attemptIdentity: SessionAttemptIdentity
        lateinit var event: (SessionEvent) -> Unit
        var emitLossDuringConnect = false
        var connectFailure: SessionFailure? = null
        var reconnectStarted: CompletableDeferred<Unit>? = null
        var reconnectGate: CompletableDeferred<Unit>? = null
        var reconnectFailure: SessionFailure? = null
        var ignoreReconnectCancellation = false
        var blockReconnect = false
        var reopenAfterReconnect = false
        var disconnectStarted: CompletableDeferred<Unit>? = null
        var disconnectGate: CompletableDeferred<Unit>? = null
        var ignoreDisconnectCancellation = false
        var sendStarted: CompletableDeferred<Unit>? = null
        var sendGate: CompletableDeferred<Unit>? = null
        var blocking = PeerBlockingState(supported = false)
        var blockingStarted: CompletableDeferred<Unit>? = null
        var blockingGate: CompletableDeferred<Unit>? = null
        var blockingResult: PeerBlockingMutationResult? = null
        val httpQueries = mutableListOf<String>()
        val blockingQueries = mutableListOf<String>()
        val blockingMutations = mutableListOf<Pair<String, Boolean>>()
        var authenticationCalls = 0
        var reconnectCalls = 0
        var disconnectCalls = 0
        var closed = false
        var revoked = false
        var retainCredentialAfterDisconnect = false
        private val credentialSentinel = Any()
        private var retainedCredential: Any? = null
        val credentialRetained: Boolean
            get() = retainedCredential != null
        var connectCredential: CharArray? = null
        var usableAfterConnect = true
        var usableAfterReconnect = true
        var emitLossOnReconnectHealthCheck: Int? = null
        private var reconnectHealthChecks = 0
        override val isUsable: Boolean
            get() {
                if (reconnectCalls > 0) {
                    reconnectHealthChecks++
                    if (reconnectHealthChecks == emitLossOnReconnectHealthCheck) emitLoss()
                }
                return !revoked && !closed && usableAfterConnect
            }

        override fun revoke() {
            revoked = true
            closed = true
        }

        override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            connectCredential = credential
            retainedCredential = credentialSentinel
            connectFailure?.let { throw it }
            if (emitLossDuringConnect) emitLoss()
            authenticationCalls++
        }

        override suspend fun reconnect(attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            reconnectCalls++
            reconnectStarted?.complete(Unit)
            if (ignoreReconnectCancellation) {
                withContext(NonCancellable) { finishReconnect() }
                return
            }
            finishReconnect()
        }

        private suspend fun finishReconnect() {
            reconnectGate?.await()
            if (blockReconnect) awaitCancellation()
            reconnectFailure?.let { throw it }
            if (reopenAfterReconnect && !revoked) closed = false
            usableAfterConnect = usableAfterReconnect
        }

        override fun updateAttempt(attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
        }

        override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) {
            if (revoked) throw org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException()
            entered()
            sendStarted?.complete(Unit)
            sendGate?.await()
        }

        override suspend fun fetchHttpFile(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
            url: String,
        ): ByteArray {
            httpQueries += url
            return "ok".encodeToByteArray()
        }

        override suspend fun peerBlockingState(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
            bareJid: String,
        ): PeerBlockingState {
            blockingQueries += bareJid
            return blocking
        }

        override suspend fun setPeerBlocked(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
            bareJid: String,
            blocked: Boolean,
            entered: () -> Unit,
        ): PeerBlockingMutationResult {
            blockingMutations += bareJid to blocked
            entered()
            blockingStarted?.complete(Unit)
            blockingGate?.await()
            return blockingResult ?: PeerBlockingMutationResult.Confirmed(
                PeerBlockingState(true, listOfNotNull(bareJid.takeIf { blocked })),
            )
        }

        override suspend fun disconnect() {
            disconnectCalls++
            closed = true
            if (!retainCredentialAfterDisconnect) retainedCredential = null
            disconnectStarted?.complete(Unit)
            if (ignoreDisconnectCancellation) {
                withContext(NonCancellable) { disconnectGate?.await() }
                return
            }
            disconnectGate?.await()
        }

        fun emitLoss(
            attempt: SessionAttemptIdentity = attemptIdentity,
            reason: SessionFailureReason = SessionFailureReason.NETWORK,
        ) {
            event(SessionEvent.ConnectionLost(attempt, reason))
        }

        fun emitIncoming(body: String, attempt: SessionAttemptIdentity = attemptIdentity) {
            event(
                SessionEvent.Incoming(
                    attempt,
                    IncomingMessageEnvelope(
                        accountId = attempt.accountId,
                        generation = attempt.generation,
                        peer = "peer@example.org",
                        sender = "peer@example.org",
                        outbound = false,
                        originId = null,
                        body = body,
                        thread = null,
                    ),
                ),
            )
        }

        fun emitRoster(attempt: SessionAttemptIdentity) =
            event(SessionEvent.RosterSnapshot(attempt, org.thanosapollo.nema.storage.CompleteRosterSnapshot(attempt.accountId.value, emptyList())))

        fun emitFailure(operationId: String, attempt: SessionAttemptIdentity = attemptIdentity) {
            event(
                SessionEvent.OutgoingFailure(
                    attempt,
                    OutgoingFailureEnvelope(
                        operationId = operationId,
                        peer = "peer@example.org",
                        reason = "remote-server-timeout",
                    ),
                ),
            )
        }
    }
}
