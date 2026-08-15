package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.chat.ChatRoute
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var repository: AccountRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "account-repository-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        repository = AccountRepository(database.accountDao())
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun accountIdCannotBeReboundButSameCanonicalJidCanUpdateConfiguration() = runBlocking {
        val original = account(bareJid = "person@example.org", authenticationId = "first")
        repository.save(original)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.save(account(bareJid = "other@example.org", authenticationId = "other"))
            }
        }
        assertEquals(original, repository.account(ACCOUNT_ID))

        val updated = account(bareJid = "person@example.org", authenticationId = "updated")
        repository.save(updated)
        assertEquals(updated, repository.account(ACCOUNT_ID))
    }

    @Test
    fun switchingActiveAccountAtomicallyResetsOnlyTargetNavigationAndSurvivesReopen() = runBlocking {
        val first = account(FIRST_ID, "first@example.org", "first")
        val second = account(SECOND_ID, "second@example.org", "second")
        repository.save(first)
        repository.save(second)
        repository.activate(FIRST_ID)
        val chats = ChatRepository(database)
        chats.saveRoute(FIRST_ID.value, ChatRoute("peer-one@example.org"))
        chats.saveRoute(SECOND_ID.value, ChatRoute("peer-two@example.org"))

        repository.switchActive(SECOND_ID)

        assertEquals(listOf(first, second), repository.configuredAccounts.first())
        assertEquals(second, repository.activeAccount.first())
        assertEquals(ChatRoute("peer-one@example.org"), chats.observeRoute(FIRST_ID.value).first())
        assertEquals(null, chats.observeRoute(SECOND_ID.value).first())

        database.close()
        database = NemaDatabase.create(context, databaseName)
        repository = AccountRepository(database.accountDao())

        assertEquals(second, repository.activeAccount.first())
        assertEquals(null, ChatRepository(database).observeRoute(SECOND_ID.value).first())
    }

    @Test
    fun unknownSwitchLeavesActiveAccountAndTargetNavigationUntouched() = runBlocking {
        val first = account(FIRST_ID, "first@example.org", "first")
        repository.save(first)
        repository.activate(FIRST_ID)
        val chats = ChatRepository(database)
        chats.saveRoute(FIRST_ID.value, ChatRoute("peer-one@example.org"))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.switchActive(AccountId.require("missing")) }
        }

        assertEquals(first, repository.activeAccount.first())
        assertEquals(ChatRoute("peer-one@example.org"), chats.observeRoute(FIRST_ID.value).first())
    }

    @Test
    fun removingActiveAccountCascadesItsStateAndLeavesOtherAccountConfigured() = runBlocking {
        val first = account(FIRST_ID, "first@example.org", "first")
        val second = account(SECOND_ID, "second@example.org", "second")
        repository.save(first)
        repository.save(second)
        repository.activate(FIRST_ID)
        val chats = ChatRepository(database)
        val messages = MessageStore(database)
        chats.saveRoute(FIRST_ID.value, ChatRoute("peer-one@example.org"))
        chats.saveRoute(SECOND_ID.value, ChatRoute("peer-two@example.org"))
        messages.compose(
            OutboundIntent(
                accountId = FIRST_ID.value,
                operationId = "operation-child",
                localMessageId = "message-child",
                originId = "origin-child",
                peerJid = "peer-one@example.org",
                senderJid = first.bareJid.value,
                messageKind = MessageKind.CHAT,
                threadId = "child",
                parentThreadId = "root",
                body = "threaded message",
            ),
        )
        database.messageDao().upsertArchiveCursor(
            ArchiveCursorEntity(
                accountId = FIRST_ID.value,
                archiveAuthority = first.bareJid.value,
                scope = "ACCOUNT",
                oldestId = "oldest",
                newestId = "newest",
                hasEarlier = true,
                retryableError = null,
            ),
        )

        repository.remove(FIRST_ID)

        assertEquals(listOf(second), repository.configuredAccounts.first())
        assertEquals(null, repository.activeAccount.first())
        assertEquals(null, chats.observeRoute(FIRST_ID.value).first())
        assertEquals(ChatRoute("peer-two@example.org"), chats.observeRoute(SECOND_ID.value).first())
        assertEquals(emptyList<MessageEntity>(), messages.messages(FIRST_ID.value))
        assertEquals(emptyList<OutboxEntity>(), messages.outboxes(FIRST_ID.value))
        assertEquals(
            null,
            messages.archiveCursor(ArchiveCursorKey(FIRST_ID.value, first.bareJid.value, "ACCOUNT")),
        )
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertEquals(false, it.moveToFirst())
        }

        database.close()
        database = NemaDatabase.create(context, databaseName)
        repository = AccountRepository(database.accountDao())

        assertEquals(listOf(second), repository.configuredAccounts.first())
        assertEquals(null, repository.activeAccount.first())
    }

    private fun account(
        id: AccountId = ACCOUNT_ID,
        bareJid: String,
        authenticationId: String,
    ) = AccountConfiguration.create(
        id = id,
        bareJid = bareJid,
        authenticationId = authenticationId,
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private companion object {
        val ACCOUNT_ID = AccountId.require("account")
        val FIRST_ID = AccountId.require("first")
        val SECOND_ID = AccountId.require("second")
    }
}
