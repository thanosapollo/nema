package org.thanosapollo.nema.service

import android.app.NotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.SessionFailureReason
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundOwnershipTest {
    @Test
    fun `new command cancels stale command before it can remove foreground ownership`() = runTest {
        val runner = SerializedServiceCommandRunner(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()

        runner.submit(1) { current ->
            firstStarted.complete(Unit)
            try {
                releaseFirst.await()
            } finally {
                if (current()) effects += "stale-stop"
            }
        }
        firstStarted.await()
        runner.submit(2) { current -> if (current()) effects += "new-connect" }
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("new-connect"), effects)
    }

    @Test
    fun `service destruction stops reusable process runtime after cancelling commands`() = runTest {
        val runner = SerializedServiceCommandRunner(this)
        var connected = false
        var stops = 0

        runner.submit(1) { }
        runner.submit(2) { current -> if (current()) connected = true }
        advanceUntilIdle()
        assertTrue(connected)

        stopServiceRuntime(
            cancelCommands = runner::cancelCurrent,
            stopRuntime = {
                connected = false
                stops++
            },
        )

        assertFalse(connected)
        assertEquals(1, stops)

        connected = true
        assertTrue(connected)
    }

    @Test
    fun `foreground policy rejects permission denial and blocked channel`() {
        assertTrue(NotificationVisibilityPolicy.allowed(true, true, NotificationManager.IMPORTANCE_LOW))
        assertFalse(NotificationVisibilityPolicy.allowed(false, true, NotificationManager.IMPORTANCE_LOW))
        assertFalse(NotificationVisibilityPolicy.allowed(true, true, NotificationManager.IMPORTANCE_NONE))
        assertFalse(commandRequiresForegroundVisibility(XmppConnectionService.ACTION_STOP))
        assertFalse(commandRequiresForegroundVisibility(XmppConnectionService.ACTION_SIGN_OUT))
        assertTrue(commandRequiresForegroundVisibility(XmppConnectionService.ACTION_CONNECT))
    }

    @Test
    fun `sign out stops before deleting active account and credential`() = runTest {
        val accountId = AccountId.require("active")
        val effects = mutableListOf<String>()

        removeActiveAccount(
            accountId = accountId,
            stopSession = { effects += "stop" },
            deleteCredential = {
                assertEquals(accountId, it)
                effects += "credential"
            },
            deleteAccount = {
                assertEquals(accountId, it)
                effects += "account"
            },
        )

        assertEquals(listOf("stop", "account", "credential"), effects)
    }

    @Test
    fun `missing-credential activate promotes durable active before credentials bind`() = runTest {
        val happy = mutableListOf<String>()
        promoteMissingCredentialActivation(
            stopSession = { happy += "stop" },
            promoteActive = { happy += "promote" },
            requireCredentials = { happy += "credentials" },
        )
        assertEquals(listOf("stop", "promote", "credentials"), happy)

        val effects = mutableListOf<String>()
        val promoted = CompletableDeferred<Unit>()
        val releasePromote = CompletableDeferred<Unit>()

        val promotion = async {
            promoteMissingCredentialActivation(
                stopSession = { effects += "stop" },
                promoteActive = {
                    effects += "promote"
                    promoted.complete(Unit)
                    releasePromote.await()
                },
                requireCredentials = { effects += "credentials" },
            )
        }
        promoted.await()
        assertEquals(listOf("stop", "promote"), effects)
        assertFalse(promotion.isCompleted)

        promotion.cancel()
        releasePromote.complete(Unit)
        promotion.join()

        assertTrue(promotion.isCancelled)
        assertEquals(listOf("stop", "promote"), effects)
    }

    @Test
    fun `cancellation during credential deletion cannot interrupt account removal`() = runTest {
        val accountId = AccountId.require("active")
        val credentialDeleted = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()

        val removal = async {
            removeActiveAccount(
                accountId = accountId,
                stopSession = { effects += "stop" },
                deleteCredential = {
                    effects += "credential"
                    credentialDeleted.complete(Unit)
                    releaseDeletion.await()
                },
                deleteAccount = { effects += "account" },
            )
        }
        credentialDeleted.await()
        removal.cancel()
        releaseDeletion.complete(Unit)
        removal.join()

        assertTrue(removal.isCancelled)
        assertEquals(listOf("stop", "account", "credential"), effects)
    }

    @Test
    fun `credential deletion failure occurs only after account removal`() = runTest {
        val effects = mutableListOf<String>()

        try {
            removeActiveAccount(
                accountId = AccountId.require("active"),
                stopSession = { effects += "stop" },
                deleteCredential = {
                    effects += "credential"
                    error("credential deletion failed")
                },
                deleteAccount = { effects += "account" },
            )
            fail("Credential deletion failure must remain observable")
        } catch (failure: IllegalStateException) {
            assertEquals("credential deletion failed", failure.message)
        }

        assertEquals(listOf("stop", "account", "credential"), effects)
    }

    @Test
    fun `current persistence and credential failures each fail closed`() = runTest {
        val failures = listOf("account persistence", "credential deletion")
        val effects = mutableListOf<String>()

        failures.forEach { source ->
            runCurrentServiceCommand(
                current = { true },
                command = { throw IllegalStateException(source) },
                failCurrent = {
                    failCurrentServiceCommand(
                        current = { true },
                        stopRuntime = { effects += "$source:runtime" },
                        removeForeground = { effects += "$source:foreground" },
                        stopService = { effects += "$source:service" },
                    )
                },
            )
        }

        assertEquals(
            failures.flatMap { listOf("$it:runtime", "$it:foreground", "$it:service") },
            effects,
        )
    }

    @Test
    fun `stale failure cannot stop replacement and cancellation stays observable`() = runTest {
        var failClosedCalls = 0
        runCurrentServiceCommand(
            current = { false },
            command = { throw IllegalStateException("stale") },
            failCurrent = { failClosedCalls++ },
        )
        assertEquals(0, failClosedCalls)

        try {
            runCurrentServiceCommand(
                current = { false },
                command = { throw CancellationException("superseded") },
                failCurrent = { failClosedCalls++ },
            )
            fail("CancellationException must remain observable")
        } catch (_: CancellationException) {
            Unit
        }
        assertEquals(0, failClosedCalls)
    }

    @Test
    fun `replacement arriving during failure cleanup retains foreground ownership`() = runTest {
        var current = true
        val effects = mutableListOf<String>()

        failCurrentServiceCommand(
            current = { current },
            stopRuntime = {
                effects += "runtime"
                current = false
            },
            removeForeground = { effects += "foreground" },
            stopService = { effects += "service" },
        )

        assertEquals(listOf("runtime"), effects)
    }

    @Test
    fun `replacement supersedes visibility shutdown before and during stop and later revoke runs`() = runTest {
        val authority = VisibilityShutdownAuthority()
        val effects = mutableListOf<String>()

        val beforeStop = requireNotNull(authority.begin(1))
        authority.supersede()
        failCurrentServiceCommand(
            current = { authority.isCurrent(beforeStop) },
            stopRuntime = { effects += "stale-before:runtime" },
            removeForeground = { effects += "stale-before:foreground" },
            stopService = { effects += "stale-before:service" },
        )

        val duringStop = requireNotNull(authority.begin(2))
        failCurrentServiceCommand(
            current = { authority.isCurrent(duringStop) },
            stopRuntime = {
                effects += "stale-during:runtime"
                authority.supersede()
            },
            removeForeground = { effects += "stale-during:foreground" },
            stopService = { effects += "stale-during:service" },
        )

        val renewed = requireNotNull(authority.begin(3))
        assertEquals(3, renewed.ownerStartId)
        failCurrentServiceCommand(
            current = { authority.isCurrent(renewed) },
            stopRuntime = { effects += "renewed:runtime" },
            removeForeground = { effects += "renewed:foreground" },
            stopService = { effects += "renewed:service" },
        )

        assertEquals(
            listOf(
                "stale-during:runtime",
                "renewed:runtime",
                "renewed:foreground",
                "renewed:service",
            ),
            effects,
        )
    }

    @Test
    fun `terminal state claims matching foreground owner but not replacement`() {
        val owner = ForegroundSessionOwner()
        val account = AccountId.require("account")
        val firstGeneration = ConnectionGeneration.require(1)
        val secondGeneration = ConnectionGeneration.require(2)
        val firstFailure = ConnectionState.Failed(
            account,
            firstGeneration,
            SessionFailureReason.RETRY_EXHAUSTED,
        )

        owner.activate(1) { true }
        assertEquals(1, owner.claimTerminal(firstFailure, firstFailure))

        owner.activate(2) { true }
        assertNull(
            owner.claimTerminal(
                firstFailure,
                ConnectionState.Connecting(account, secondGeneration),
            ),
        )

        val secondFailure = ConnectionState.Failed(
            account,
            secondGeneration,
            SessionFailureReason.TLS_CERTIFICATE,
        )
        assertEquals(2, owner.claimTerminal(secondFailure, secondFailure))
    }

    @Test
    fun `fail closed teardown continues when foreground removal throws`() = runTest {
        val effects = mutableListOf<String>()

        failClosedForeground(
            stopRuntime = { effects += "runtime" },
            removeForeground = {
                effects += "foreground"
                error("framework failure")
            },
            stopService = { effects += "service" },
        )

        assertEquals(listOf("runtime", "foreground", "service"), effects)
    }
}