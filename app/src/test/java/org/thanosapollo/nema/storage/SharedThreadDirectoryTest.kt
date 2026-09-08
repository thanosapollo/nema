package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.threads.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedThreadDirectoryTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "shared-${UUID.randomUUID()}.db"
    private lateinit var db: NemaDatabase
    private val bare = "self@example.org"
    private val id = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val incarnation = "a".repeat(64)
    @Before fun setup() = runBlocking {
        db = NemaDatabase.create(context, name)
        db.accountDao().upsert(AccountEntity("local-id", bare, "self", null, "example.org", null, null))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun snapshot(peer: String, kind: MessageKind, rows: List<ThreadDirectoryItem>) = ThreadDirectorySnapshot(
        bare, "example.org", if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(bare, peer)
        else ThreadDirectoryScope.Muc(peer, incarnation), "opaque", rows,
    )
    private fun item(revision: Long = 1, title: String = "Shared", archived: Boolean = false) = ThreadDirectoryItem(id, title, revision, archived, true)

    @Test fun directAndMucEmptyNamesArchiveAndPrivateAliasesSurviveReopen() = runBlocking {
        for (kind in listOf(MessageKind.CHAT, MessageKind.GROUPCHAT)) {
            val peer = if (kind == MessageKind.CHAT) "peer@example.org" else "room@rooms.example.org"
            val repository = ChatRepository(db)
            db.messageDao().createNamedThread(MessageThreadEntity("local-id", peer, kind, id.toString(), null), "Private")
            SharedThreadStore(db).snapshot("local-id", bare, peer, kind, snapshot(peer, kind, listOf(item())))
            val projected = repository.observeRecentThreads("local-id", peer).first().single()
            assertEquals("Shared", projected.title)
            assertEquals("Private", projected.shared?.localAlias)
            assertTrue(db.messageDao().isNamedThread("local-id", peer, kind, id.toString()))
            SharedThreadStore(db).snapshot("local-id", bare, peer, kind, snapshot(peer, kind, listOf(item(2, "Renamed", true))))
            assertTrue(repository.observeRecentThreads("local-id", peer).first().single().shared!!.archived)
            assertEquals("Private", db.messageDao().observeThreadTitles("local-id", peer).first().single().title)
        }
        db.close(); db = NemaDatabase.create(context, name)
        assertEquals(2, db.openHelper.readableDatabase.query("SELECT * FROM shared_threads").use { it.count })
    }

    @Test fun completeSnapshotRejectsWrongAccountScopeRevisionAndWholeBatchAtomically() = runBlocking {
        val peer = "peer@example.org"
        val kind = MessageKind.CHAT
        val store = SharedThreadStore(db)
        val original = snapshot(peer, kind, listOf(item(2)))
        store.snapshot("local-id", bare, peer, kind, original)
        val invalid = listOf(original.copy(account = "other@example.org"), original.copy(authority = "evil.example.org"),
            original.copy(items = listOf(item(1))), original.copy(items = listOf(item(2, "Equivocation"))),
            original.copy(items = listOf(item(3), item(3))),
            original.copy(items = listOf(item(3, "valid"), item(4, "bad\nname").copy(id = UUID.randomUUID()))))
        for (candidate in invalid) {
            assertTrue(runCatching { store.snapshot("local-id", bare, peer, kind, candidate) }.isFailure)
            assertEquals("Shared", db.sharedThreadDao().rows("local-id", peer, kind).single().title)
            assertEquals(2L, db.sharedThreadDao().rows("local-id", peer, kind).single().revision)
        }
    }

    @Test fun directoryNeverRewritesExistingChildLineageOrPublishesItsAlias() = runBlocking {
        val peer = "peer@example.org"
        val dao = db.messageDao()
        dao.insertPeer(PeerEntity("local-id", peer))
        dao.insertThread(MessageThreadEntity("local-id", peer, MessageKind.CHAT, "legacy-parent", null))
        dao.insertThread(MessageThreadEntity("local-id", peer, MessageKind.CHAT, id.toString(), "legacy-parent"))
        SharedThreadStore(db).snapshot("local-id", bare, peer, MessageKind.CHAT, snapshot(peer, MessageKind.CHAT, listOf(item())))
        assertEquals("legacy-parent", dao.thread("local-id", peer, MessageKind.CHAT, id.toString())?.parentThreadId)
        assertTrue(dao.observeThreadTitles("local-id", peer).first().isEmpty())
        assertEquals("legacy-parent", dao.observeNamedThreads("local-id", peer).first().single().parentThreadId)
    }

    @Test fun disappearedDirectoryRowsRemainArchivedDestinationsWithoutChangingCanonicalIdentity() = runBlocking {
        val peer = "room@rooms.example.org"
        val kind = MessageKind.GROUPCHAT
        val store = SharedThreadStore(db)
        store.snapshot("local-id", bare, peer, kind, snapshot(peer, kind, listOf(item())))
        store.snapshot("local-id", bare, peer, kind, snapshot(peer, kind, emptyList()).copy(scope = ThreadDirectoryScope.Muc(peer, "b".repeat(64))))
        val retired = db.sharedThreadDao().rows("local-id", peer, kind).single()
        assertTrue(retired.retired && retired.archived && !retired.canModify)
        assertTrue(db.messageDao().isNamedThread("local-id", peer, kind, id.toString()))
        assertNotNull(db.messageDao().thread("local-id", peer, kind, id.toString()))
    }

    @Test fun authoredMucIntentKeepsOriginalIncarnationCasAndOperationAcrossReopen() = runBlocking {
        val peer = "room@rooms.example.org"
        val snap = snapshot(peer, MessageKind.GROUPCHAT, listOf(item()))
        val store = SharedThreadStore(db)
        store.snapshot("local-id", bare, peer, MessageKind.GROUPCHAT, snap)
        val action = DirectoryAction(DirectoryContext(snap.authority, snap.scope), id, revision = 1, title = "Rename")
        store.prepare("local-id", bare, peer, MessageKind.GROUPCHAT, action)
        assertTrue(runCatching { store.prepare("local-id", bare, peer, MessageKind.GROUPCHAT, action) }.isFailure)
        db.close(); db = NemaDatabase.create(context, name)
        assertEquals(action, SharedThreadStore(db).intent("local-id", peer, MessageKind.GROUPCHAT)?.action(bare))
    }

    @Test fun sharedArchivePreservesDirectAndMucHistoryDraftsAndReadPartition() = runBlocking {
        for (kind in listOf(MessageKind.CHAT, MessageKind.GROUPCHAT)) {
            val peer = if (kind == MessageKind.CHAT) "peer@example.org" else "room@rooms.example.org"
            val store = SharedThreadStore(db)
            val messages = MessageStore(db)
            val repository = ChatRepository(db)
            val thread = org.thanosapollo.nema.thread.ThreadRef(org.thanosapollo.nema.thread.ThreadId.require(id.toString()))
            store.snapshot("local-id", bare, peer, kind, snapshot(peer, kind, listOf(item())))
            suspend fun ingest(key: String, threadId: String?) = messages.ingest(IncomingMessage(
                "local-id", "$kind-$key", peer, if (kind == MessageKind.CHAT) peer else "$peer/member",
                MessageDirection.INBOUND, kind, threadId, null, key, null, emptyList(),
            ))
            ingest("named", id.toString()); ingest("main", null); ingest("implicit", "legacy-session")
            val key = org.thanosapollo.nema.chat.DirectConversationKey("local-id", peer, thread)
            repository.saveDraft(key, "kept draft")
            db.messageDao().markMessagesReadThrough("local-id", peer, db.messageDao().message("local-id", "$kind-main")!!.localSequence)
            assertFalse(db.messageDao().message("local-id", "$kind-named")!!.locallyRead)
            store.snapshot("local-id", bare, peer, kind, snapshot(peer, kind, listOf(item(2, archived = true))))
            assertEquals(listOf("named"), repository.observeTimeline(key).first().map { it.body })
            assertEquals(listOf("main", "implicit"), repository.observeTimeline("local-id", peer).first().map { it.body })
            assertEquals("kept draft", repository.observeDraft(key).first())
            assertEquals(1, repository.observeRecentThreads("local-id", peer).first().single { it.thread == thread }.unreadCount)
            assertTrue(db.messageDao().isNamedThread("local-id", peer, kind, id.toString()))
        }
    }

    @Test fun localAccountBindingCannotBeReplacedByAnAuthenticatedButDifferentBareJid() = runBlocking {
        val peer = "peer@example.org"
        val forged = snapshot(peer, MessageKind.CHAT, listOf(item())).copy(account = "other@example.org",
            scope = ThreadDirectoryScope.Direct("other@example.org", peer))
        assertTrue(runCatching { SharedThreadStore(db).snapshot("local-id", "other@example.org", peer, MessageKind.CHAT, forged) }.isFailure)
        assertTrue(db.sharedThreadDao().rows("local-id", peer, MessageKind.CHAT).isEmpty())
    }

    @Test fun exported28MigrationPreservesLiteralAliasesDraftsLineageAndReadBits() = runBlocking {
        db.close(); context.deleteDatabase(name)
        migrations.createDatabase(name, 28).use { sql ->
            sql.execSQL("INSERT INTO accounts (id,bareJid,authenticationId,serviceDomain) VALUES ('local-id','self@example.org','self','example.org')")
            sql.execSQL("INSERT INTO peers (accountId,jid,room,lastReadLocalSequence,inRoster) VALUES ('local-id','peer@example.org',0,0,0)")
            sql.execSQL("INSERT INTO message_threads VALUES ('local-id','peer@example.org','CHAT','legacy-parent',NULL)")
            sql.execSQL("INSERT INTO message_threads VALUES ('local-id','peer@example.org','CHAT','legacy-child','legacy-parent')")
            sql.execSQL("INSERT INTO message_thread_titles VALUES ('local-id','peer@example.org','CHAT','legacy-child','Private literal')")
            sql.execSQL("INSERT INTO message_drafts (accountId,peerJid,messageKind,threadKey,body) VALUES ('local-id','peer@example.org','CHAT','legacy-key','draft')")
            sql.execSQL("INSERT INTO messages (accountId,localMessageId,peerJid,senderJid,direction,messageKind,body,localSequence,markable,directSessionTransitionApplied,liveDeliveryObserved,unreadEligible,locallyRead,threadId,parentThreadId) VALUES ('local-id','m','peer@example.org','peer@example.org','INBOUND','CHAT','body',1,0,0,0,1,0,'legacy-child','legacy-parent')")
        }
        migrations.runMigrationsAndValidate(name, 29, true).close()
        db = NemaDatabase.create(context, name)
        val dao = db.messageDao()
        assertEquals("Private literal", dao.observeThreadTitles("local-id", "peer@example.org").first().single().title)
        assertEquals("draft", dao.draft("local-id", "peer@example.org", "legacy-key")?.body)
        assertEquals("legacy-parent", dao.thread("local-id", "peer@example.org", MessageKind.CHAT, "legacy-child")?.parentThreadId)
        assertEquals(false, dao.message("local-id", "m")?.locallyRead)
        assertTrue(db.sharedThreadDao().rows("local-id", "peer@example.org", MessageKind.CHAT).isEmpty())
    }
}
