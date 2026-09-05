package org.thanosapollo.nema.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftCorrection
import org.thanosapollo.nema.chat.DraftReply
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrdinaryDraftOwnershipTest {
    private val key = DirectConversationKey("account", "peer@example.org", ThreadRef(ThreadId.require("child"), ThreadId.require("parent")))
    private val venue = ConversationVenue.Direct
    private val target = DraftCorrection("local", "wire", "original")
    private val draft = ComposerState(key, " \n ", 7L, attachmentUrl = "https://example.org/file",
        attachmentName = "file.pdf", attachmentMime = "application/pdf", attachmentSize = 19L,
        reply = DraftReply("reply", "peer@example.org", "quoted", "Peer"))
    private fun ComposerOwner.current() = composerStates.value.getValue(key)
    private fun ComposerOwner.install(state: ComposerState) { composerStates.value += key to state }
    private fun ComposerOwner.failed() {
        assertTrue(current().ordinarySaveUnconfirmed)
        assertTrue(pendingDraftAttempts.value.isEmpty())
    }
    private fun ComposerState.edit(body: String) = copy(body = body, revision = revision + 1,
        ordinaryRevision = ordinaryRevision + 1)
    private fun saved(state: ComposerState): List<*> = with(composerStateSaver(key)) {
        with(object : SaverScope { override fun canBeSaved(value: Any) = true }) {
            checkNotNull(save(mutableStateOf(state))) as List<*>
        }
    }

    // Defensive owner injection: actual Edit UI forbids entering correction during an ordinary save.
    @Test fun relocationRetainsFailureThroughCorrectionEditsAndCancel() = runTest {
        for (exception in listOf(false, true)) {
            val owner = ComposerOwner(this)
            val result = CompletableDeferred<Boolean>()
            owner.saveOrdinary(draft, venue) { result }
            owner.install(owner.current().beginCorrection(target, "corrected"))
            if (exception) result.completeExceptionally(IllegalStateException("save")) else result.complete(false)
            runCurrent()
            owner.failed()
            owner.install(owner.current().copy(body = "new correction", revision = owner.current().revision + 1))
            owner.install(owner.current().cancelCorrection())
            owner.failed()
            assertEquals(draft.ordinarySnapshot(venue), owner.current().ordinarySnapshot(venue))
        }
    }

    @Test fun backupSettlementSurvivesCorrectionSuccessBeforeOrAfterSave() = runTest {
        for (saveFirst in listOf(false, true)) for (success in listOf(false, true)) {
            val owner = ComposerOwner(this)
            val correcting = draft.beginCorrection(target, "corrected")
            val send = correcting.toDraftSnapshot(venue)
            val backup = checkNotNull(correcting.correctionBackup).copy(body = "latest backup")
            val next = correcting.copy(correctionBackup = backup, ordinaryRevision = correcting.ordinaryRevision + 1)
            val result = CompletableDeferred<Boolean>()
            owner.saveOrdinary(next, venue) { snapshot ->
                assertNull(snapshot.correction)
                assertEquals(draft.ordinarySnapshot(venue).copy(body = "latest backup", composerRevision = 8L), snapshot)
                result
            }
            assertEquals(send, owner.current().toDraftSnapshot(venue))
            if (saveFirst) { result.complete(success); runCurrent() }
            owner.install(owner.current().clearAfterSend(send, venue))
            if (!saveFirst) { result.complete(success); runCurrent() }
            assertNull(owner.current().correction)
            assertEquals(next.ordinarySnapshot(venue), owner.current().ordinarySnapshot(venue))
            assertEquals(!success, owner.current().ordinarySaveUnconfirmed)
            assertTrue(owner.pendingDraftAttempts.value.isEmpty())
        }
    }

    @Test fun ordinarySendInvalidatesAnOutstandingSave() = runTest {
        for (success in listOf(false, true)) {
            val owner = ComposerOwner(this)
            val result = CompletableDeferred<Boolean>()
            owner.saveOrdinary(draft, venue) { result }
            owner.install(owner.current().clearAfterSend(draft.toDraftSnapshot(venue), venue))
            result.complete(success)
            runCurrent()
            assertEquals("", owner.current().body)
            assertNull(owner.current().reply)
            assertNull(owner.current().attachmentUrl)
            assertFalse(owner.current().ordinarySaveUnconfirmed)
            assertTrue(owner.pendingDraftAttempts.value.isEmpty())
        }
    }

    @Test fun sameOccurrenceNewAttemptCannotBeSettledByItsPredecessor() = runTest {
        val owner = ComposerOwner(this)
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        owner.saveOrdinary(draft, venue) { first }
        owner.saveOrdinary(owner.current(), venue) { second }
        val latest = owner.pendingDraftAttempts.value.getValue(key)
        first.complete(true)
        runCurrent()
        assertSame(latest, owner.pendingDraftAttempts.value[key])
        assertTrue(owner.current().ordinarySaveUnconfirmed)
        second.complete(false)
        runCurrent()
        owner.failed()
    }

    @Test fun abaAndStaleFailuresCannotRetargetLatestOccurrence() = runTest {
        val owner = ComposerOwner(this)
        val results = List(3) { CompletableDeferred<Boolean>() }
        var next = draft
        listOf("P", "Q", "P").forEachIndexed { index, body ->
            next = next.edit(body)
            owner.saveOrdinary(next, venue) { results[index] }
        }
        val latest = owner.pendingDraftAttempts.value.getValue(key)
        results[1].completeExceptionally(IllegalStateException("stale"))
        runCurrent()
        assertSame(latest, owner.pendingDraftAttempts.value[key])
        results[2].complete(false)
        runCurrent()
        results[0].complete(true)
        runCurrent()
        owner.failed()
        assertEquals("P", owner.current().body)
        assertEquals(10L, owner.current().ordinaryRevision)
    }

    @Test fun reservePrecedesSynchronousReentryAndThrow() = runTest {
        val owner = ComposerOwner(this)
        val successor = CompletableDeferred<Boolean>()
        owner.saveOrdinary(draft, venue) {
            assertTrue(owner.current().ordinarySaveUnconfirmed)
            assertNotNull(owner.pendingDraftAttempts.value[key])
            owner.saveOrdinary(owner.current().edit("successor"), venue) { successor }
            throw IllegalStateException("old callback")
        }
        assertEquals("successor", owner.current().body)
        assertNotNull(owner.pendingDraftAttempts.value[key])
        successor.complete(true)
        runCurrent()
        assertFalse(owner.current().ordinarySaveUnconfirmed)
        assertTrue(owner.pendingDraftAttempts.value.isEmpty())
        owner.saveOrdinary(owner.current().edit("throwing"), venue) { throw IllegalStateException("current") }
        owner.failed()
    }

    @Test fun completedResultsAndLiveCancellationSettleButTeardownCannotWrite() = runTest {
        val owner = ComposerOwner(this)
        owner.saveOrdinary(draft, venue) { CompletableDeferred(true) }
        runCurrent()
        assertFalse(owner.current().ordinarySaveUnconfirmed)
        val cancelled = CompletableDeferred<Boolean>()
        owner.saveOrdinary(draft, venue) { cancelled }
        cancelled.cancel()
        runCurrent()
        owner.failed()
        val job = Job()
        val retired = ComposerOwner(CoroutineScope(coroutineContext + job))
        val late = CompletableDeferred<Boolean>()
        retired.saveOrdinary(draft, venue) { late }
        runCurrent()
        val before = retired.current()
        job.cancel()
        runCurrent()
        late.complete(true)
        var called = false
        retired.saveOrdinary(draft.edit("replacement"), venue) { called = true; CompletableDeferred(true) }
        runCurrent()
        assertFalse(called)
        assertEquals(before, retired.current())
        assertEquals(draft.ordinarySnapshot(venue), owner.current().ordinarySnapshot(venue))
    }

    @Test fun saverPreservesExactOrdinaryAndBackupAcrossEverySettlement() = runTest {
        for (correction in listOf(false, true)) for (result in listOf(null, false, true)) {
            val owner = ComposerOwner(this)
            val state = if (correction) draft.beginCorrection(target, "corrected") else draft
            val pending = CompletableDeferred<Boolean>()
            owner.saveOrdinary(state, venue) { pending }
            if (result != null) { pending.complete(result); runCurrent() }
            val restored = checkNotNull(composerStateSaver(key).restore(saved(owner.current()))).value
            assertEquals(owner.current(), restored)
            val replacement = ComposerOwner(this)
            replacement.install(restored)
            assertTrue(replacement.pendingDraftAttempts.value.isEmpty())
            assertEquals(result != true, replacement.current().ordinarySaveUnconfirmed)
            assertEquals(draft.ordinarySnapshot(venue), restored.ordinarySnapshot(venue))
            pending.complete(true)
            runCurrent()
            assertEquals(restored, replacement.current())
        }
    }

    @Test fun legacyRowsMigrateConservativelyAndMalformedOrWrongKeysAreRejected() {
        for (state in listOf(draft, draft.beginCorrection(target, "corrected"))) {
            val legacy = saved(state).take(28).toMutableList().also { it[0] = 4; it[7] = null }
            val restored = checkNotNull(composerStateSaver(key).restore(legacy)).value
            assertEquals(state.copy(ordinarySaveUnconfirmed = true, ordinaryRevision = state.revision), restored)
            val valid = saved(state)
            for ((index, invalid) in listOf(7 to "true", 28 to -1L, 6 to -1L, (if (state.correction == null) 12 else 24) to null, 16 to "broken")) {
                val row = valid.toMutableList().also { it[index] = invalid }
                if (index == 16 && state.correction != null) continue
                assertNull(composerStateSaver(key).restore(row))
            }
            assertNull(composerStateSaver(key.copy(accountId = "replacement")).restore(valid))
            assertNull(composerStateSaver(key.copy(thread = ThreadRef(ThreadId.require("child"), ThreadId.require("other")))).restore(valid))
        }
    }
}
