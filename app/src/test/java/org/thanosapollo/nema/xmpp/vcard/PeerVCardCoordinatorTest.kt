package org.thanosapollo.nema.xmpp.vcard

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerVCardCoordinatorTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var store: PeerIdentityStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "vcard-coord-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().saveBound(
            AccountEntity(ACCOUNT, "self@example.org", "self", null, "example.org", null, null),
        )
        store = PeerIdentityStore(database.messageDao())
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun sendNotAttemptedDoesNotStampFailure() = runBlocking {
        val coordinator = PeerVCardCoordinator(
            store = store,
            loader = VCardLoader { _, _, _ -> throw SendNotAttemptedException() },
        )
        coordinator.ensure(AccountId.require(ACCOUNT), ConnectionGeneration.require(1), listOf(PEER))
        assertNull(store.peer(ACCOUNT, PEER)?.vcardFailureAtMs)
        assertNull(store.peer(ACCOUNT, PEER)?.vcardFetchedAtMs)
    }

    @Test
    fun cancellationDoesNotStampFailure() = runBlocking {
        val loads = AtomicInteger()
        val coordinator = PeerVCardCoordinator(
            store = store,
            loader = VCardLoader { _, _, _ ->
                loads.incrementAndGet()
                throw CancellationException("cancelled load")
            },
        )
        val thrown = runCatching {
            coordinator.ensure(AccountId.require(ACCOUNT), ConnectionGeneration.require(1), listOf(PEER))
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(1, loads.get())
        assertNull(store.peer(ACCOUNT, PEER)?.vcardFailureAtMs)
    }

    @Test
    fun singleInFlightSkipsSecondCaller() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val coordinator = PeerVCardCoordinator(
            store = store,
            loader = VCardLoader { _, _, _ ->
                loads.incrementAndGet()
                started.complete(Unit)
                release.await()
                RemoteVCardPayload("Name", null, null, null, null)
            },
        )
        val first = async {
            coordinator.ensure(AccountId.require(ACCOUNT), ConnectionGeneration.require(1), listOf(PEER))
        }
        started.await()
        coordinator.ensure(AccountId.require(ACCOUNT), ConnectionGeneration.require(1), listOf(PEER))
        assertEquals(1, loads.get())
        release.complete(Unit)
        first.await()
        assertEquals("Name", store.peer(ACCOUNT, PEER)?.displayName)
    }

    @Test
    fun oversizedPhotoKeepsPriorAvatar() = runBlocking {
        val prior = byteArrayOf(9, 9, 9)
        store.saveSuccess(
            PeerEntity(
                accountId = ACCOUNT,
                jid = PEER,
                displayName = "Old",
                photoMime = "image/png",
                photoBytes = prior,
                photoSha1 = "old",
                vcardFetchedAtMs = 1L,
                vcardFailureAtMs = null,
            ),
        )
        store.saveLocalNickname(ACCOUNT, PEER, "Local Friend")
        val coordinator = PeerVCardCoordinator(
            store = store,
            loader = VCardLoader { _, _, _ ->
                RemoteVCardPayload(
                    formattedName = "New",
                    nickname = null,
                    photoBytes = ByteArray(MAX_VCARD_PHOTO_BYTES + 1) { 1 },
                    photoMime = "image/jpeg",
                    photoSha1 = "new",
                )
            },
            clockMs = { 100L },
            successTtlMs = 1L,
        )
        coordinator.ensure(AccountId.require(ACCOUNT), ConnectionGeneration.require(1), listOf(PEER))
        val cached = requireNotNull(store.peer(ACCOUNT, PEER))
        assertEquals("New", cached.displayName)
        assertEquals("Local Friend", cached.localNickname)
        assertTrue(cached.photoBytes.contentEquals(prior))
        assertEquals("image/png", cached.photoMime)
    }

    @Test
    fun roomVCardIsNotFetchedAndDoesNotWipeDisplayName() = runBlocking {
        store.saveRoom(ACCOUNT, ROOM, true)
        store.saveDisplayName(ACCOUNT, ROOM, "Council of Oberon")
        val loads = AtomicInteger()
        val coordinator = PeerVCardCoordinator(
            store = store,
            loader = VCardLoader { _, _, _ ->
                loads.incrementAndGet()
                RemoteVCardPayload(null, null, null, null, null)
            },
        )
        coordinator.ensure(AccountId.require(ACCOUNT), ConnectionGeneration.require(1), listOf(ROOM))
        assertEquals(0, loads.get())
        assertEquals("Council of Oberon", store.peer(ACCOUNT, ROOM)?.displayName)
    }

    companion object {
        private const val ACCOUNT = "account-a"
        private const val PEER = "peer@example.org"
        private const val ROOM = "coven@conference.example.org"
    }
}
