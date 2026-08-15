package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.chat.ConversationSummary
import org.thanosapollo.nema.thread.MessageKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerIdentityStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "peer-identity-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().saveBound(
            AccountEntity(
                id = ACCOUNT,
                bareJid = "self@example.org",
                authenticationId = "self",
                authorizationId = null,
                serviceDomain = "example.org",
                networkHost = null,
                networkPort = null,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun cacheSurvivesAndProjectsIntoConversationList() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = "m1",
                peerJid = PEER,
                senderJid = PEER,
                direction = MessageDirection.INBOUND,
                messageKind = MessageKind.CHAT,
                threadId = null,
                parentThreadId = null,
                body = "hello",
                archiveOrdinal = null,
                aliases = emptyList(),
            ),
        )
        val identities = PeerIdentityStore(database.messageDao())
        val photo = byteArrayOf(1, 2, 3, 4)
        identities.saveSuccess(
            PeerEntity(
                accountId = ACCOUNT,
                jid = PEER,
                displayName = "Peer Name",
                photoMime = "image/png",
                photoBytes = photo,
                photoSha1 = "deadbeef",
                vcardFetchedAtMs = 42L,
                vcardFailureAtMs = null,
            ),
        )
        identities.saveLocalNickname(ACCOUNT, PEER, "  Local Friend  ")

        database.close()
        database = NemaDatabase.create(context, databaseName)
        val reopened = PeerIdentityStore(database.messageDao())
        val cached = requireNotNull(reopened.peer(ACCOUNT, PEER))
        assertEquals("Peer Name", cached.displayName)
        assertEquals("Local Friend", cached.localNickname)
        assertArrayEquals(photo, cached.photoBytes)

        val conversations = ChatRepository(database).observeConversations(ACCOUNT).first()
        assertEquals(listOf("Local Friend"), conversations.map(ConversationSummary::displayLabel))
        assertArrayEquals(photo, conversations.single().photoBytes)
    }

    @Test
    fun nicknameIsAccountQualifiedAndBlankClearsIt() = runBlocking {
        database.accountDao().saveBound(
            AccountEntity(
                id = SECOND_ACCOUNT,
                bareJid = "second@example.org",
                authenticationId = "second",
                authorizationId = null,
                serviceDomain = "example.org",
                networkHost = null,
                networkPort = null,
            ),
        )
        val identities = PeerIdentityStore(database.messageDao())

        identities.saveLocalNickname(ACCOUNT, PEER, "First")
        identities.saveLocalNickname(SECOND_ACCOUNT, PEER, "Second")
        identities.saveLocalNickname(ACCOUNT, PEER, "   ")

        assertNull(identities.peer(ACCOUNT, PEER)?.localNickname)
        assertEquals("Second", identities.peer(SECOND_ACCOUNT, PEER)?.localNickname)
    }

    @Test
    fun displayNameIsStoredSeparatelyFromLocalNickname() = runBlocking {
        val identities = PeerIdentityStore(database.messageDao())
        identities.saveDisplayName(ACCOUNT, PEER, "  Council of Oberon  ")
        identities.saveLocalNickname(ACCOUNT, PEER, "Puck")
        val peer = requireNotNull(identities.peer(ACCOUNT, PEER))
        assertEquals("Council of Oberon", peer.displayName)
        assertEquals("Puck", peer.localNickname)
    }

    @Test
    fun failureKeepsPriorIdentity() = runBlocking {
        val identities = PeerIdentityStore(database.messageDao())
        identities.saveLocalNickname(ACCOUNT, PEER, "Local Friend")
        identities.saveSuccess(
            PeerEntity(
                accountId = ACCOUNT,
                jid = PEER,
                displayName = "Keep Me",
                photoMime = null,
                photoBytes = null,
                photoSha1 = null,
                vcardFetchedAtMs = 10L,
                vcardFailureAtMs = null,
            ),
        )
        identities.saveFailure(ACCOUNT, PEER, failureAtMs = 99L)
        val after = requireNotNull(identities.peer(ACCOUNT, PEER))
        assertEquals("Keep Me", after.displayName)
        assertEquals("Local Friend", after.localNickname)
        assertEquals(10L, after.vcardFetchedAtMs)
        assertEquals(99L, after.vcardFailureAtMs)
        assertNull(after.photoBytes)
    }

    companion object {
        private const val ACCOUNT = "account-a"
        private const val SECOND_ACCOUNT = "account-b"
        private const val PEER = "peer@example.org"
    }
}
