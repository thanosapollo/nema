package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentitylessRepairTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "identityless-repair-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().saveBound(
            AccountEntity(ACCOUNT, "account@example.org", ACCOUNT, null, "example.org", null, null),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun pendingRepairMergesUniquePairsAndCompletesAtomicReceipt() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("unique-live", "unique", MessageTimeSource.LOCAL))
        store.ingest(incoming("ambiguous-live", "ambiguous", MessageTimeSource.LOCAL))
        store.applyArchivePage(
            page(
                archived("unique-result", "unique-mam", "unique"),
                archived("ambiguous-result-1", "ambiguous-mam-1", "ambiguous"),
                archived("ambiguous-result-2", "ambiguous-mam-2", "ambiguous"),
            ),
        )
        database.messageDao().insertTrustedAlias(
            TrustedIdentityAliasEntity(
                ACCOUNT,
                IdentityAliasKind.STANZA_ID,
                "untrusted.example.org",
                "quarantined",
                "unique-live",
                IdentityAliasStatus.QUARANTINED,
            ),
        )

        val state = requireNotNull(store.repairIdentitylessDuplicates(ACCOUNT))

        assertEquals(ReconciliationRepairStatus.COMPLETE, state.status)
        assertEquals(5L, state.beforeCount)
        assertEquals(4L, state.afterCount)
        assertEquals(1L, state.matchedCount)
        assertEquals(1L, state.skippedCount)
        assertEquals(
            setOf("unique-live", "ambiguous-live", "ambiguous-mam-1", "ambiguous-mam-2"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId).toSet(),
        )
        assertEquals(state, store.repairIdentitylessDuplicates(ACCOUNT))
    }

    @Test
    fun concurrentRepairRereadsCompleteAfterWriterSerialization() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))

        val states = coroutineScope {
            listOf(
                async { store.repairIdentitylessDuplicates(ACCOUNT) },
                async { store.repairIdentitylessDuplicates(ACCOUNT) },
            ).awaitAll()
        }

        assertTrue(states.all { it?.status == ReconciliationRepairStatus.COMPLETE })
        assertEquals(1, store.messages(ACCOUNT).size)
        assertEquals(1L, states.first()?.matchedCount)
    }

    @Test
    fun failedRepairRollsBackMergeAndReceipt() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) error("stop during repair")
        }

        val failure = runCatching { faulting.repairIdentitylessDuplicates(ACCOUNT) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(2, store.messages(ACCOUNT).size)
        assertEquals(
            AccountReconciliationStateEntity(accountId = ACCOUNT, wallFloorMs = 1_000),
            database.accountDao().reconciliationState(ACCOUNT),
        )
    }

    private fun incoming(id: String, body: String, source: MessageTimeSource) = IncomingMessage(
        accountId = ACCOUNT,
        localMessageId = id,
        peerJid = PEER,
        senderJid = PEER,
        direction = MessageDirection.INBOUND,
        messageKind = MessageKind.CHAT,
        threadId = null,
        parentThreadId = null,
        body = body,
        archiveOrdinal = null,
        aliases = emptyList(),
        sentAtEpochMs = 1_000,
        sentTimeSource = source,
    )

    private fun archived(resultId: String, id: String, body: String) = ArchivedIncomingMessage(
        resultId,
        incoming(id, body, MessageTimeSource.MAM),
    )

    private fun page(vararg messages: ArchivedIncomingMessage) = ArchivePage(
        key = ArchiveCursorKey(ACCOUNT, "account@example.org", "ACCOUNT"),
        direction = ArchiveDirection.BOOTSTRAP,
        boundaryId = null,
        complete = false,
        hasEarlier = false,
        stable = true,
        firstId = messages.first().resultId,
        lastId = messages.last().resultId,
        messages = messages.toList(),
    )

    private companion object {
        const val ACCOUNT = "account"
        const val PEER = "peer@example.org"
    }
}
