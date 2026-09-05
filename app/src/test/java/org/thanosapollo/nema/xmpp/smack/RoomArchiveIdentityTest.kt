package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.toIncomingMessage
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.xmpp.transport.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomArchiveIdentityTest {
    private val self = "self@example.org"
    private val room = "room@conference.example.org"
    private val attempt = SessionAttemptIdentity(AccountId.require("account"), ConnectionGeneration.require(1), ConnectionAttempt.require(1), LifecycleEpoch.require(1))
    private val key = ArchiveCursorKey("account", room, room)

    private fun parse(xml: String): Message {
        installNemaSidProviders()
        installNemaMamResultProvider()
        return PacketParserUtils.parseStanza(xml) as Message
    }

    private fun inner(nick: String, sid: String = "") =
        "<message xmlns='jabber:client' from='$room/$nick' type='groupchat'><body>synthetic</body>$sid</message>"

    private fun carrier(nick: String, sid: String = "") = parse(
        "<message xmlns='jabber:client' from='$room'><result xmlns='urn:xmpp:mam:2' queryid='query' id='archive-1'>" +
            "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>" +
            inner(nick, sid) + "</forwarded></result></message>",
    )

    private fun facts(mam: Boolean = true): RoomConsumerFacts {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(attempt) }
        val lease = requireNotNull(registry.beginJoin(attempt, room))
        check(registry.publish(lease, true, false, "self", mam))
        return requireNotNull(copyRoomConsumerFacts(Any(), registry, attempt, room))
    }

    private fun normalized(carrier: Message, captured: RoomConsumerFacts? = facts()) = normalizeMamResults(
        listOf(carrier), listOf(MamResultExtension.from(carrier)), attempt, room, true,
        mappingBareJid = self, ownRoomNick = "self", receivedAtEpochMs = 1_767_225_600_000,
        archiveRoom = requireCurrentArchiveRoom(captured, captured, attempt, room),
    ).single()

    @Test
    fun roomClaimsAndCollectedCarriersFailClosed() {
        val valid = "<stanza-id xmlns='urn:xmpp:sid:0' by='$room' id='archive-1'/>"
        assertEquals(listOf(StanzaIdEnvelope("archive-1", room)), normalized(carrier("other", valid)).message?.stanzaIds)
        assertEquals(listOf(StanzaIdEnvelope("archive-1", room)), normalized(carrier("other", valid.replace(room, "foreign@example.org"))).message?.stanzaIds)
        for (sid in listOf(valid.replace("archive-1", "contradiction"), valid + valid,
            valid.replace(" id='archive-1'", ""), valid.replace("/>", "><bad/></stanza-id>"))) {
            assertThrows(IllegalArgumentException::class.java) { normalized(carrier("other", sid)) }
        }
        val source = carrier("other")
        for (sender in listOf("$room/nick", "other@conference.example.org")) {
            val wrong = source.asBuilder().from(sender).build()
            assertThrows(IllegalArgumentException::class.java) { normalized(wrong) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeMamResults(listOf(source), listOf(MamResultExtension.from(carrier("other"))), attempt, room, true,
                mappingBareJid = self, archiveRoom = facts())
        }
        // Smack's real collector filter owns the generated query ID, not the sender.
        val filter = org.jivesoftware.smackx.mam.filter.MamResultFilter(
            org.jivesoftware.smackx.mam.element.MamQueryIQ("query"))
        assertTrue(filter.accept(source))
        for (query in listOf("queryid='wrong'", "")) {
            val wrong = parse(source.toXML().toString().replace("queryid='query'", query))
            assertFalse(filter.accept(wrong))
        }
    }

    @Test
    fun capabilityAndLeaseAreRoomAndAttemptScoped() {
        val noMam = org.jivesoftware.smackx.disco.packet.DiscoverInfo().apply { addFeature("urn:xmpp:sid:0") }
        assertFalse(roomFeatureSupport(noMam).mamV2)
        noMam.addFeature("urn:xmpp:mam:2")
        assertTrue(roomFeatureSupport(noMam).mamV2)
        assertFalse(RoomFeatureSupport(false, false).mamV2) // discovery failure fallback
        for (absent in listOf(null, facts(false))) assertTrue(normalized(carrier("other"), absent).message!!.stanzaIds.isEmpty())
        val registry = RoomStableIdAuthorityRegistry().apply { begin(attempt) }
        val lease = requireNotNull(registry.beginJoin(attempt, room))
        assertTrue(registry.publish(lease, true, false, mamV2 = true))
        val captured = copyRoomConsumerFacts(Any(), registry, attempt, room)
        assertNull(copyRoomConsumerFacts(Any(), registry, attempt.copy(accountId = AccountId.require("other")), room))
        assertNull(copyRoomConsumerFacts(Any(), registry, attempt, "other@conference.example.org"))
        val replacement = requireNotNull(registry.beginJoin(attempt, room))
        registry.publish(replacement, true, false, mamV2 = true)
        assertThrows(IllegalArgumentException::class.java) {
            requireCurrentArchiveRoom(captured, copyRoomConsumerFacts(Any(), registry, attempt, room), attempt, room)
        }
        registry.retireAll()
        assertThrows(IllegalArgumentException::class.java) { requireCurrentArchiveRoom(captured, null, attempt, room) }
        assertThrows(IllegalArgumentException::class.java) { requireCurrentArchiveRoom(captured, captured, attempt, self) }
        val accountCarrier = carrier("other").asBuilder().from(self).build()
        assertTrue(normalizeMamResults(listOf(accountCarrier), listOf(MamResultExtension.from(accountCarrier)),
            attempt, self, true).single().message!!.stanzaIds.isEmpty())
    }

    @Test
    fun pageFailureRollsBackAndConflictingOrQuarantinedIdentityDoesNotMerge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (mode in listOf("fault", "conflict", "quarantine")) {
            val name = "room-negative-${UUID.randomUUID()}.db"
            val db = NemaDatabase.create(context, name)
            try {
                db.accountDao().upsert(AccountEntity("account", self, "account", null, "example.org", null, null))
                val store = MessageStore(db)
                val mapped = requireNotNull(normalized(carrier("other")).message).toIncomingMessage("archive")
                val page = ArchivePage(key, ArchiveDirection.BOOTSTRAP, null, true, false, true,
                    "archive-1", "archive-1", listOf(ArchivedIncomingMessage("archive-1", mapped)))
                if (mode == "fault") {
                    db.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER reject_cursor BEFORE INSERT ON archive_cursors BEGIN SELECT RAISE(ABORT, 'fault'); END")
                    assertTrue(runCatching { store.applyArchivePage(page) }.isFailure)
                    assertTrue(store.messages("account").isEmpty())
                    assertTrue(store.aliases("account").isEmpty())
                    assertTrue(db.messageDao().archivePositions("account").isEmpty())
                    assertNull(store.archiveCursor(key))
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cursor")
                    assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page).status)
                } else {
                    store.ingest(mapped.copy(localMessageId = "live", body = if (mode == "conflict") "different" else mapped.body))
                    if (mode == "quarantine") db.openHelper.writableDatabase.execSQL(
                        "UPDATE trusted_identity_aliases SET status = 'QUARANTINED' WHERE kind = 'STANZA_ID'")
                    assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page).status)
                    assertEquals(2, store.messages("account").size)
                    assertEquals(IdentityAliasStatus.QUARANTINED,
                        store.aliases("account").single { it.kind == IdentityAliasKind.STANZA_ID }.status)
                    assertEquals("archive-1", store.archiveCursor(key)?.newestId)
                }
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun redactedPageBoundariesAdvanceWithoutFabricatedIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (redactedFirst in listOf(false, true)) {
            val name = "room-redacted-${UUID.randomUUID()}.db"
            val db = NemaDatabase.create(context, name)
            try {
                db.accountDao().upsert(AccountEntity("account", self, "account", null, "example.org", null, null))
                val store = MessageStore(db)
                val redacted = parse("<message xmlns='jabber:client' from='$room'>" +
                    "<result xmlns='urn:xmpp:mam:2' queryid='query' id='redacted'>" +
                    "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>" +
                    "</forwarded></result></message>")
                val carriers = listOf(redacted, carrier("other")).let { if (redactedFirst) it else it.reversed() }
                val entries = normalizeMamResults(carriers, carriers.map(MamResultExtension::from), attempt, room, true,
                    mappingBareJid = self, archiveRoom = facts())
                assertNull(entries.single { it.resultId == "redacted" }.message)
                val result = store.applyArchivePage(ArchivePage(key, ArchiveDirection.BOOTSTRAP, null, true, false, true,
                    entries.first().resultId, entries.last().resultId,
                    entries.map { ArchivedIncomingMessage(it.resultId, it.message?.toIncomingMessage("normal")) }))
                assertEquals(ArchivePageStatus.APPLIED, result.status)
                assertEquals(entries.first().resultId, result.cursor.oldestId)
                assertEquals(entries.last().resultId, result.cursor.newestId)
                assertEquals(1, store.messages("account").size)
                assertEquals(setOf(IdentityAliasKind.STANZA_ID, IdentityAliasKind.MAM_RESULT), store.aliases("account").map { it.kind }.toSet())
                assertTrue(store.aliases("account").all { it.value == "archive-1" })
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun xmlLiveAndArchiveConvergeInEitherOrderAndAfterReopen() = runBlocking {
        for (archiveFirst in listOf(false, true)) for (nick in listOf("other", "self")) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "room-archive-${UUID.randomUUID()}.db"
            var db = NemaDatabase.create(context, name)
            try {
                db.accountDao().upsert(AccountEntity("account", self, "account", null, "example.org", null, null))
                var store = MessageStore(db)
                val live = requireNotNull(parse(inner(nick, "<stanza-id xmlns='urn:xmpp:sid:0' by='$room' id='archive-1'/>"))
                    .toIncomingEnvelope(attempt, self, room, "self", receivedAtEpochMs = 1_767_225_600_000))
                suspend fun archive() {
                    val mapped = normalized(carrier(nick))
                    val cursor = store.archiveCursor(key)
                    val page = ArchivePage(key, if (cursor == null) ArchiveDirection.BOOTSTRAP else ArchiveDirection.AFTER,
                        cursor?.newestId, true, false, true, "archive-1", "archive-1",
                        listOf(ArchivedIncomingMessage(mapped.resultId, mapped.message?.toIncomingMessage(UUID.randomUUID().toString()))))
                    assertEquals(if (cursor == null) ArchivePageStatus.APPLIED else ArchivePageStatus.RETRYABLE_ERROR,
                        store.applyArchivePage(page).status)
                }
                if (archiveFirst) archive()
                store.ingest(live.toIncomingMessage("live"))
                if (!archiveFirst) archive()
                repeat(2) {
                    assertEquals(1, store.messages("account").size)
                    val row = store.messages("account").single()
                    assertTrue(row.liveDeliveryObserved)
                    assertEquals(nick == "self", row.direction == MessageDirection.OUTBOUND)
                    val aliases = db.messageDao().trustedAliasesForMessage("account", row.localMessageId)
                    assertTrue(aliases.any { it.kind == IdentityAliasKind.STANZA_ID && it.authority == room && it.value == "archive-1" })
                    assertTrue(aliases.any { it.kind == IdentityAliasKind.MAM_RESULT && it.authority == key.aliasAuthority() && it.value == "archive-1" })
                    assertEquals(1, store.archivePositions("account", row.localMessageId).size)
                    assertEquals("archive-1", store.archiveCursor(key)?.newestId)
                    db.close()
                    db = NemaDatabase.create(context, name)
                    store = MessageStore(db)
                    archive()
                }
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }
}
