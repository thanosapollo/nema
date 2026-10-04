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
import org.thanosapollo.nema.chat.toIncomingMessage
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.omemo.*
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectedMessageStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private suspend fun fixture(block: suspend (String, NemaDatabase) -> Unit) {
        SmackAndroid.initialize(context)
        installNemaOmemoProviders()
        val name = "protected-${UUID.randomUUID()}.db"
        val db = NemaDatabase.create(context, name)
        try {
            db.openHelper.writableDatabase.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('account', 'account@example.org', 'account', 'example.org')")
            block(name, db)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun accountIdentitylessRepairSkipsProtectedRowsButKeepsItsOrdinaryPositive() = runBlocking {
        fixture { _, db ->
            val dao = db.messageDao()
            db.accountDao().insertReconciliationState(AccountReconciliationStateEntity("account", IDENTITYLESS_LIVE_MAM_REPAIR))
            dao.insertPeer(PeerEntity("account", ProtectedFixtures.peer))
            val evidence = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).protection!!
            var sequence = 0L
            for (protected in listOf(false, true)) {
                val prefix = if (protected) "protected" else "ordinary"
                val row = MessageEntity("account", "$prefix-live", ProtectedFixtures.peer, ProtectedFixtures.peer,
                    MessageDirection.INBOUND, MessageKind.CHAT, null, null, prefix, ++sequence, null,
                    sentAtEpochMs = 1000, sentTimeSource = MessageTimeSource.LOCAL,
                    reconciliationObservedAtMs = 1000, liveDeliveryObserved = true,
                    protectedState = if (protected) evidence.state.name else "NONE",
                    protectedEvidence = if (protected) ProtectedContentCodec.encode(evidence) else null)
                dao.insertMessage(row)
                dao.insertMessage(row.copy(localMessageId = "$prefix-mam", localSequence = ++sequence,
                    sentTimeSource = MessageTimeSource.MAM, reconciliationObservedAtMs = null,
                    liveDeliveryObserved = false, archiveOrdinal = sequence))
                val key = ArchiveCursorKey("account", ProtectedFixtures.self, "ACCOUNT")
                dao.insertTrustedAlias(TrustedIdentityAliasEntity("account", IdentityAliasKind.MAM_RESULT,
                    key.aliasAuthority(), "$prefix-uid", "$prefix-mam", IdentityAliasStatus.TRUSTED))
                dao.insertArchivePosition(ArchiveMessagePositionEntity("account", key.archiveAuthority, key.scope, sequence, "$prefix-mam"))
            }
            assertEquals(1L, MessageStore(db).repairIdentitylessDuplicates("account")!!.matchedCount)
            assertEquals(setOf("ordinary-live", "protected-live", "protected-mam"), dao.messages("account").map { it.localMessageId }.toSet())
        }
    }

    @Test fun nativeMamThenLiveReplayTraversesArchiveSynchronizerAndRetainsOneOwner() = runBlocking {
        fixture { name, db ->
            installNemaMamResultProvider()
            val wire = "<message xmlns='jabber:client' from='account@example.org'>" +
                "<result xmlns='urn:xmpp:mam:2' queryid='q' id='r1'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' from='peer@example.org' to='account@example.org' type='chat' id='wire'>" +
                ProtectedFixtures.encrypted(OmemoProtocol.MODERN) +
                "<received xmlns='urn:xmpp:receipts' id='ordinary'/></message></forwarded></result></message>"
            val carrier = org.jivesoftware.smack.util.PacketParserUtils.parseStanza<org.jivesoftware.smack.packet.Message>(wire)
            val result = org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension.from(carrier)
            val normalized = normalizeMamResults(listOf(carrier), listOf(result), ProtectedFixtures.attempt,
                ProtectedFixtures.self, false)
            assertNull(normalized.single().signal)
            val store = MessageStore(db)
            val sync = org.thanosapollo.nema.chat.ArchiveSynchronizer(store,
                discover = { org.thanosapollo.nema.xmpp.transport.SessionCapabilities(true,
                    org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState.UNSUPPORTED, false) },
                query = { request -> org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope(request,
                    true, true, false, "r1", "r1", normalized) }, localIds = { "archived" })
            sync.synchronize(org.thanosapollo.nema.session.SessionIdentity(ProtectedFixtures.attempt.accountId,
                ProtectedFixtures.attempt.generation), ProtectedFixtures.self, isAuthoritative = { true })
            assertTrue(sync.state.value is org.thanosapollo.nema.chat.ArchiveSyncState.Ready)
            assertEquals(1, store.messages("account").size)
            assertFalse(store.ingest(ProtectedFixtures.envelope(OmemoProtocol.MODERN).toIncomingMessage("live")).inserted)
            val retained = store.messages("account").single().protection()!!
            assertEquals(setOf(ProtectedCarrierKind.MAM, ProtectedCarrierKind.LIVE), retained.carriers.map { it.kind }.toSet())
            assertEquals("r1", retained.carriers.single { it.kind == ProtectedCarrierKind.MAM }.resultId)
            db.close()
            val reopened = NemaDatabase.create(context, name)
            try {
                assertEquals(retained, MessageStore(reopened).messages("account").single().protection())
                assertEquals("r1", MessageStore(reopened).archiveCursor(ArchiveCursorKey("account", ProtectedFixtures.self,
                    org.thanosapollo.nema.xmpp.transport.ACCOUNT_ARCHIVE_SCOPE))!!.newestId)
            } finally { reopened.close() }
        }
    }

    @Test fun nativeContentSurvivesMetadataReplayAndReopenWithoutChangingOrdinaryControls() = runBlocking {
        fixture { name, db ->
            val store = MessageStore(db)
            for (protocol in OmemoProtocol.entries) {
                val incoming = ProtectedFixtures.envelope(protocol, id = protocol.name).toIncomingMessage(protocol.name)
                assertTrue(store.ingest(incoming).inserted)
                val carbon = incoming.protection!!.copy(carriers = listOf(ProtectedCarrier(
                    ProtectedCarrierKind.RECEIVED_CARBON, ProtectedFixtures.peer, ProtectedFixtures.self,
                    ProtectedFixtures.self, "${ProtectedFixtures.self}/test")))
                assertFalse(store.ingest(incoming.copy(localMessageId = "replay-${protocol.name}", protection = carbon)).inserted)
                val row = store.messages("account").single { it.localMessageId == protocol.name }
                assertEquals(setOf(ProtectedCarrierKind.LIVE, ProtectedCarrierKind.RECEIVED_CARBON), row.protection()!!.carriers.map { it.kind }.toSet())
                assertEquals(incoming.protection.content, row.protection()!!.content)
                db.messageDao().updateMessage(row.copy(body = row.body, attachmentName = "metadata"))
                assertEquals(row.protectedEvidence, store.messages("account").single { it.localMessageId == protocol.name }.protectedEvidence)
            }
            db.close()
            val reopened = NemaDatabase.create(context, name)
            try {
                val rows = MessageStore(reopened).messages("account")
                assertEquals(2, rows.size)
                assertTrue(rows.all { it.protection()?.content?.payload == "AQID" })
                assertTrue(ChatRepository(reopened).cachedConversations("account").all {
                    it.preview == ProtectedState.UNSUPPORTED_PAYLOAD.status && it.previewSender == null
                })
            } finally { reopened.close() }
        }
    }

    @Test fun conflictingCiphertextAndOrdinaryFallbackNeverMergeThroughAliases() = runBlocking {
        fixture { _, db ->
            val store = MessageStore(db)
            val first = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).toIncomingMessage("first").copy(body = "fallback")
            store.ingest(first)
            val second = ProtectedFixtures.envelope(OmemoProtocol.LEGACY, "BAUG").toIncomingMessage("second").copy(body = "fallback")
            val result = store.ingest(second)
            assertTrue(result.identityConflict)
            assertEquals(2, store.messages("account").size)
            val ordinary = first.copy(localMessageId = "ordinary", protection = null)
            store.ingest(ordinary)
            assertEquals(3, store.messages("account").size)
            assertEquals("AQID", store.messages("account").single { it.localMessageId == "first" }.protection()!!.content!!.payload)
            assertEquals("BAUG", store.messages("account").single { it.localMessageId == "second" }.protection()!!.content!!.payload)
        }
    }

    @Test fun aliasMergeTransfersEvidenceAtomicallyAndRollsBackEveryDestructiveBoundary() = runBlocking {
        for (boundary in listOf(MessageWriteBoundary.AFTER_DEPENDENT_REPARENT, MessageWriteBoundary.AFTER_PROTECTED_CONTENT)) {
            fixture { name, db ->
                val first = ProtectedFixtures.envelope(OmemoProtocol.MODERN, id = "one").toIncomingMessage("first")
                val second = first.copy(localMessageId = "second", aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ProtectedFixtures.peer, "two")),
                    protection = first.protection!!.copy(carriers = listOf(ProtectedCarrier(ProtectedCarrierKind.MAM,
                        ProtectedFixtures.peer, ProtectedFixtures.self, archiveAuthority = ProtectedFixtures.self, resultId = "r1", archiveScope = "ACCOUNT"))))
                val store = MessageStore(db)
                store.ingest(first)
                store.ingest(second)
                val merge = first.copy(localMessageId = "merge", aliases = first.aliases + second.aliases)
                val before = store.messages("account")
                val failing = MessageStore.observingWrites(db) { if (it == boundary) error("injected") }
                assertTrue(runCatching { failing.ingest(merge) }.isFailure)
                assertEquals(before, store.messages("account"))
                db.close()
                val reopened = NemaDatabase.create(context, name)
                try {
                    assertEquals(before, MessageStore(reopened).messages("account"))
                    MessageStore(reopened).ingest(merge)
                    val retained = MessageStore(reopened).messages("account").single().protection()!!
                    assertEquals(setOf(ProtectedCarrierKind.LIVE, ProtectedCarrierKind.MAM), retained.carriers.map { it.kind }.toSet())
                } finally { reopened.close() }
            }
        }
    }

    @Test fun headerOnlyIsVisibleWithoutUnreadAndSentCarbonCannotReadThrough() = runBlocking {
        fixture { _, db ->
            val store = MessageStore(db)
            val ordinary = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).toIncomingMessage("ordinary")
                .copy(protection = null, body = "ordinary", aliases = emptyList())
            store.ingest(ordinary)
            val header = ProtectedFixtures.envelope(OmemoProtocol.LEGACY, null, "header").toIncomingMessage("header")
            store.ingest(header)
            assertFalse(store.messages("account").single { it.localMessageId == "header" }.unreadEligible)
            val sent = header.copy(localMessageId = "sent", aliases = emptyList(), senderJid = ProtectedFixtures.self,
                direction = MessageDirection.OUTBOUND, sentAtEpochMs = 1, sentTimeSource = MessageTimeSource.CARBON)
            store.ingest(sent)
            assertFalse(store.messages("account").single { it.localMessageId == "ordinary" }.locallyRead)
            store.ingest(sent.copy(localMessageId = "ordinary-sent", protection = null, body = "sent"))
            assertTrue(store.messages("account").single { it.localMessageId == "ordinary" }.locallyRead)
        }
    }

    @Test fun malformedTupleAndRejectedPresenceCannotAcquireAnotherOwner() = runBlocking {
        fixture { _, db ->
            val store = MessageStore(db)
            val first = ProtectedFixtures.envelope(OmemoProtocol.MODERN).toIncomingMessage("first")
            store.ingest(first)
            db.openHelper.writableDatabase.execSQL("UPDATE messages SET protectedEvidence = '{\"version\":99}' WHERE localMessageId = 'first'")
            assertTrue(store.ingest(first.copy(localMessageId = "second")).identityConflict)
            assertEquals(2, store.messages("account").size)
            assertNull(store.messages("account").single { it.localMessageId == "first" }.protection())
        }
    }
}
