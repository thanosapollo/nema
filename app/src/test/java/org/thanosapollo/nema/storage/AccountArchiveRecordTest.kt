package org.thanosapollo.nema.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountArchiveRecordTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "archive-record-${UUID.randomUUID()}.db"
    private lateinit var db: NemaDatabase
    private val key = ArchiveCursorKey("a", "archive.example.org", "ACCOUNT")
    private val shared = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, "peer@example.org", "shared")

    @Before fun open() = runBlocking {
        db = NemaDatabase.create(context, name)
        for (id in listOf("a", "b")) db.accountDao().saveBound(AccountEntity(
            id, "$id@example.org", id, null, "example.org", null, null,
        ))
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun reopen(): MessageStore { db.close(); db = NemaDatabase.create(context, name); return MessageStore(db) }
    private fun record(uid: String, local: String = uid, aliases: List<TrustedIdentityAlias> = listOf(shared)) = ArchivedIncomingMessage(
        uid, IncomingMessage(
            accountId = "a", localMessageId = local, peerJid = "peer@example.org", senderJid = "peer@example.org",
            direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT, body = "same",
            threadId = null, parentThreadId = null, archiveOrdinal = null,
            aliases = aliases, sentAtEpochMs = 1000, sentTimeSource = MessageTimeSource.MAM,
        ),
    )
    private fun page(direction: ArchiveDirection, boundary: String? = null, vararg records: ArchivedIncomingMessage,
        cursorKey: ArchiveCursorKey = key) = ArchivePage(
        cursorKey, direction, boundary, complete = true, hasEarlier = true, stable = true,
        firstId = records.firstOrNull()?.resultId, lastId = records.lastOrNull()?.resultId, messages = records.toList(),
    )
    private suspend fun raw(cursorKey: ArchiveCursorKey = key) = db.messageDao().archiveRecords(
        cursorKey.accountId, cursorKey.archiveAuthority, cursorKey.scope, Long.MIN_VALUE, Long.MAX_VALUE,
    ).map { it.resultId to it.archiveOrdinal }
    private suspend fun canonical() = db.messageDao().archiveScopePositions(key.accountId, key.archiveAuthority, key.scope)

    @Test fun unseededLateBridgeSelectsOwnerBeforeFirstWrite() = runBlocking {
        val other = shared.copy(value = "other")
        val records = arrayOf(record("z", "first"), ArchivedIncomingMessage("control", null),
            record("a", "second", listOf(other)), record("m", "bridge", listOf(shared, other)))
        val result = MessageStore(db).applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = records))
        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(3, result.ingested)
        assertEquals(1, result.inserted)
        assertEquals(1, result.insertedInbound.size)
        val store = reopen()
        assertEquals(listOf("first"), store.messages("a").map { it.localMessageId })
        assertEquals(listOf("z" to 0L, "control" to 1L, "a" to 2L, "m" to 3L), raw())
        assertEquals(listOf(0L), canonical().map { it.archiveOrdinal })
        assertTrue(store.aliases("a").all { it.messageId == "first" && it.status == IdentityAliasStatus.TRUSTED })
        assertEquals(1L, store.messages("a").single().localSequence)
    }

    @Test fun lateBridgeFindsExistingOwnerEvenWhenFirstMemberHasNoStoredAlias() = runBlocking {
        val other = shared.copy(value = "other")
        val store = MessageStore(db)
        store.ingest(requireNotNull(record("seed", "owner", listOf(other)).message))
        val sequence = store.messages("a").single().localSequence
        val result = store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(
            record("z", "first"), record("a", "second", listOf(other)), record("m", "bridge", listOf(shared, other)),
        )))
        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(0, result.inserted)
        assertEquals(listOf("owner"), store.messages("a").map { it.localMessageId })
        assertEquals(sequence, store.messages("a").single().localSequence)
        assertTrue(store.aliases("a").all { it.messageId == "owner" && it.status == IdentityAliasStatus.TRUSTED })
        assertEquals(listOf(0L), canonical().map { it.archiveOrdinal })
        assertEquals(3, raw().size)
    }

    @Test fun splitBothDirectionsAndOverlapKeepCanonicalReadStateAndExactRawOrder() = runBlocking {
        var store = MessageStore(db)
        assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP,
            records = arrayOf(record("mid", "owner")))).status)
        val original = store.messages("a").single()
        assertFalse(original.unreadEligible)
        store = reopen()
        assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page(ArchiveDirection.AFTER, "mid",
            record("mid", "overlap"), record("z", "repeat"))).status)
        assertFalse(store.messages("a").single().unreadEligible)
        store.markMessagesRead("a", "peer@example.org", listOf("owner"))
        store = reopen()
        assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page(ArchiveDirection.BEFORE, "mid",
            record("a", "older"), record("mid", "overlap"), record("z", "overlap-two"))).status)
        assertEquals(listOf("a" to -1L, "mid" to 0L, "z" to 1L), raw())
        assertEquals(listOf(0L), canonical().map { it.archiveOrdinal })
        val after = store.messages("a").single()
        assertEquals(original.localSequence, after.localSequence)
        assertFalse(after.unreadEligible)
        assertTrue(after.locallyRead)
        assertEquals(0, db.messageDao().observeConversationSummaries("a").first().single().unreadCount)
        assertEquals(0L, db.messageDao().peer("a", "peer@example.org")?.lastReadLocalSequence)
        val cursor = requireNotNull(store.archiveCursor(key))
        val noProgress = store.applyArchivePage(page(ArchiveDirection.AFTER, "z", record("mid"), record("z")))
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, noProgress.status)
        assertEquals(cursor.copy(retryableError = noProgress.cursor.retryableError), noProgress.cursor)
    }

    @Test fun lateBridgeAfterCannotPromoteAlreadyArchivedIneligibleOwner() = runBlocking {
        var store = MessageStore(db)
        val old = shared.copy(value = "old")
        store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(record("seed", "owner", listOf(old)))))
        store = reopen()
        val original = store.messages("a").single()
        assertFalse(original.unreadEligible)
        val result = store.applyArchivePage(page(ArchiveDirection.AFTER, "seed",
            record("first"), ArchivedIncomingMessage("control", null),
            record("second", aliases = listOf(old)), record("bridge", aliases = listOf(shared, old))))
        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(0, result.inserted)
        assertTrue(result.insertedInbound.isEmpty())
        assertEquals(original, store.messages("a").single())
        assertEquals(listOf(0L), canonical().map { it.archiveOrdinal })
        assertEquals(5, raw().size)
    }

    @Test fun freshInboundRepeatAllocatesOneNotificationAndOneUnreadRow() = runBlocking {
        val store = MessageStore(db)
        store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(ArchivedIncomingMessage("control", null))))
        val result = store.applyArchivePage(page(ArchiveDirection.AFTER, "control", record("r1"), record("r2")))
        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(1, result.inserted)
        assertEquals(1, result.insertedInbound.count { it.inbound })
        assertEquals(1, db.messageDao().observeConversationSummaries("a").first().single().unreadCount)
        assertEquals(1, store.messages("a").size)
    }

    @Test fun swappedKnownUidsAndOccupiedOrdinalsRejectWithoutPageEffects() = runBlocking {
        val store = MessageStore(db)
        store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(record("one"), record("two"))))
        val before = raw()
        val aliases = store.aliases("a")
        for (records in listOf(arrayOf(record("two"), record("one"), record("new")),
            arrayOf(record("one"), record("new"), record("end")))) {
            val result = store.applyArchivePage(page(ArchiveDirection.AFTER, "two", records = records))
            assertEquals(ArchivePageStatus.RETRYABLE_ERROR, result.status)
            assertEquals(before, raw())
            assertEquals(aliases, store.aliases("a"))
        }
        assertEquals(before, run { reopen(); raw() })
    }

    @Test fun incompatibleMembersAndOptionalMetadataNontransitivityAreAtomic() = runBlocking {
        val first = record("first", aliases = listOf(shared.copy(kind = IdentityAliasKind.STANZA_ID)))
        val base = requireNotNull(first.message)
        val mutations: List<(IncomingMessage) -> IncomingMessage> = listOf(
            { it.copy(body = "different") }, { it.copy(peerJid = "other@example.org") },
            { it.copy(senderJid = "other@example.org") }, { it.copy(direction = MessageDirection.OUTBOUND) },
            { it.copy(threadId = "thread") }, { it.copy(threadId = "thread", parentThreadId = "parent") },
            { it.copy(attachmentUrl = "https://example.org/file") }, { it.copy(replyToId = "target") },
            { it.copy(replyToId = "target", replyToJid = "other@example.org") }, { it.copy(replaceId = "target") },
            { it.copy(messageKind = MessageKind.GROUPCHAT) },
        )
        for (mutate in mutations) {
            val result = MessageStore(db).applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(
                first, ArchivedIncomingMessage("second", mutate(base.copy(localMessageId = "second"))),
            )))
            assertEquals(ArchivePageStatus.RETRYABLE_ERROR, result.status)
            assertTrue(MessageStore(db).messages("a").isEmpty())
            assertTrue(raw().isEmpty())
        }
        for (field in listOf("name", "mime", "size")) {
            fun metadata(local: String, value: String?) = base.copy(localMessageId = local,
                attachmentName = value.takeIf { field == "name" }, attachmentMime = value.takeIf { field == "mime" },
                attachmentSize = value?.length?.toLong()?.takeIf { field == "size" })
            val result = MessageStore(db).applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(
                ArchivedIncomingMessage("first", metadata("first", "A")),
                ArchivedIncomingMessage("second", metadata("second", null)),
                ArchivedIncomingMessage("third", metadata("third", "BB")),
            )))
            assertEquals(ArchivePageStatus.RETRYABLE_ERROR, result.status)
            assertTrue(raw().isEmpty())
        }
    }

    @Test fun existingOwnerMismatchAndTwoOwnerBridgeAreNotRepaired() = runBlocking {
        val store = MessageStore(db)
        store.ingest(requireNotNull(record("seed", "owner").message).copy(attachmentName = "A"))
        val conflicting = requireNotNull(record("r1").message).copy(attachmentName = "B")
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP,
            records = arrayOf(ArchivedIncomingMessage("r1", conflicting), record("r2")))).status)
        val other = shared.copy(value = "other")
        store.ingest(requireNotNull(record("other", "other-owner", listOf(other)).message))
        val before = store.messages("a")
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP,
            records = arrayOf(record("r1"), record("r2", aliases = listOf(other)), record("bridge", aliases = listOf(shared, other))))).status)
        assertEquals(before, store.messages("a"))
        assertTrue(raw().isEmpty())
    }

    @Test fun everyWriteBoundaryRollsBackLedgerAndPageOnFailureOrCancellation() = runBlocking {
        for (boundary in listOf(MessageWriteBoundary.AFTER_MESSAGE, MessageWriteBoundary.AFTER_ALIAS,
            MessageWriteBoundary.AFTER_OUTBOX, MessageWriteBoundary.BEFORE_ARCHIVE_CURSOR, MessageWriteBoundary.AFTER_ARCHIVE_CURSOR)) {
            for (cancel in listOf(false, true)) {
                val store = MessageStore.observingWrites(db, observer = { reached ->
                    if (reached == boundary) {
                        if (cancel) throw CancellationException("injected") else error("injected")
                    }
                })
                try {
                    store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(record("r1"), record("r2"))))
                    fail("Expected rollback at $boundary")
                } catch (expected: IllegalStateException) { assertEquals("injected", expected.message) }
                val reopened = reopen()
                assertTrue(raw().isEmpty())
                assertTrue(canonical().isEmpty())
                assertTrue(reopened.messages("a").isEmpty())
                assertTrue(reopened.aliases("a").isEmpty())
                assertNull(reopened.archiveCursor(key))
            }
        }
    }

    @Test fun quarantinedAliasNeverConnectsOtherwiseSeparateRecords() = runBlocking {
        val store = MessageStore(db)
        store.ingest(requireNotNull(record("seed", "seed").message))
        store.ingest(requireNotNull(record("conflict", "conflict").message).copy(body = "other"))
        assertEquals(IdentityAliasStatus.QUARANTINED, store.aliases("a").single { it.value == shared.value }.status)
        val result = store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(record("r1"), record("r2"))))
        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(2, result.inserted)
        assertEquals(4, store.messages("a").size)
        assertEquals(IdentityAliasStatus.QUARANTINED, store.aliases("a").single { it.value == shared.value }.status)
        assertEquals(2, raw().size)
    }

    @Test fun originalLocalIdCollisionCannotBeHiddenBySelectedComponentOwner() = runBlocking {
        val store = MessageStore(db)
        store.ingest(requireNotNull(record("old", "collision", emptyList()).message).copy(body = "different"))
        val before = store.messages("a")
        try {
            store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, records = arrayOf(record("r1", "first"), record("r2", "collision"))))
            fail("Original local ID must remain validated")
        } catch (_: IllegalArgumentException) { /* entire page rolls back */ }
        val reopened = reopen()
        assertEquals(before, reopened.messages("a"))
        assertTrue(raw().isEmpty())
        assertNull(reopened.archiveCursor(key))
    }

    @Test fun roomRepeatPolicyIsUnchangedAndAccountMismatchAndDuplicateUidRemainInvalid() = runBlocking {
        val records = arrayOf(record("r1", aliases = listOf(shared.copy(kind = IdentityAliasKind.STANZA_ID))),
            record("r2", aliases = listOf(shared.copy(kind = IdentityAliasKind.STANZA_ID))))
        val roomKey = key.copy(scope = "room@example.org")
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, MessageStore(db).applyArchivePage(page(
            ArchiveDirection.BOOTSTRAP, records = records, cursorKey = roomKey)).status)
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, MessageStore(db).applyArchivePage(page(
            ArchiveDirection.BOOTSTRAP, records = arrayOf(record("r1").let { it.copy(message = it.message?.copy(accountId = "b")) }))).status)
        try { page(ArchiveDirection.BOOTSTRAP, records = arrayOf(record("same"), record("same"))); fail("Duplicate UID") }
        catch (_: IllegalArgumentException) { /* DTO rejects before storage */ }
        assertTrue(raw().isEmpty())
        assertTrue(MessageStore(db).messages("a").isEmpty())
    }

    @Test fun accountRemovalCascadesRawLedgerButMessageDeletionDoesNot() = runBlocking {
        val otherKey = key.copy(accountId = "b")
        val store = MessageStore(db)
        for (cursorKey in listOf(key, otherKey)) {
            val records = arrayOf(record("r1"), record("r2")).map { it.copy(message = it.message?.copy(accountId = cursorKey.accountId)) }.toTypedArray()
            assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP,
                records = records, cursorKey = cursorKey)).status)
        }
        val other = raw(otherKey)
        db.openHelper.writableDatabase.execSQL("DELETE FROM trusted_identity_aliases WHERE accountId = 'a'")
        db.openHelper.writableDatabase.execSQL("DELETE FROM messages WHERE accountId = 'a'")
        assertEquals(2, raw().size)
        db.accountDao().remove("a")
        assertTrue(raw().isEmpty())
        assertEquals(other, raw(otherKey))
        assertEquals(1, store.messages("b").size)
    }
}
