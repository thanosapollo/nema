package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.chat.toIncomingMessage
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.transport.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MucCorrectionMigrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)
    private val room = "room@conference.example.org"
    private val key = ArchiveCursorKey("a", room, room)

    @Test
    fun schema25RowsAndDependentsSurviveDormantMigrationAndActualPageReplay() = replay(false)

    @Test
    fun schema25RowsSafelyEnrichThroughActualPageReplay() = replay(true)

    @Test
    fun schema25RowsSafelyEnrichThroughAuthenticatedWirePage() = replay(true, wire = true)

    private fun replay(enrich: Boolean, wire: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "muc-migration-${UUID.randomUUID()}.db"
        try {
            val before = migrations.createDatabase(name, 25).use { sql ->
                sql.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('a', 'self@example.org', 'self', 'example.org')")
                sql.execSQL("INSERT INTO peers (accountId, jid, room, lastReadLocalSequence) VALUES ('a', '$room', 1, 1)")
                for ((index, id) in listOf("root", "edit").withIndex()) {
                    // Schema 25 stored a MUC correction's fallback body, without accepted replaceId.
                    sql.execSQL("""INSERT INTO messages (accountId, localMessageId, peerJid, senderJid,
                        direction, messageKind, body, localSequence, archiveOrdinal, markable,
                        directSessionTransitionApplied, liveDeliveryObserved, locallyRead, sentAtEpochMs, sentTimeSource)
                        VALUES ('a', '$id', '$room', '$room/nick', 'INBOUND', 'GROUPCHAT', '$id body',
                        ${index + 1}, $index, 0, 0, 0, ${1 - index}, 1234, 'MAM')""")
                    sql.execSQL("INSERT INTO archive_message_positions VALUES ('a', '$room', '$room', $index, '$id')")
                    for ((kind, authority) in listOf("STANZA_ID" to room, "MAM_RESULT" to key.aliasAuthority())) {
                        sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', '$kind', '$authority', '$id-uid', '$id', 'TRUSTED')")
                    }
                }
                sql.execSQL("INSERT INTO account_message_sequences VALUES ('a', 3)")
                sql.execSQL("INSERT INTO archive_cursors VALUES ('a', '$room', '$room', 'root-uid', 'edit-uid', 0, NULL, 0, 1)")
                sql.execSQL("INSERT INTO message_outbox VALUES ('a', 'op', 'root', 'origin', 'PENDING', NULL, 0, NULL, NULL)")
                sql.execSQL("INSERT INTO message_reactions VALUES ('a', '$room', '$room/nick', 'root', 'root', 'root-uid', '[]', 123, 1)")
                sql.execSQL("INSERT INTO message_drafts (accountId, peerJid, messageKind, threadKey, body) VALUES ('a', '$room', 'GROUPCHAT', '', 'draft')")
                snapshot(sql)
            }
            var db = NemaDatabase.create(context, name)
            try {
                val sql = db.openHelper.writableDatabase
                assertEquals(28, sql.version)
                assertEquals(before, snapshot(sql))
                assertUnknown(sql)
                var store = MessageStore(db)
                val rows = store.messages("a")
                val aliases = store.aliases("a").toSet()
                val positions = db.messageDao().archivePositions("a").toSet()
                // A redacted new boundary makes the overlapping page advance without adding a message.
                val syntheticPage = ArchivePage(key, ArchiveDirection.AFTER, "edit-uid", true, false, true,
                    "root-uid", "next-uid", listOf("root", "edit").map { id ->
                        ArchivedIncomingMessage("$id-uid", IncomingMessage("a", "replay-$id", room,
                            "$room/nick", MessageDirection.INBOUND, MessageKind.GROUPCHAT, null, null,
                            "$id body", null, listOf(TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, room, "$id-uid")),
                            sentAtEpochMs = 1234, sentTimeSource = org.thanosapollo.nema.xmpp.transport.MessageTimeSource.MAM,
                            mucFacts = if (enrich) MucEventFacts("wire-$id", if (id == "edit") "wire-root" else null,
                                if (id == "edit") MucClaimState.VALID else MucClaimState.NONE, "opaque",
                                MucOccupantEvidence.ROOM_MAM, MucPayloadState.PLAIN) else null))
                    } + ArchivedIncomingMessage("next-uid", null))
                val page = if (!wire) syntheticPage else syntheticPage.copy(messages = wirePage() + ArchivedIncomingMessage("next-uid", null))
                val preservedTables = before.keys - "archive_cursors" - if (wire) setOf("trusted_identity_aliases") else emptySet()
                if (enrich) {
                    sql.execSQL("CREATE TRIGGER reject_cursor BEFORE INSERT ON archive_cursors BEGIN SELECT RAISE(ABORT, 'fault'); END")
                    assertTrue(runCatching { store.applyArchivePage(page) }.isFailure)
                    assertEquals(before, snapshot(sql))
                    assertUnknown(sql)
                    sql.execSQL("DROP TRIGGER reject_cursor")
                    db.close()
                    db = NemaDatabase.create(context, name)
                    store = MessageStore(db)
                }
                assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page).status)
                repeat(2) {
                    if (!enrich) assertEquals(rows, store.messages("a"))
                    else store.messages("a").forEach { row ->
                        assertEquals("wire-${row.localMessageId}", row.mucMessageId)
                        assertEquals("opaque", row.mucOccupantId)
                        assertEquals(MucPayloadState.PLAIN, row.mucPayloadState)
                        assertNull(row.mucLiveOrderEpoch)
                        assertEquals(if (wire && row.localMessageId == "edit") "wire-root" else null, row.replaceId)
                        assertEquals(if (wire && row.localMessageId == "edit") "root" else null, row.correctionTargetMessageId)
                    }
                    if (!wire) assertEquals(aliases, store.aliases("a").toSet())
                    else {
                        assertTrue(store.aliases("a").containsAll(aliases))
                        assertEquals(setOf("wire-root", "wire-edit"), (store.aliases("a").toSet() - aliases).map { it.value }.toSet())
                        assertTrue((store.aliases("a").toSet() - aliases).all { it.kind == IdentityAliasKind.MESSAGE_ID })
                    }
                    assertEquals(positions, db.messageDao().archivePositions("a").toSet())
                    assertEquals("next-uid", store.archiveCursor(key)?.newestId)
                    val after = snapshot(db.openHelper.writableDatabase)
                    assertEquals(before.filterKeys { it in preservedTables }, after.filterKeys { it in preservedTables })
                    if (!enrich) assertUnknown(db.openHelper.writableDatabase)
                    db.close()
                    db = NemaDatabase.create(context, name)
                    store = MessageStore(db)
                }
                if (enrich) {
                    val retainedRows = store.messages("a")
                    val omitted = page.copy(boundaryId = "next-uid", lastId = "final-uid",
                        messages = page.messages.take(2).map { archived -> archived.copy(
                            message = archived.message!!.copy(mucFacts = MucEventFacts(claim = MucClaimState.NONE)))
                        } + ArchivedIncomingMessage("final-uid", null))
                    assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(omitted).status)
                    assertEquals(retainedRows, store.messages("a"))
                    assertEquals(before.filterKeys { it in preservedTables }, snapshot(db.openHelper.writableDatabase).filterKeys { it in preservedTables })
                    val changed = page.messages[1].message!!.copy(localMessageId = "actor-conflict",
                        mucFacts = page.messages[1].message!!.mucFacts!!.copy(occupantId = "different"))
                    store.ingest(changed)
                    val edit = store.messages("a").single { it.localMessageId == "edit" }
                    assertEquals(MucOccupantEvidence.CONFLICT, edit.mucOccupantEvidence)
                    assertEquals("opaque", edit.mucOccupantId)
                    assertEquals(2, store.messages("a").size)
                    db.close()
                    db = NemaDatabase.create(context, name)
                    assertEquals(edit, MessageStore(db).messages("a").single { it.localMessageId == "edit" })
                }
                // Storage-only round trip: typed retained facts survive partial metadata writes and reopen.
                val retained = rows.first().copy(mucMessageId = "wire", mucReplaceId = "claim",
                    mucClaimState = MucClaimState.VALID, mucOccupantId = "opaque",
                    mucOccupantEvidence = MucOccupantEvidence.BOTH, mucPayloadState = MucPayloadState.PLAIN,
                    mucLiveOrderEpoch = "test-insertion", mucCorrectionSelected = true)
                db.messageDao().updateMessage(retained)
                db.close()
                db = NemaDatabase.create(context, name)
                assertEquals(retained, MessageStore(db).messages("a").first())
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun wirePage(): List<ArchivedIncomingMessage> {
        installNemaSidProviders()
        installNemaMamResultProvider()
        installNemaOccupantIdProvider()
        installNemaCorrectionProvider()
        val attempt = SessionAttemptIdentity(AccountId.require("a"), ConnectionGeneration.require(1),
            ConnectionAttempt.require(1), LifecycleEpoch.require(1))
        val registry = RoomStableIdAuthorityRegistry().apply { begin(attempt) }
        val lease = requireNotNull(registry.beginJoin(attempt, room))
        check(registry.publish(lease, true, true, "self", true))
        val facts = copyRoomConsumerFacts(Any(), registry, attempt, room)
        val carriers = listOf("root", "edit").map { id ->
            val claim = if (id == "edit") "<replace xmlns='urn:xmpp:message-correct:0' id='wire-root'/>" else ""
            org.jivesoftware.smack.util.PacketParserUtils.parseStanza(
                "<message xmlns='jabber:client' from='$room'><result xmlns='urn:xmpp:mam:2' queryid='query' id='$id-uid'>" +
                    "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='1970-01-01T00:00:01.234Z'/>" +
                    "<message xmlns='jabber:client' from='$room/nick' type='groupchat' id='wire-$id'><body>$id body</body>" +
                    "$claim<occupant-id xmlns='urn:xmpp:occupant-id:0' id='opaque'/></message></forwarded></result></message>"
            ) as org.jivesoftware.smack.packet.Message
        }
        return normalizeMamResults(carriers, carriers.map { org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension.from(it) },
            attempt, room, true, mappingBareJid = "self@example.org", archiveRoom = facts).map {
            ArchivedIncomingMessage(it.resultId, it.message!!.toIncomingMessage("replay-${it.resultId}"))
        }
    }

    private fun assertUnknown(sql: SupportSQLiteDatabase) {
        sql.query("SELECT mucMessageId, mucReplaceId, mucClaimState, mucOccupantId, mucOccupantEvidence, mucPayloadState, mucLiveOrderEpoch, mucCorrectionSelected FROM messages").use { c ->
            while (c.moveToNext()) {
                for (column in listOf(0, 1, 3, 6)) assertTrue(c.isNull(column))
                for (column in listOf(2, 4, 5)) assertEquals("UNKNOWN", c.getString(column))
                assertEquals(0, c.getInt(7))
            }
        }
    }

    // Compare every old column, not just row counts; never fabricate schema 25 by rewinding a new DB.
    private fun snapshot(sql: SupportSQLiteDatabase): Map<String, List<List<String?>>> {
        val result = mutableMapOf<String, List<List<String?>>>()
        for (table in listOf("accounts", "peers", "messages", "archive_message_positions", "trusted_identity_aliases",
            "message_outbox", "message_reactions", "message_drafts", "message_threads", "account_message_sequences", "archive_cursors")) {
            sql.query("SELECT * FROM $table ORDER BY rowid").use { c ->
                val columns = c.columnNames.indices.filterNot { c.columnNames[it].startsWith("muc") ||
                    c.columnNames[it] in setOf("replaceId", "correctionTargetMessageId") }
                result[table] = buildList { while (c.moveToNext()) add(columns.map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        }
        return result
    }
}
