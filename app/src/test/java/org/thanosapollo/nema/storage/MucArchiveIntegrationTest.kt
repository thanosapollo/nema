package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.mam.element.MamQueryIQ
import org.jxmpp.jid.impl.JidCreate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.transport.*

/** Only the socket is synthetic: real MamManager collectors, session normalization and Room commits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class MucArchiveIntegrationTest : ReactionStoreTestFixture() {
    private val attempt = SessionAttemptIdentity(AccountId.require(ACCOUNT), ConnectionGeneration.require(1),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1))
    private val identity = SessionIdentity(attempt.accountId, attempt.generation)
    private val key = ArchiveCursorKey(ACCOUNT, ROOM, ROOM)
    private lateinit var socket: ArchiveSocket
    private lateinit var session: SmackSessionConnection
    private lateinit var registry: RoomStableIdAuthorityRegistry
    private val requests = mutableListOf<ArchivePageRequest>()

    @Before fun transport() {
        SmackAndroid.initialize(context)
        installNemaSidProviders(); installNemaMamResultProvider()
        installNemaOccupantIdProvider(); installNemaCorrectionProvider()
        socket = ArchiveSocket()
        session = SmackSessionConnection(socket, "account", SELF, event = {})
        session.updateAttempt(attempt)
        val field = session.javaClass.getDeclaredField("roomStableIdAuthorities").apply { isAccessible = true }
        registry = field.get(session) as RoomStableIdAuthorityRegistry
        val lease = requireNotNull(registry.beginJoin(attempt, ROOM))
        check(registry.publish(lease, true, true, "self", true))
    }

    private fun sync() = ArchiveSynchronizer(store, { SessionCapabilities(true, CarbonCapabilityState.ENABLED, true) }, {
        requests += it
        session.queryArchive(it)
    })
    private suspend fun run(sync: ArchiveSynchronizer) {
        sync.synchronize(identity, ROOM, ROOM) { true }
        assertTrue(sync.state.value.toString(), sync.state.value is ArchiveSyncState.Ready)
    }
    @Test fun roomQueryRequiresCurrentMembershipAndRoomMamBeforeNetworkEntry() = runBlocking {
        for (mode in listOf("absent", "unsupported", "generation")) {
            registry.retireAll()
            registry.begin(attempt)
            if (mode == "unsupported") {
                val lease = requireNotNull(registry.beginJoin(attempt, ROOM))
                check(registry.publish(lease, true, true, "self", false))
            }
            val request = ArchivePageRequest(attempt.accountId,
                if (mode == "generation") ConnectionGeneration.require(99) else attempt.generation,
                ROOM, ROOM, ArchivePageDirection.BOOTSTRAP, null, 50)
            assertTrue(mode, runCatching { session.queryArchive(request) }.exceptionOrNull() is SendNotAttemptedException)
            assertEquals(mode, 0, socket.queries)
        }
    }

    @Test fun nativeRoomQueryRejectsReplacedMembershipBeforePublishingPage() = runBlocking {
        socket.pages.add(listOf("root"))
        socket.beforeFin = {
            val lease = requireNotNull(registry.beginJoin(attempt, ROOM))
            check(registry.publish(lease, true, true, "self", true))
        }
        val synchronizer = sync()
        synchronizer.synchronize(identity, ROOM, ROOM) { true }
        assertTrue(synchronizer.state.value is ArchiveSyncState.RetryableError)
        assertEquals(1, socket.queries)
        assertTrue(store.messages(ACCOUNT).isEmpty())
        assertNull(store.archiveCursor(key))
    }

    private fun reopen() {
        database.close()
        database = NemaDatabase.create(context, databaseName)
        store = MessageStore(database)
    }
    private suspend fun projection(body: String, visible: Int = 1) {
        val timeline = database.messageDao().observeDirectTimeline(ACCOUNT, ROOM).first()
        assertEquals(visible, timeline.size)
        val root = timeline.single { it.body == "root body" }
        assertEquals(body, root.correctedBody)
        assertTrue(root.edited)
        assertEquals(body, database.messageDao().observeConversationSummaries(ACCOUNT).first().single().preview)
        assertEquals(body, database.messageDao().cachedConversationSummaries(ACCOUNT).single().preview)
    }

    @Test fun splitPagesOriginalLaterRepeatedEditsAndOverlapSurviveReopen() = runBlocking {
        socket.pages.add(listOf("edit1"))
        val synchronizer = sync()
        run(synchronizer)
        val fallback = store.messages(ACCOUNT).single()
        assertNull(fallback.replaceId)
        assertEquals("edit1 body", database.messageDao().observeDirectTimeline(ACCOUNT, ROOM).first().single().body)
        socket.pages.add(listOf("root", "edit1"))
        // BEFORE must use the same room cursor and scope established by synchronize.
        assertTrue(synchronizer.backfillOnePage(identity, ROOM, ROOM) { true })
        projection("edit1 body")
        val original = store.messages(ACCOUNT).single { it.mucMessageId == "root" }
        val originalPosition = database.messageDao().archivePosition(ACCOUNT, ROOM, ROOM, original.localMessageId)
        socket.pages.add(listOf("edit1", "edit2"))
        run(synchronizer)
        projection("edit2 body")
        assertEquals(original, store.messages(ACCOUNT).single { it.mucMessageId == "root" })
        assertEquals(originalPosition, database.messageDao().archivePosition(ACCOUNT, ROOM, ROOM, original.localMessageId))
        val rows = store.messages(ACCOUNT)
        val aliases = store.aliases(ACCOUNT)
        val positions = database.messageDao().archivePositions(ACCOUNT)
        assertEquals(3, rows.size)
        assertEquals(setOf("root", "edit1", "edit2"), rows.map { it.mucMessageId }.toSet())
        assertTrue(rows.all { it.mucLiveOrderEpoch == null && it.mucOccupantEvidence == MucOccupantEvidence.ROOM_MAM })
        assertTrue(rows.filter { it.mucMessageId != "root" }.all {
            it.mucReplaceId == "root" && it.correctionTargetMessageId == original.localMessageId
        })
        assertEquals(1, rows.count { it.mucCorrectionSelected })
        assertEquals("uid-root", store.archiveCursor(key)!!.oldestId)
        assertEquals("uid-edit2", store.archiveCursor(key)!!.newestId)
        reopen()
        projection("edit2 body")
        socket.pages.add(listOf("root", "edit1", "edit2", "boundary"))
        run(sync())
        assertEquals(rows, store.messages(ACCOUNT))
        assertEquals(aliases.toSet(), store.aliases(ACCOUNT).toSet())
        assertEquals(positions.toSet(), database.messageDao().archivePositions(ACCOUNT).toSet())
        assertEquals("uid-boundary", store.archiveCursor(key)!!.newestId)
        assertEquals(listOf(ArchivePageDirection.BOOTSTRAP, ArchivePageDirection.BEFORE,
            ArchivePageDirection.AFTER, ArchivePageDirection.AFTER), requests.map { it.direction })
        assertTrue(requests.all { it.scope == ROOM && it.archiveAuthority == ROOM })
        reopen()
        projection("edit2 body")
    }

    @Test fun expiredQueryWrongRoomAndChangedOccupantCannotSuppressFallback() = runBlocking {
        socket.pages.add(listOf("root", "edit1"))
        run(sync())
        val before = store.messages(ACCOUNT)
        socket.expired = true
        socket.pages.add(listOf("edit2", "boundary"))
        run(sync())
        assertEquals(before, store.messages(ACCOUNT))
        projection("edit1 body")
        socket.expired = false
        socket.wrongRoom = true
        socket.pages.add(listOf("edit2"))
        val rejected = sync()
        rejected.synchronize(identity, ROOM, ROOM) { true }
        assertTrue(rejected.state.value is ArchiveSyncState.RetryableError)
        assertEquals(before, store.messages(ACCOUNT))
        assertEquals("uid-boundary", store.archiveCursor(key)!!.newestId)
        socket.wrongRoom = false
        socket.actor = "other-occupant"
        socket.pages.add(listOf("edit2", "end"))
        run(sync())
        val edit = store.messages(ACCOUNT).single { it.mucMessageId == "edit2" }
        assertNull(edit.replaceId)
        assertNull(edit.correctionTargetMessageId)
        assertFalse(edit.mucCorrectionSelected)
        val timeline = database.messageDao().observeDirectTimeline(ACCOUNT, ROOM).first()
        assertEquals(2, timeline.size)
        assertEquals("edit1 body", timeline.single { it.body == "root body" }.correctedBody)
        assertEquals("edit2 body", timeline.single { it.body == "edit2 body" }.body)
        reopen()
        assertEquals(edit, store.messages(ACCOUNT).single { it.mucMessageId == "edit2" })
    }

    private inner class ArchiveSocket : XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
        .setXmppDomain(JidCreate.domainBareFrom("example.org")).setUsernameAndPassword("account", null).build()) {
        val pages = ArrayDeque<List<String>>()
        var queries = 0
        var beforeFin: () -> Unit = {}
        var expired = false
        var wrongRoom = false
        var actor = "occupant"
        init {
            connected = true; authenticated = true
            user = JidCreate.entityFullFrom("$SELF/test")
            replyTimeout = 3000
        }
        override fun throwNotConnectedExceptionIfAppropriate() = Unit
        override fun sendStanzaInternal(packet: Stanza) {
            val query = packet as MamQueryIQ
            queries++
            assertEquals(ROOM, query.to.toString())
            val ids = pages.removeFirst()
            val accepted = if (expired) ids.filter { it == "boundary" } else ids
            for (id in ids) {
                val qid = if (expired && id != "boundary") "expired-query" else query.queryId
                val claim = if (id.startsWith("edit")) "<replace xmlns='urn:xmpp:message-correct:0' id='root'/>" else ""
                val inner = if (id in setOf("boundary", "end")) "" else
                    "<message xmlns='jabber:client' from='$ROOM/nick' type='groupchat' id='$id'>" +
                        "<body>$id body</body>$claim<occupant-id xmlns='urn:xmpp:occupant-id:0' id='$actor'/></message>"
                processStanza(PacketParserUtils.parseStanza(
                    "<message xmlns='jabber:client' from='${if (wrongRoom) "wrong@conference.example.org" else ROOM}'>" +
                        "<result xmlns='urn:xmpp:mam:2' queryid='$qid' id='uid-$id'><forwarded xmlns='urn:xmpp:forward:0'>" +
                        "<delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>$inner</forwarded></result></message>"))
            }
            beforeFin()
            processStanza(PacketParserUtils.parseStanza(
                "<iq xmlns='jabber:client' from='$ROOM' to='$SELF/test' id='${query.stanzaId}' type='result'>" +
                    "<fin xmlns='urn:xmpp:mam:2' complete='true' stable='true'><set xmlns='http://jabber.org/protocol/rsm'>" +
                    "<first index='1'>uid-${accepted.first()}</first><last>uid-${accepted.last()}</last></set></fin></iq>"))
        }
    }
}
