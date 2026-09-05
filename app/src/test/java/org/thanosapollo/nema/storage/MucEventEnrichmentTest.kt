package org.thanosapollo.nema.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MucEventEnrichmentTest {
    private val room = "room@example.org"
    private val key = ArchiveCursorKey("a", room, room)
    private val facts = MucEventFacts("target", null, MucClaimState.NONE, "actor",
        MucOccupantEvidence.ROOM_MAM, MucPayloadState.PLAIN)
    private fun message(id: String) = IncomingMessage("a", id, room, "$room/nick",
        MessageDirection.INBOUND, MessageKind.GROUPCHAT, null, null, "same body", null,
        listOf(TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, room, id),
            TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, "$room/nick", "target")), mucFacts = facts)

    @Test
    fun targetAliasesNeverMergeOrdinaryEventsOrPageComponents() = runBlocking {
        for (pageMode in listOf(false, true)) database { db ->
            val store = MessageStore(db)
            if (pageMode) {
                val page = ArchivePage(key, ArchiveDirection.BOOTSTRAP, null, true, false, true,
                    "one", "two", listOf("one", "two").map { ArchivedIncomingMessage(it, message(it)) })
                assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page).status)
                assertEquals(2, db.messageDao().archivePositions("a").size)
            } else {
                store.ingest(message("one"))
                val result = store.ingest(message("two"))
                assertTrue(result.inserted)
                assertEquals(0, result.mergedRows)
                assertTrue(result.identityConflict)
            }
            assertEquals(setOf("one", "two"), store.messages("a").map { it.localMessageId }.toSet())
            assertEquals(IdentityAliasStatus.QUARANTINED,
                store.aliases("a").single { it.kind == IdentityAliasKind.MESSAGE_ID }.status)
        }
    }

    @Test
    fun trustedReplayPreservesIdentityOnIncompatibleContentAndRejectsDisagreement() = runBlocking {
        database { db ->
            val store = MessageStore(db)
            store.ingest(message("one").copy(mucFacts = null))
            val before = store.messages("a")
            val incompatible = store.ingest(message("one").copy(localMessageId = "new", body = "different"))
            assertTrue(incompatible.identityConflict)
            assertFalse(incompatible.inserted)
            assertEquals(before, store.messages("a"))
            store.ingest(message("two").copy(aliases = listOf(
                TrustedIdentityAlias(IdentityAliasKind.MAM_RESULT, key.aliasAuthority(), "second"))))
            val both = store.messages("a")
            assertTrue(runCatching { store.ingest(message("one").copy(localMessageId = "ambiguous",
                aliases = message("one").aliases + TrustedIdentityAlias(
                    IdentityAliasKind.MAM_RESULT, key.aliasAuthority(), "second"))) }.isFailure)
            assertEquals(both, store.messages("a"))
        }
    }

    @Test
    fun laterLiveObservationCannotRelabelEpochAndFactConflictsAreSticky() = runBlocking {
        database { db ->
            val store = MessageStore(db)
            store.ingest(message("one"))
            store.ingest(message("one").copy(localMessageId = "live",
                mucFacts = facts.copy(evidence = MucOccupantEvidence.LIVE_ROOM)))
            val row = store.messages("a").single()
            assertEquals(MucOccupantEvidence.BOTH, row.mucOccupantEvidence)
            assertNull(row.mucLiveOrderEpoch)
            store.ingest(message("one").copy(localMessageId = "bad",
                mucFacts = facts.copy(messageId = "different")))
            store.ingest(message("one").copy(localMessageId = "again"))
            assertEquals(MucClaimState.CONFLICT, store.messages("a").single().mucClaimState)
            assertEquals("target", store.messages("a").single().mucMessageId)
        }
    }

    @Test
    fun rejectedReplayLeavesNoThreadOrPeerEffectsIncludingPageAndReopen() = runBlocking {
        for (pageMode in listOf(false, true)) for (quarantined in listOf(false, true)) database { db ->
            val store = MessageStore(db)
            store.ingest(message("one").copy(archiveOrdinal = 0, archiveAuthority = room, archiveScope = room))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE peers SET room = 0")
            if (quarantined) sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', 'STANZA_ID', '$room', 'blocked', NULL, 'QUARANTINED')")
            val before = rejectedSnapshot(db)
            val incoming = message("one").copy(localMessageId = "rejected",
                threadId = if (quarantined) null else "new-thread",
                parentThreadId = if (quarantined) null else "new-parent",
                aliases = message("one").aliases + if (quarantined)
                    listOf(TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, room, "blocked")) else emptyList())
            if (pageMode) {
                val result = store.applyArchivePage(ArchivePage(key, ArchiveDirection.BOOTSTRAP, null,
                    true, false, true, "uid", "uid", listOf(ArchivedIncomingMessage("uid", incoming))))
                assertEquals(ArchivePageStatus.APPLIED, result.status)
            } else {
                val result = store.ingest(incoming)
                assertTrue(result.identityConflict)
                assertFalse(result.inserted)
            }
            assertEquals(before, rejectedSnapshot(db))
            val name = java.io.File(requireNotNull(sql.path)).name
            db.close()
            val reopened = NemaDatabase.create(ApplicationProvider.getApplicationContext<Application>(), name)
            try { assertEquals(before, rejectedSnapshot(reopened)) } finally { reopened.close() }
        }
    }

    @Test
    fun mergeRetainingOlderSequenceClearsLiveEpochAcrossReopen() = runBlocking {
        database { db ->
            val store = MessageStore(db)
            store.ingest(message("older").copy(aliases = emptyList(), mucFacts = null))
            val older = store.messages("a").single()
            db.messageDao().updateMessage(older.copy(mucLiveOrderEpoch = "old-live-insertion"))
            store.ingest(message("newer").copy(mucFacts = null))
            val result = store.ingest(message("newer").copy(localMessageId = "older", mucFacts = null))
            assertEquals(1, result.mergedRows)
            assertEquals(older.localSequence, store.messages("a").single().localSequence)
            assertNull(store.messages("a").single().mucLiveOrderEpoch)
            val name = java.io.File(requireNotNull(db.openHelper.writableDatabase.path)).name
            db.close()
            val reopened = NemaDatabase.create(ApplicationProvider.getApplicationContext<Application>(), name)
            try { assertNull(MessageStore(reopened).messages("a").single().mucLiveOrderEpoch) }
            finally { reopened.close() }
        }
    }

    private fun rejectedSnapshot(db: NemaDatabase): List<List<List<String?>>> =
        listOf("messages", "message_threads", "peers", "trusted_identity_aliases").map { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                }) }
            }
        }

    private suspend fun database(block: suspend (NemaDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "muc-enrichment-${UUID.randomUUID()}.db"
        val db = NemaDatabase.create(context, name)
        try {
            db.accountDao().upsert(AccountEntity("a", "self@example.org", "self", null, "example.org", null, null))
            block(db)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
