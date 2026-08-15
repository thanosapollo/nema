package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.OutboundIntent
import org.thanosapollo.nema.storage.OutboxEntity
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.storage.TrustedIdentityAlias
import org.thanosapollo.nema.session.DispatchLease
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.reply.replyReference
import org.thanosapollo.nema.xmpp.smack.toSmackMessage
import org.thanosapollo.nema.xmpp.smack.toThreadRef
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxDispatcherTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var store: MessageStore
    private val activeLeases = IdentityHashMap<OutboxDispatcher, DispatchLease>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "outbox-dispatcher-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(
            AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null),
        )
        store = MessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun staleAuthorityCannotReadPendingRowsAndReplacementDispatches() = runBlocking {
        val intent = outbound("authoritative-replacement")
        store.compose(intent)
        var authoritative = false
        var pendingReads = 0
        val sent = mutableListOf<OutgoingMessageEnvelope>()
        val dispatcher = OutboxDispatcher(
            store = store,
            pendingOutbound = { accountId ->
                pendingReads++
                store.pendingOutbound(accountId)
            },
            send = { envelope, entered ->
                entered()
                sent += envelope
            },
        )

        val staleDispatch = dispatcher.launchAuthoritativeDispatch(
            lease = lease(1),
            isAuthoritative = { authoritative },
            scope = this,
            onFailure = { error("must not fail") },
        )

        assertEquals(null, staleDispatch)
        assertEquals(0, pendingReads)
        assertTrue(sent.isEmpty())
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 0)

        authoritative = true
        val replacementDispatch = requireNotNull(
            dispatcher.launchAuthoritativeDispatch(
                lease = lease(2),
                isAuthoritative = { authoritative },
                scope = this,
                onFailure = { error("must not fail") },
            ),
        )
        replacementDispatch.join()

        assertEquals(1, pendingReads)
        assertEquals(listOf(intent.operationId), sent.map(OutgoingMessageEnvelope::operationId))
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun threadReplyDraftDispatchesOneStanzaWithBothXepMetadataAndConsumesDraft() = runBlocking {
        val thread = ThreadRef(ThreadId.require("reply-thread"), ThreadId.require("parent-thread"))
        database.messageDao().saveDraft(
            ACCOUNT,
            PEER,
            thread.draftKey(),
            "thread answer",
            "target-wire-id",
            "$PEER/device",
            "target body",
            "device",
        )
        requireNotNull(
            store.composeDirectDraft(
                accountId = ACCOUNT,
                operationId = "operation-thread-reply",
                localMessageId = "local-thread-reply",
                originId = "origin-thread-reply",
                peerJid = PEER,
                senderJid = SELF,
                body = "thread answer",
                thread = thread,
                replyToId = "target-wire-id",
                replyToJid = "$PEER/device",
                replyFallbackBody = "target body",
                replyFallbackSender = "device",
            ),
        )
        var sent: OutgoingMessageEnvelope? = null
        val dispatcher = OutboxDispatcher(store) { envelope, entered ->
            entered()
            sent = envelope
        }

        dispatcher.dispatch(this, 1)

        val envelope = requireNotNull(sent)
        val stanza = envelope.toSmackMessage()
        assertEquals(thread, envelope.thread)
        assertEquals(thread, stanza.toThreadRef())
        assertEquals("target-wire-id", requireNotNull(envelope.reply).id)
        assertEquals("target-wire-id", requireNotNull(stanza.replyReference()).id)
        assertTrue(stanza.body.endsWith("thread answer"))
        assertEquals(null, database.messageDao().draft(ACCOUNT, PEER, thread.draftKey()))
    }

    @Test
    fun correctionDispatchPreservesExactTargetAndUnrelatedDraft() = runBlocking {
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "original-wire-id",
            localMessageId = "original-local-id",
            originId = "original-origin-id",
            peerJid = PEER,
            senderJid = SELF,
            body = "original",
        )
        val original = requireNotNull(store.outbox(ACCOUNT, "original-wire-id"))
        database.messageDao().updateOutbox(original.copy(status = OutboxStatus.ACKNOWLEDGED))
        database.messageDao().saveDraft(ACCOUNT, PEER, "", "unrelated draft")
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "correction-wire-id",
            localMessageId = "correction-local-id",
            originId = "correction-origin-id",
            peerJid = PEER,
            senderJid = SELF,
            body = "corrected",
            replaceId = "original-wire-id",
            correctionTargetMessageId = "original-local-id",
        )
        var sent: OutgoingMessageEnvelope? = null

        OutboxDispatcher(store) { envelope, entered ->
            entered()
            sent = envelope
        }.dispatch(this, 1)

        assertEquals("original-wire-id", requireNotNull(sent).replaceId)
        assertEquals("correction-wire-id", sent?.operationId)
        assertEquals("unrelated draft", database.messageDao().draft(ACCOUNT, PEER, "")?.body)
    }

    @Test
    fun preCallRejectionReturnsExactClaimToPendingAndRetryCapturesNextAttempt() = runBlocking {
        val intent = outbound("retry")
        store.compose(intent)
        var reject = true
        val sent = mutableListOf<OutgoingMessageEnvelope>()
        val dispatcher = OutboxDispatcher(store) { envelope, entered ->
            if (reject) error("rejected before transport entry")
            entered()
            sent += envelope
        }

        dispatcher.dispatch(this, 1)
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 1)

        reject = false
        dispatcher.dispatch(this, 1)

        assertEquals(listOf(2), sent.map(OutgoingMessageEnvelope::attempt))
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 2)
    }

    @Test
    fun dispatchPreservesPersistedParentAndChildThreadLineage() = runBlocking {
        val intent = outbound("thread").copy(
            threadId = "child-thread",
            parentThreadId = "parent-thread",
        )
        store.compose(intent)
        val sent = mutableListOf<OutgoingMessageEnvelope>()
        val dispatcher = OutboxDispatcher(store) { envelope, entered ->
            entered()
            sent += envelope
        }

        dispatcher.dispatch(this, 1)

        assertEquals(
            ThreadRef(ThreadId.require("child-thread"), ThreadId.require("parent-thread")),
            sent.single().thread,
        )
    }

    @Test
    fun enteredSendReturnAndErrorBothBecomeUncertainWithoutAutomaticRetry() = runBlocking {
        listOf("return", "error").forEach { outcome ->
            val intent = outbound(outcome)
            store.compose(intent)
            var calls = 0
            val dispatcher = OutboxDispatcher(store) { _, entered ->
                calls++
                entered()
                if (outcome == "error") error("socket failed")
            }

            dispatcher.dispatch(this, 2)
            dispatcher.dispatch(this, 2)

            assertEquals(1, calls)
            assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
        }
    }

    @Test
    fun cancellationBeforeTransportEntryReturnsPending() = runBlocking {
        val intent = outbound("cancel-before")
        store.compose(intent)
        val started = CompletableDeferred<Unit>()
        val dispatcher = OutboxDispatcher(store) { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }

        val dispatch = dispatcher.start(this, 3).job
        started.await()
        dispatch.cancel()
        dispatch.join()

        assertTrue(dispatch.isCancelled)
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 1)
    }

    @Test
    fun cancellationAfterTransportEntryBecomesUncertain() = runBlocking {
        val intent = outbound("cancel-after")
        store.compose(intent)
        val started = CompletableDeferred<Unit>()
        val dispatcher = OutboxDispatcher(store) { _, entered ->
            entered()
            started.complete(Unit)
            awaitCancellation()
        }

        val dispatch = dispatcher.start(this, 4).job
        started.await()
        dispatch.cancel()
        dispatch.join()

        assertTrue(dispatch.isCancelled)
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun delayedOldReadinessCannotSettleReplacementPreEntryClaim() = runBlocking {
        val intent = outbound("stale-readiness")
        store.compose(intent)
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val dispatcher = OutboxDispatcher(store) { _, _ ->
            sendStarted.complete(Unit)
            releaseSend.await()
            error("rejected before transport entry")
        }
        dispatcher.change(this, lease(2))
        val replacementDispatch = dispatcher.start(this, 2).job
        sendStarted.await()

        dispatcher.change(this, lease(1))
        releaseSend.complete(Unit)
        replacementDispatch.join()

        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 1)
    }

    @Test
    fun replacementDuringPendingReadDiscardsOldSnapshot() = runBlocking {
        val intent = outbound("replace-during-read")
        store.compose(intent)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var sends = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            pendingOutbound = { accountId ->
                readStarted.complete(Unit)
                releaseRead.await()
                store.pendingOutbound(accountId)
            },
            send = { _, _ -> sends++ },
        )
        dispatcher.change(this, lease(1))
        val oldDispatch = dispatcher.start(this, 1).job
        readStarted.await()

        dispatcher.change(this, lease(2))
        releaseRead.complete(Unit)
        oldDispatch.join()

        assertEquals(0, sends)
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 0)
    }

    @Test
    fun replacementDuringClaimReturnsPublishedOldClaimToPending() = runBlocking {
        val intent = outbound("replace-during-claim")
        store.compose(intent)
        val claimStarted = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        var sends = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            claimOutbound = { accountId, operationId, generation ->
                claimStarted.complete(Unit)
                releaseClaim.await()
                store.claim(accountId, operationId, generation)
            },
            send = { _, _ -> sends++ },
        )
        dispatcher.change(this, lease(1))
        val oldDispatch = dispatcher.start(this, 1).job
        claimStarted.await()

        val replacement = async { dispatcher.change(this, lease(2)) }
        yield()
        releaseClaim.complete(Unit)
        replacement.await()
        oldDispatch.join()

        assertEquals(0, sends)
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 1)
    }

    @Test
    fun capturedOldLeaseCannotLaunchAfterReplacement() = runBlocking {
        var reads = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            pendingOutbound = {
                reads++
                emptyList()
            },
            send = { _, _ -> error("must not send") },
        )
        val oldLease = lease(1)
        dispatcher.change(this, oldLease)
        dispatcher.change(this, lease(2))

        val stale = dispatcher.launchDispatch(oldLease, this) { error("must not report") }

        assertEquals(null, stale)
        assertEquals(0, reads)
    }

    @Test
    fun replacementAfterClaimBeforeTransportEntryReturnsPending() = runBlocking {
        val intent = outbound("replace-before-entry")
        store.compose(intent)
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val dispatcher = OutboxDispatcher(store) { _, _ ->
            sendStarted.complete(Unit)
            releaseSend.await()
        }
        val old = dispatcher.start(this, 1)
        sendStarted.await()

        val replacement = async { dispatcher.change(this, lease(2)) }
        yield()
        releaseSend.complete(Unit)
        replacement.await()
        old.job.join()

        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 1)
    }

    @Test
    fun deadlineFreezeRetainsOneRevocationAndLateOldSettlementCannotMutateRetry() = runBlocking {
        val intent = outbound("timeout")
        store.compose(intent)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val dispatcher = OutboxDispatcher(
            store = store,
            send = { envelope, entered ->
                if (envelope.attempt == 1) {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                }
                entered()
            },
        )
        val old = dispatcher.start(this, 1)
        withTimeout(5_000) { oldStarted.await() }

        val retained = requireNotNull(dispatcher.revoke(lease(1)))
        assertSame(retained, dispatcher.revoke(lease(1)))
        retained.freezeUnknownEntry()
        val replacementLease = lease(2)
        assertEquals(replacementLease, dispatcher.lifecycleChanged(replacementLease) { true })
        val replacement = requireNotNull(dispatcher.launchDispatch(replacementLease, this) {
            error("replacement dispatch must not fail")
        })
        withTimeout(5_000) { replacement.join() }
        assertOutbox(intent.operationId, OutboxStatus.IN_FLIGHT, attempt = 1)

        releaseOld.complete(Unit)
        withTimeout(5_000) { old.job.join() }
        assertEquals(
            org.thanosapollo.nema.session.DispatchRevocationResult.COMPLETE,
            withTimeout(5_000) { retained.finish() },
        )
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
        store.retryUncertain(retryKey(requireNotNull(store.outbox(ACCOUNT, intent.operationId))))
        withTimeout(5_000) { dispatcher.dispatch(this, 2) }
        val expected = requireNotNull(store.outbox(ACCOUNT, intent.operationId))

        assertEquals(expected, store.outbox(ACCOUNT, intent.operationId))
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 2)
    }

    @Test
    fun staleStoragePoisonCannotSuppressReplacementGeneration() = runBlocking {
        val intent = outbound("stale-poison")
        store.compose(intent)
        val readStarted = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val cause = IllegalStateException("old storage failed")
        var reads = 0
        var sends = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            pendingOutbound = { accountId ->
                reads++
                if (reads == 1) {
                    readStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFailure.await() }
                    throw cause
                }
                store.pendingOutbound(accountId)
            },
            send = { _, entered ->
                sends++
                entered()
            },
        )
        val old = dispatcher.start(this, 1)
        readStarted.await()

        val replacement = async { dispatcher.change(this, lease(2)) }
        yield()
        releaseFailure.complete(Unit)
        replacement.await()
        old.job.join()
        dispatcher.dispatch(this, 2)

        assertOutboxStorageFailure(old.failure.get(), generation(1), cause)
        assertEquals(2, reads)
        assertEquals(1, sends)
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun senderBindingMismatchFailsGenerationBeforeClaimOrSend() = runBlocking {
        val intent = outbound("sender-mismatch").copy(senderJid = "different@example.org")
        store.compose(intent)
        var sends = 0
        val dispatcher = OutboxDispatcher(store) { _, _ -> sends++ }

        val started = dispatcher.start(this, 12)
        started.job.join()

        val failure = requireNotNull(started.failure.get())
        assertEquals(ACCOUNT, failure.accountId)
        assertEquals(generation(12), failure.generation)
        assertTrue(failure.cause is IllegalStateException)
        assertEquals("Outbound sender does not match account binding", failure.cause?.message)
        assertEquals(0, sends)
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 0)
    }

    @Test
    fun staleTerminalObservationCannotRevokeCurrentLease() = runBlocking {
        val intent = outbound("stale-terminal")
        store.compose(intent)
        var sends = 0
        val dispatcher = OutboxDispatcher(store) { _, entered ->
            sends++
            entered()
        }
        dispatcher.change(this, lease(2))

        dispatcher.change(this, null, authoritative = { false })
        dispatcher.dispatch(this, 2)

        assertEquals(1, sends)
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun delayedAttemptCannotMutateExplicitRetry() = runBlocking {
        val intent = outbound("stale")
        store.compose(intent)
        val enteredFirst = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = OutboxDispatcher(store) { envelope, entered ->
            assertEquals(1, envelope.attempt)
            entered()
            enteredFirst.complete(Unit)
            releaseFirst.await()
        }
        val firstDispatch = first.start(this, 5).job
        enteredFirst.await()

        first.change(this, lease(6))
        store.retryUncertain(retryKey(requireNotNull(store.outbox(ACCOUNT, intent.operationId))))
        val second = OutboxDispatcher(store) { envelope, entered ->
            assertEquals(2, envelope.attempt)
            entered()
        }
        second.dispatch(this, 6)
        val expected = requireNotNull(store.outbox(ACCOUNT, intent.operationId))

        releaseFirst.complete(Unit)
        firstDispatch.join()

        assertEquals(expected, store.outbox(ACCOUNT, intent.operationId))
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 2)
    }

    @Test
    fun freshConnectionDispatchesOnlyPendingRows() = runBlocking {
        val uncertain = outbound("uncertain")
        val pending = outbound("pending")
        store.compose(uncertain)
        store.compose(pending)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, uncertain.operationId, generation = 7)))
        val sent = mutableListOf<String>()
        val dispatcher = OutboxDispatcher(store) { envelope, entered ->
            entered()
            sent += envelope.operationId
        }

        dispatcher.dispatch(this, 8)

        assertEquals(listOf(pending.operationId), sent)
        assertOutbox(uncertain.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
        assertOutbox(pending.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun pendingReadFailurePoisonsGenerationBeforeQueuedDispatch() = runBlocking {
        store.compose(outbound("pending-read-failure"))
        val readEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val cause = IllegalStateException("injected pending read failure")
        var reads = 0
        var sends = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            pendingOutbound = {
                reads++
                readEntered.complete(Unit)
                releaseFailure.await()
                throw cause
            },
            send = { _, _ -> sends++ },
        )
        val generation = generation(9)

        val first = async {
            runCatching { dispatcher.dispatch(this, 9) }.exceptionOrNull()
        }
        readEntered.await()
        val second = async {
            secondStarted.complete(Unit)
            runCatching { dispatcher.dispatch(this, 9) }.exceptionOrNull()
        }
        secondStarted.await()
        yield()
        releaseFailure.complete(Unit)

        val firstFailure = first.await()
        val secondFailure = second.await()
        assertOutboxStorageFailure(firstFailure, generation, cause)
        assertOutboxStorageFailure(secondFailure, generation, cause)
        assertEquals(1, reads)
        assertEquals(0, sends)
    }

    @Test
    fun confirmationBetweenSnapshotAndClaimSkipsStaleRowAndContinuesBatch() = runBlocking {
        val first = outbound("first")
        val second = outbound("second")
        store.compose(first)
        store.compose(second)
        val sent = mutableListOf<String>()
        val dispatcher = OutboxDispatcher(
            store = store,
            claimOutbound = { accountId, operationId, generation ->
                if (operationId == first.operationId) confirm(first)
                store.claim(accountId, operationId, generation)
            },
            send = { envelope, entered ->
                entered()
                sent += envelope.operationId
            },
        )

        dispatcher.dispatch(this, 9)

        assertEquals(listOf(second.operationId), sent)
        assertOutbox(first.operationId, OutboxStatus.CONFIRMED, attempt = 0)
        assertOutbox(second.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun closedRoomDuringClaimAbortsBatchBeforeTransport() = runBlocking {
        val first = outbound("storage-failure-first")
        val second = outbound("storage-failure-second")
        store.compose(first)
        store.compose(second)
        var sends = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            claimOutbound = { accountId, operationId, generation ->
                database.close()
                store.claim(accountId, operationId, generation)
            },
            send = { _, _ -> sends++ },
        )

        val failure = runCatching { dispatcher.dispatch(this, 10) }.exceptionOrNull()

        assertTrue("Expected typed Room claim failure", failure is OutboxStorageFailure)
        failure as OutboxStorageFailure
        assertEquals(ACCOUNT, failure.accountId)
        assertEquals(generation(10), failure.generation)
        assertEquals(0, sends)
        database = NemaDatabase.create(context, databaseName)
        store = MessageStore(database)
        val repeated = runCatching { dispatcher.dispatch(this, 10) }.exceptionOrNull()
        assertSame(failure, repeated)
        assertEquals(0, sends)
        assertOutbox(first.operationId, OutboxStatus.PENDING, attempt = 0)
        assertOutbox(second.operationId, OutboxStatus.PENDING, attempt = 0)
    }

    @Test
    fun retainedClaimSettlementRetriesAfterPersistenceFailure() = runBlocking {
        val intent = outbound("settlement")
        store.compose(intent)
        var fail = true
        var sends = 0
        var settlements = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            recordUncertain = { claim ->
                settlements++
                if (fail) error("injected persistence failure")
                store.recordPotentialDelivery(claim)
                Unit
            },
            send = { _, entered ->
                sends++
                entered()
            },
        )

        val failure = runCatching { dispatcher.dispatch(this, 9) }.exceptionOrNull()
        assertTrue(failure is OutboxStorageFailure)
        assertOutbox(intent.operationId, OutboxStatus.IN_FLIGHT, attempt = 1)

        fail = false
        dispatcher.detach(this)
        dispatcher.detach(this)

        assertEquals(1, sends)
        assertEquals(2, settlements)
        assertOutbox(intent.operationId, OutboxStatus.UNCERTAIN, attempt = 1)
    }

    @Test
    fun definitePreEntrySettlementRetriesOnSameGenerationWithoutSecondSend() = runBlocking {
        val intent = outbound("pending-settlement")
        store.compose(intent)
        var fail = true
        var sends = 0
        var settlements = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            recordPending = { claim ->
                settlements++
                if (fail) error("injected persistence failure")
                store.recordDefinitePreHandoffFailure(claim)
                Unit
            },
            send = { _, _ ->
                sends++
                error("rejected before transport entry")
            },
        )

        val failure = runCatching { dispatcher.dispatch(this, 9) }.exceptionOrNull()
        assertTrue(failure is OutboxStorageFailure)
        assertOutbox(intent.operationId, OutboxStatus.IN_FLIGHT, attempt = 1)

        fail = false
        dispatcher.detach(this)
        dispatcher.detach(this)

        assertEquals(1, sends)
        assertEquals(2, settlements)
        assertOutbox(intent.operationId, OutboxStatus.PENDING, attempt = 1)
    }

    @Test
    fun failedRetainedSettlementIsNotRetriedByLateSendCompletion() = runBlocking {
        val intent = outbound("detached-settlement")
        store.compose(intent)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        var settlements = 0
        val dispatcher = OutboxDispatcher(
            store = store,
            recordUncertain = {
                settlements++
                error("injected detached settlement failure")
            },
            send = { _, entered ->
                entered()
                sendEntered.complete(Unit)
                releaseSend.await()
            },
        )
        val started = dispatcher.start(this, 10)
        withTimeout(5_000) { sendEntered.await() }

        val retained = requireNotNull(dispatcher.revoke(lease(10)))
        releaseSend.complete(Unit)
        withTimeout(5_000) { started.job.join() }
        val dispatchFailure = started.failure.get()
        val settlement = withTimeout(5_000) { retained.finish() }

        assertEquals(null, dispatchFailure)
        assertEquals(
            org.thanosapollo.nema.session.DispatchRevocationResult.LOCAL_STORAGE_FAILED,
            settlement,
        )
        assertEquals(1, settlements)
        assertOutbox(intent.operationId, OutboxStatus.IN_FLIGHT, attempt = 1)
    }

    @Test
    fun fatalJvmErrorIsNotConvertedToStorageFailure() = runBlocking {
        val fatal = AssertionError("fatal")
        val dispatcher = OutboxDispatcher(
            store = store,
            pendingOutbound = { throw fatal },
            send = { _, _ -> error("must not send") },
        )

        val observed = CompletableDeferred<Throwable>()
        val fatalScope = CoroutineScope(
            coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, failure ->
                observed.complete(failure)
            },
        )
        val exactLease = lease(11)
        dispatcher.change(fatalScope, exactLease)
        val dispatch = requireNotNull(dispatcher.launchDispatch(exactLease, fatalScope) {
            error("must not convert fatal error")
        })

        assertSame(fatal, observed.await())
        dispatch.join()
        fatalScope.cancel()
    }

    private suspend fun assertOutbox(operationId: String, status: OutboxStatus, attempt: Int) {
        val outbox = requireNotNull(store.outbox(ACCOUNT, operationId))
        assertEquals(status, outbox.status)
        assertEquals(attempt, outbox.attempt)
        assertTrue(outbox.status != OutboxStatus.ACKNOWLEDGED)
    }

    private fun assertOutboxStorageFailure(
        failure: Throwable?,
        generation: ConnectionGeneration,
        cause: Throwable,
    ) {
        assertTrue(failure is OutboxStorageFailure)
        failure as OutboxStorageFailure
        assertEquals(ACCOUNT, failure.accountId)
        assertEquals(generation, failure.generation)
        assertSame(cause, failure.cause)
    }

    private suspend fun confirm(intent: OutboundIntent) {
        store.ingest(
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = "server-${intent.operationId}",
                peerJid = PEER,
                senderJid = SELF,
                direction = MessageDirection.OUTBOUND,
                messageKind = MessageKind.CHAT,
                threadId = null,
                parentThreadId = null,
                body = intent.body,
                archiveOrdinal = null,
                aliases = listOf(
                    TrustedIdentityAlias(
                        kind = IdentityAliasKind.ORIGIN_ID,
                        authority = MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        value = intent.originId,
                    ),
                ),
            ),
        )
    }

    private fun outbound(suffix: String) = OutboundIntent(
        accountId = ACCOUNT,
        operationId = "operation-$suffix",
        localMessageId = "local-$suffix",
        originId = "origin-$suffix",
        peerJid = PEER,
        senderJid = SELF,
        messageKind = MessageKind.CHAT,
        threadId = null,
        parentThreadId = null,
        body = "body",
    )

    private fun retryKey(outbox: OutboxEntity) = RetryUncertainKey(
        accountId = outbox.accountId,
        operationId = outbox.operationId,
        generation = requireNotNull(outbox.generation),
        attempt = outbox.attempt,
    )

    private fun generation(value: Long) = ConnectionGeneration.require(value)

    private fun lease(generation: Long, epoch: Long = 1) = DispatchLease(
        LifecycleEpoch.require(epoch),
        SessionIdentity(AccountId.require(ACCOUNT), generation(generation)),
    )

    private suspend fun OutboxDispatcher.change(
        scope: CoroutineScope,
        next: DispatchLease?,
        authoritative: () -> Boolean = { true },
    ): DispatchLease? {
        val previous = activeLeases[this]
        if (previous != null && previous != next) {
            val result = revoke(previous)?.finish()
            if (result == org.thanosapollo.nema.session.DispatchRevocationResult.LOCAL_STORAGE_FAILED) {
                return null
            } else {
                activeLeases.remove(this)
            }
        }
        val active = lifecycleChanged(next, authoritative)
        if (active != null) activeLeases[this] = active
        return active
    }

    private suspend fun OutboxDispatcher.start(
        scope: CoroutineScope,
        generation: Long,
    ): StartedDispatch {
        val exactLease = lease(generation)
        check(change(scope, exactLease) == exactLease)
        val failure = AtomicReference<OutboxStorageFailure?>()
        val job = requireNotNull(launchDispatch(exactLease, scope) { failure.set(it) })
        return StartedDispatch(job, failure)
    }

    private suspend fun OutboxDispatcher.dispatch(scope: CoroutineScope, generation: Long) {
        val started = start(scope, generation)
        started.job.join()
        started.failure.get()?.let { throw it }
    }

    private suspend fun OutboxDispatcher.detach(scope: CoroutineScope) {
        change(scope, null)
    }

    private data class StartedDispatch(
        val job: Job,
        val failure: AtomicReference<OutboxStorageFailure?>,
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val SELF = "account@example.org"
        private const val PEER = "peer@example.org"
    }
}