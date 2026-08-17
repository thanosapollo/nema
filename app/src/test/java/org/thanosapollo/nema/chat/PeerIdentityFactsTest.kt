package org.thanosapollo.nema.chat

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
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.PeerEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerIdentityFactsTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "peer-facts-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().saveBound(
            AccountEntity("account", "account@example.org", "account", null, "example.org", null, null),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun observePeerProjectsRoomAndAvatarWithoutRosterName() = runBlocking {
        val photo = byteArrayOf(1, 2, 3)
        database.messageDao().upsertPeer(
            PeerEntity(
                accountId = "account",
                jid = "peer@example.org",
                displayName = "Remote",
                localNickname = "Local",
                photoBytes = photo,
                photoMime = "image/png",
                room = true,
            ),
        )

        val facts = ChatRepository(database).observePeer("account", "peer@example.org").first()

        assertEquals("Local", facts?.localNickname)
        assertEquals("Remote", facts?.remoteProfileName)
        assertEquals("image/png", facts?.photoMime)
        assertArrayEquals(photo, facts?.photoBytes)
        assertEquals(true, facts?.room)
        assertNull(ChatRepository(database).observePeer("account", "missing@example.org").first())
    }
}
