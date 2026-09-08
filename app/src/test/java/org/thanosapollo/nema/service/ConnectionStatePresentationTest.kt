package org.thanosapollo.nema.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.chat.ArchiveStorageFailure
import org.thanosapollo.nema.chat.OutboxStorageFailure
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionFailureReason
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.session.SessionLifecycleObservation
import org.thanosapollo.nema.session.dispatchLease
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class ConnectionStatePresentationTest {
    private val account = AccountId.require("private-account-id")
    private val generation = ConnectionGeneration.require(9)

    @Test fun `Tor protected transport without XMPP TLS remains explicitly visible`() {
        val status = privacySafeStatus(ConnectionState.Connected(account, generation, onionWithoutTls = true))
        assertEquals("Connected through Tor · no XMPP TLS", status)
        assertEquals(status, org.thanosapollo.nema.ui.quietConnectionStatus(status))
        assertFalse(status.contains(account.value))
        assertFalse(status.contains("Secure"))
    }

    @Test
    fun `connection status contains no account server or failure detail`() {
        val states = listOf(
            ConnectionState.Stopped,
            ConnectionState.NeedsCredentials(),
            ConnectionState.Disconnected(account),
            ConnectionState.Connecting(account, generation),
            ConnectionState.Connected(account, generation),
            ConnectionState.ReconnectWait(account, generation),
            ConnectionState.Disconnecting(account, generation),
            ConnectionState.Switching(account, AccountId.require("other-private-account-id")),
            ConnectionState.Failed(account, generation, SessionFailureReason.TLS_CERTIFICATE),
            ConnectionState.Failed(account, generation, SessionFailureReason.LOCAL_STORAGE),
        )

        val labels = states.map(::privacySafeStatus)

        assertEquals(
            listOf(
                "Stopped",
                "Credentials required",
                "Disconnected",
                "Connecting",
                "Connected",
                "Waiting to reconnect",
                "Disconnecting",
                "Switching account",
                "Secure connection failed",
                "Local storage failed",
            ),
            labels,
        )
        labels.forEach {
            assertFalse(it.contains(account.value))
            assertFalse(it.contains("TLS_CERTIFICATE"))
        }
    }

    @Test
    fun `only exact connected lifecycle observation yields dispatch lease`() {
        val connected = connectedObservation()

        assertEquals(SessionIdentity(account, generation), connected.dispatchLease()?.identity)
        assertEquals(null, connected.copy(state = ConnectionState.Stopped).dispatchLease())
        assertEquals(null, connected.copy(owner = null).dispatchLease())
        assertEquals(
            null,
            connected.copy(owner = SessionIdentity(account, ConnectionGeneration.require(10))).dispatchLease(),
        )
    }

    @Test
    fun `typed outbox failure is contained per emission and collector continues`() = runTest {
        val connected = connectedObservation()
        val cause = IllegalStateException("storage")
        var actionCalls = 0
        var failureCalls = 0

        flowOf(connected, connected).collect { emission ->
            runOutboxLifecycleStep(
                observation = emission,
                action = {
                    actionCalls++
                    if (actionCalls == 1) throw OutboxStorageFailure(account.value, generation, cause)
                },
                failed = { failure, observed ->
                    failureCalls++
                    assertEquals(account.value, failure.accountId)
                    assertEquals(generation, failure.generation)
                    assertEquals(connected, observed)
                },
            )
        }

        assertEquals(2, actionCalls)
        assertEquals(1, failureCalls)
    }

    @Test
    fun `ordinary observation failure is bound to exact owner and collector continues`() = runTest {
        val connected = connectedObservation()
        var actionCalls = 0
        val failures = mutableListOf<OutboxStorageFailure>()

        flowOf(connected, connected).collect { emission ->
            runOutboxLifecycleStep(
                observation = emission,
                action = {
                    actionCalls++
                    if (actionCalls == 1) error("injected observation failure")
                },
                failed = { failure, _ -> failures += failure },
            )
        }

        assertEquals(2, actionCalls)
        assertEquals(account.value, failures.single().accountId)
        assertEquals(generation, failures.single().generation)
    }

    @Test
    fun `cancellation during typed failure report still finishes report`() = runTest {
        val connected = connectedObservation()
        val reportStarted = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()
        var reportFinished = false
        val collection = launch {
            runOutboxLifecycleStep(
                observation = connected,
                action = {
                    throw OutboxStorageFailure(account.value, generation, IllegalStateException("storage"))
                },
                failed = { _, _ ->
                    reportStarted.complete(Unit)
                    releaseReport.await()
                    reportFinished = true
                },
            )
        }

        reportStarted.await()
        collection.cancel()
        releaseReport.complete(Unit)
        collection.join()

        assertTrue(collection.isCancelled)
        assertTrue(reportFinished)
    }

    @Test
    fun `ordinary cancellation remains cancellation without storage report`() = runTest {
        var failureCalls = 0
        val observed = runCatching {
            runOutboxLifecycleStep(
                observation = connectedObservation(),
                action = { throw CancellationException("stop") },
                failed = { _, _ -> failureCalls++ },
            )
        }.exceptionOrNull()

        assertTrue(observed is CancellationException)
        assertEquals(0, failureCalls)
    }

    @Test
    fun `lifecycle and backfill authority lookup preserve cancellation identity without report`() = runTest {
        val identity = SessionIdentity(account, generation)

        listOf("lifecycle", "backfill").forEach { caller ->
            val cancellation = CancellationException(caller)
            var reports = 0
            val observed = runCatching {
                lookupArchiveAuthority(
                    identity = identity,
                    lookup = { throw cancellation },
                    failed = { reports++ },
                )
            }.exceptionOrNull()

            assertSame(cancellation, observed)
            assertEquals(0, reports)
        }
    }

    @Test
    fun `ordinary authority lookup failure is wrapped and reported once`() = runTest {
        val identity = SessionIdentity(account, generation)
        val cause = IllegalStateException("storage")
        val failures = mutableListOf<ArchiveStorageFailure>()

        val authority = lookupArchiveAuthority(
            identity = identity,
            lookup = { throw cause },
            failed = { failures += it },
        )

        assertEquals(null, authority)
        assertEquals(identity, failures.single().identity)
        assertSame(cause, failures.single().cause)
    }

    private fun connectedObservation() = SessionLifecycleObservation(
        state = ConnectionState.Connected(account, generation),
        epoch = LifecycleEpoch.require(1),
        owner = SessionIdentity(account, generation),
    )
}