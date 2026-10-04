package org.thanosapollo.nema.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ArchiveSynchronizer
import org.thanosapollo.nema.chat.ArchiveSyncState
import org.thanosapollo.nema.chat.LiveMessageAdapter
import org.thanosapollo.nema.chat.toIncomingMessage
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.omemo.*
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.transport.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectedReplayTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val identity = SessionIdentity(ProtectedFixtures.attempt.accountId, ProtectedFixtures.attempt.generation)

    private suspend fun fixture(block: suspend (String, NemaDatabase) -> Unit) {
        SmackAndroid.initialize(context)
        installNemaOmemoProviders()
        val name = "protected-replay-${UUID.randomUUID()}.db"
        val db = NemaDatabase.create(context, name)
        try {
            for (id in listOf("account", "other")) db.accountDao().saveBound(AccountEntity(
                id, "$id@example.org", id, null, "example.org", null, null))
            block(name, db)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private suspend fun archive(store: MessageStore, vararg entries: Pair<String, IncomingMessageEnvelope>) {
        var local = 0
        val sync = ArchiveSynchronizer(store,
            discover = { SessionCapabilities(true, CarbonCapabilityState.UNSUPPORTED, false) },
            query = { request -> ArchivePageEnvelope(request, true, true, false, entries.first().first,
                entries.last().first, entries.map { ArchiveMessageEnvelope(it.first, it.second) }) },
            localIds = { "archive-${entries.first().first}-${local++}" })
        sync.synchronize(identity, ProtectedFixtures.self, isAuthoritative = { true })
        assertTrue(sync.state.value.toString(), sync.state.value is ArchiveSyncState.Ready)
    }

    @Test fun nativeCarrierPermutationsPreserveOneOwnerThroughEveryReopenAndOrdinaryControls() = runBlocking {
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (protocol in OmemoProtocol.entries) for (outbound in listOf(false, true)) {
            for (protected in listOf(false, true)) for (order in orders) fixture { name, initial ->
                var db = initial
                val kinds = listOf(ProtectedCarrierKind.LIVE,
                    if (outbound) ProtectedCarrierKind.SENT_CARBON else ProtectedCarrierKind.RECEIVED_CARBON,
                    ProtectedCarrierKind.MAM)
                try {
                    val observed = mutableSetOf<ProtectedCarrierKind>()
                    var owner: String? = null
                    for (index in order) {
                        val kind = kinds[index]
                        val envelope = ProtectedFixtures.carried(protocol, kind, outbound, protected = protected)
                        if (kind == ProtectedCarrierKind.MAM) archive(MessageStore(db), "r1" to envelope)
                        else LiveMessageAdapter(MessageStore(db)) { kind.name }.ingest(envelope)
                        observed += kind
                        db.close(); db = NemaDatabase.create(context, name)
                        val row = MessageStore(db).messages("account").single()
                        if (owner == null) owner = row.localMessageId else assertEquals(owner, row.localMessageId)
                        assertEquals("fallback", row.body)
                        assertEquals(if (outbound) MessageDirection.OUTBOUND else MessageDirection.INBOUND, row.direction)
                        if (protected) {
                            assertEquals("AQID", row.protection()!!.content!!.payload)
                            assertEquals(observed, row.protection()!!.carriers.map { it.kind }.toSet())
                        } else { assertEquals("NONE", row.protectedState); assertNull(row.protectedEvidence) }
                    }
                    assertTrue(MessageStore(db).messages("other").isEmpty())
                } finally { db.close() }
            }
        }
    }

    @Test fun distinctMamResultUidsRetainTraversalWithoutDuplicatingProtectedSentContent() = runBlocking {
        for (protocol in OmemoProtocol.entries) fixture { name, db ->
            val store = MessageStore(db)
            archive(store, "r0" to ProtectedFixtures.carried(protocol, ProtectedCarrierKind.MAM, true, "r0"))
            archive(store, "r1" to ProtectedFixtures.carried(protocol, ProtectedCarrierKind.MAM, true, "r1"),
                "r2" to ProtectedFixtures.carried(protocol, ProtectedCarrierKind.MAM, true, "r2"))
            db.close()
            val reopened = NemaDatabase.create(context, name)
            try {
                val row = MessageStore(reopened).messages("account").single()
                assertEquals("AQID", row.protection()!!.content!!.payload)
                assertEquals(listOf("r0", "r1", "r2"), reopened.messageDao().archiveRecords(
                    "account", ProtectedFixtures.self, ACCOUNT_ARCHIVE_SCOPE, Long.MIN_VALUE, Long.MAX_VALUE).map { it.resultId })
                assertEquals("r2", MessageStore(reopened).archiveCursor(ArchiveCursorKey("account", ProtectedFixtures.self, ACCOUNT_ARCHIVE_SCOPE))!!.newestId)
            } finally { reopened.close() }
        }
    }

    @Test fun scopedAliasesCannotMergeOtherAccountPeerSenderKindOrCiphertext() = runBlocking {
        for (difference in listOf("account", "peer", "sender", "kind", "ciphertext", "fallback")) fixture { _, db ->
            val store = MessageStore(db)
            val first = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).toIncomingMessage("first")
            store.ingest(first)
            val before = store.messages("account").single()
            val candidate = first.copy(localMessageId = "candidate").let {
                when (difference) {
                    "account" -> it.copy(accountId = "other")
                    "peer" -> it.copy(peerJid = "different@example.org")
                    "sender" -> it.copy(senderJid = "different@example.org")
                    "kind" -> it.copy(messageKind = MessageKind.GROUPCHAT)
                    "fallback" -> it.copy(body = "other fallback")
                    else -> it.copy(protection = it.protection!!.copy(content = it.protection.content!!.copy(payload = "BAUG")))
                }
            }
            val outcome = store.ingest(candidate)
            assertTrue(outcome.inserted)
            assertEquals(difference != "account", outcome.identityConflict)
            assertEquals(before, store.messages("account").single { it.localMessageId == "first" })
            assertEquals(2, store.messages("account").size + store.messages("other").size)
        }
    }

    @Test fun onlineIdentitylessRepairExcludesProtectedButStillMergesOrdinaryClosedPair() = runBlocking {
        for (protected in listOf(false, true)) fixture { name, db ->
            val store = MessageStore(db, clock = { 31_001 })
            val live = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).toIncomingMessage("live").copy(
                aliases = emptyList(), body = "same", sentAtEpochMs = 1_000, sentTimeSource = MessageTimeSource.LOCAL)
                .let { if (protected) it else it.copy(protection = null) }
            store.ingest(live)
            val mam = live.copy(localMessageId = "mam", sentTimeSource = MessageTimeSource.MAM)
            val result = store.applyArchivePage(ArchivePage(ArchiveCursorKey("account", ProtectedFixtures.self, ACCOUNT_ARCHIVE_SCOPE),
                ArchiveDirection.BOOTSTRAP, null, true, false, true, "r1", "r1", listOf(ArchivedIncomingMessage("r1", mam))))
            assertEquals(ArchivePageStatus.APPLIED, result.status)
            assertEquals(if (protected) 1 else 0, result.inserted)
            db.close()
            val reopened = NemaDatabase.create(context, name)
            try { assertEquals(if (protected) 2 else 1, MessageStore(reopened).messages("account").size) }
            finally { reopened.close() }
        }
    }

    @Test fun ordinaryReceiptsAndReplyPreparationCannotTurnProtectedRowsIntoOrdinaryTargets() = runBlocking {
        fixture { _, db ->
            val store = MessageStore(db)
            val protected = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).toIncomingMessage("protected")
            store.ingest(protected)
            store.recordReceiptSignal("account", ProtectedFixtures.peer, ProtectedFixtures.self, "wire", MessageReceiptStage.DISPLAYED)
            assertFalse(store.messages("account").single().locallyRead)
            val outbound = ProtectedFixtures.carried(OmemoProtocol.MODERN, ProtectedCarrierKind.SENT_CARBON, true,
                id = "sent-wire").toIncomingMessage("sent")
            store.ingest(outbound)
            assertNull(store.recordReceiptSignal("account", ProtectedFixtures.peer, ProtectedFixtures.peer, "sent-wire", MessageReceiptStage.RECEIVED))
            assertTrue(store.outboxes("account").isEmpty())
            val reply = OutboundIntent("account", "reply-op", "reply", "reply-origin", ProtectedFixtures.peer,
                ProtectedFixtures.self, MessageKind.CHAT, null, null, "reply", replyToId = "wire", replyToJid = ProtectedFixtures.peer)
            assertTrue(runCatching { store.compose(reply) }.isFailure)
            assertTrue(store.outboxes("account").isEmpty())
            store.ingest(protected.copy(localMessageId = "ordinary", protection = null, body = "ordinary",
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ProtectedFixtures.peer, "ordinary-wire"))))
            store.recordReceiptSignal("account", ProtectedFixtures.peer, ProtectedFixtures.self, "ordinary-wire", MessageReceiptStage.DISPLAYED)
            assertTrue(store.messages("account").single { it.localMessageId == "ordinary" }.locallyRead)
            store.compose(reply.copy(replyToId = "ordinary-wire"))
            assertEquals(OutboxStatus.PENDING, store.outbox("account", "reply-op")!!.status)
            store.ingest(outbound.copy(localMessageId = "ordinary-sent", protection = null, body = "ordinary",
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ProtectedFixtures.self, "ordinary-sent-wire"))))
            assertNotNull(store.recordReceiptSignal("account", ProtectedFixtures.peer, ProtectedFixtures.peer,
                "ordinary-sent-wire", MessageReceiptStage.RECEIVED))
            db.messageDao().insertTrustedAlias(TrustedIdentityAliasEntity("account", IdentityAliasKind.MAM_RESULT,
                "archive-only", "private-uid", "protected", IdentityAliasStatus.TRUSTED))
            assertEquals(OutboxStatus.PENDING, store.compose(reply.copy(operationId = "unknown-reply", localMessageId = "unknown-reply",
                originId = "unknown-origin", replyToId = "private-uid")).status)
        }
    }

    @Test fun metadataCannotOverwriteTupleAndZeroAffectedProtectedUpdateRollsBackAliasChanges() = runBlocking {
        fixture { name, db ->
            val store = MessageStore(db)
            val incoming = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).toIncomingMessage("protected")
            store.ingest(incoming)
            val row = store.messages("account").single()
            db.messageDao().updateMessage(row.copy(protectedState = "NONE", protectedEvidence = null,
                attachmentName = "ordinary metadata"))
            assertEquals(row.protectedEvidence, store.messages("account").single().protectedEvidence)
            assertEquals("ordinary metadata", store.messages("account").single().attachmentName)
            assertEquals(0, db.messageDao().updateProtectedContent("other", row.localMessageId, "REJECTED", "invalid"))
            assertEquals(0, db.messageDao().updateProtectedContent("account", "missing", "REJECTED", "invalid"))
            val aliases = store.aliases("account")
            val before = store.messages("account")
            val replay = incoming.copy(localMessageId = "replay", aliases = incoming.aliases +
                TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ProtectedFixtures.peer, "new-alias"))
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER ignore_protected_update
                BEFORE UPDATE OF protectedState, protectedEvidence ON messages BEGIN SELECT RAISE(IGNORE); END""")
            assertTrue(runCatching { store.ingest(replay) }.isFailure)
            db.close()
            val reopened = NemaDatabase.create(context, name)
            try {
                assertEquals(before, MessageStore(reopened).messages("account"))
                assertEquals(aliases, MessageStore(reopened).aliases("account"))
                reopened.openHelper.writableDatabase.execSQL("DROP TRIGGER ignore_protected_update")
                assertFalse(MessageStore(reopened).ingest(replay).inserted)
                assertEquals(aliases.size + 1, MessageStore(reopened).aliases("account").size)
            } finally { reopened.close() }
        }
    }

    @Test fun partialAndUnknownTuplesCannotDowngradeOrAcquireOrdinaryOwners() = runBlocking {
        fixture { _, db ->
            val store = MessageStore(db)
            val evidence = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).protection!!
            val encoded = ProtectedContentCodec.encode(evidence)
            for ((index, tuple) in listOf("NONE" to encoded, "FUTURE" to encoded, "UNSUPPORTED_PAYLOAD" to null).withIndex()) {
                val incoming = ProtectedFixtures.envelope(OmemoProtocol.LEGACY, id = "wire-$index")
                    .toIncomingMessage("owner-$index").copy(body = "fallback")
                store.ingest(incoming)
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE messages SET protectedState = ?, protectedEvidence = ? WHERE accountId = ? AND localMessageId = ?",
                    arrayOf<Any?>(tuple.first, tuple.second, "account", incoming.localMessageId))
                assertTrue(store.ingest(incoming.copy(localMessageId = "ordinary-$index", protection = null)).identityConflict)
                val row = store.messages("account").single { it.localMessageId == incoming.localMessageId }
                assertTrue(row.isProtected()); assertNull(row.protection())
                assertNotEquals("NONE", db.messageDao().observeDirectTimeline("account", ProtectedFixtures.peer)
                    .first().single { it.localMessageId == incoming.localMessageId }.protectedState)
            }
        }
    }

    @Test fun malformedStoredRecordsStayInertAndCannotMergeOnReplay() = runBlocking {
        fixture { name, db ->
            val evidence = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).protection!!
            val mutations = MalformedProtectedRecords.from(ProtectedContentCodec.encode(evidence))
            val store = MessageStore(db)
            for ((index, entry) in mutations.entries.withIndex()) {
                val incoming = ProtectedFixtures.envelope(OmemoProtocol.LEGACY, id = "wire-$index").toIncomingMessage("owner-$index")
                store.ingest(incoming)
                db.openHelper.writableDatabase.execSQL("UPDATE messages SET protectedEvidence = ? WHERE accountId = ? AND localMessageId = ?",
                    arrayOf(entry.value, "account", incoming.localMessageId))
                assertTrue(entry.key, store.ingest(incoming.copy(localMessageId = "replay-$index")).identityConflict)
            }
            db.close()
            val reopened = NemaDatabase.create(context, name)
            try {
                val rows = MessageStore(reopened).messages("account")
                assertEquals(mutations.size * 2, rows.size)
                mutations.values.forEachIndexed { index, raw ->
                    val row = rows.single { it.localMessageId == "owner-$index" }
                    assertEquals(raw, row.protectedEvidence); assertTrue(row.isProtected()); assertNull(row.protection())
                }
            } finally { reopened.close() }
        }
    }
}
