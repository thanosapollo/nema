package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.*
import org.thanosapollo.nema.xmpp.threads.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedThreadSwitcherTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val name = "shared-ui-${UUID.randomUUID()}.db"
    private lateinit var db: NemaDatabase
    @After fun cleanup() { if (::db.isInitialized) db.close(); context.deleteDatabase(name) }

    @Test fun directSharedRoomProjectionArchivesRestoresAndRemainsSharedOffline() = journey(MessageKind.CHAT)
    @Test fun mucSharedRoomProjectionArchivesRestoresAndRemainsSharedOffline() = journey(MessageKind.GROUPCHAT)

    @Test fun sharedCreateFormDoesNotRebindToRecreatedRoomOrPublishLocalConsent() {
        val peer = "room@rooms.example.org"
        val old = DirectoryContext("example.org", ThreadDirectoryScope.Muc(peer, "a".repeat(64)), 1)
        val view = mutableStateOf(DirectoryView(DirectoryMode.SHARED, old))
        var writes = 0
        compose.setContent {
            MaterialTheme {
                ThreadSwitcher(ChatRouteOccurrence(ChatRoute(peer), 1), null, emptyList(), { _, _ -> },
                    onCreate = { _, _ -> writes++; true }, onRename = { _, _, _, _ -> false }, directory = view.value,
                    onCreateShared = { _, _, _ -> writes++; true })
            }
        }
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithText("New thread").performClick()
        compose.onNodeWithTag("thread-name-input").performTextInput("Old members only")
        compose.runOnIdle { view.value = DirectoryView(DirectoryMode.SHARED, old.copy(scope = ThreadDirectoryScope.Muc(peer, "b".repeat(64)))) }
        compose.onNodeWithText("Create thread").performClick()
        compose.onNodeWithText("Not saved. Try again.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun localCreateConsentNeverBecomesSharedWhenDiscoverySettlesDuringTyping() {
        val peer = "peer@example.org"
        val view = mutableStateOf(DirectoryView(DirectoryMode.LOCAL_ONLY))
        var localWrites = 0
        var sharedWrites = 0
        compose.setContent {
            MaterialTheme {
                ThreadSwitcher(ChatRouteOccurrence(ChatRoute(peer), 1), null, emptyList(), { _, _ -> },
                    onCreate = { _, _ -> localWrites++; true }, onRename = { _, _, _, _ -> false }, directory = view.value,
                    onCreateShared = { _, _, _ -> sharedWrites++; true })
            }
        }
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithText("New thread").performClick()
        compose.onNodeWithTag("thread-name-input").performTextInput("Private topic")
        compose.runOnIdle {
            view.value = DirectoryView(DirectoryMode.SHARED,
                DirectoryContext("example.org", ThreadDirectoryScope.Direct("self@example.org", peer), 1))
        }
        compose.onNodeWithText("Create thread").performClick()
        compose.onNodeWithText("Not saved. Try again.").assertIsDisplayed()
        compose.onNodeWithTag("thread-name-input").assertTextContains("Private topic")
        compose.runOnIdle { assertEquals(0, localWrites); assertEquals(0, sharedWrites) }
    }

    private fun journey(kind: MessageKind) {
        val bare = "self@example.org"
        val peer = if (kind == MessageKind.CHAT) "peer@example.org" else "room@rooms.example.org"
        val scope = if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(bare, peer) else ThreadDirectoryScope.Muc(peer, "a".repeat(64))
        val id = UUID.randomUUID()
        var item = ThreadDirectoryItem(id, "Shared topic", 1, false, true)
        db = NemaDatabase.create(context, name)
        val store = SharedThreadStore(db)
        suspend fun publish() = store.snapshot("local-id", bare, peer, kind, ThreadDirectorySnapshot(bare, "example.org", scope, "snapshot", listOf(item)))
        runBlocking {
            db.accountDao().upsert(AccountEntity("local-id", bare, "self", null, "example.org", null, null))
            db.messageDao().createNamedThread(MessageThreadEntity("local-id", peer, kind, id.toString(), null), "Private alias")
            publish()
        }
        val view = mutableStateOf(DirectoryView(DirectoryMode.SHARED, DirectoryContext("example.org", scope)))
        val selection = mutableStateOf<ThreadRef?>(null)
        val occurrence = ChatRouteOccurrence(ChatRoute(peer), 1)
        val repository = ChatRepository(db)
        compose.setContent {
            val threads by repository.observeRecentThreads("local-id", peer).collectAsState(emptyList())
            MaterialTheme {
                ThreadSwitcher(occurrence, selection.value, threads,
                    onSelect = { _, thread -> selection.value = thread },
                    onCreate = { _, _ -> false }, onRename = { _, _, _, _ -> false }, directory = view.value,
                    onArchive = { _, _ -> item = item.copy(revision = item.revision + 1, archived = !item.archived); publish(); true })
            }
        }
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shared topic").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Shared · Private alias: Private alias").assertIsDisplayed()
        compose.onNodeWithText("Archive").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("thread-destination-$id").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Archived (1) · 0 unread").performClick()
        compose.onNodeWithText("Shared topic").assertIsDisplayed()
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("thread-destination-$id").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Active threads").performClick()
        compose.onNodeWithText("Shared topic").assertIsDisplayed()
        compose.runOnIdle { view.value = DirectoryView(DirectoryMode.OFFLINE) }
        compose.onNodeWithText("Shared · Private alias: Private alias").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsNotEnabled()
        compose.onNodeWithText("New thread").assertIsNotEnabled()
        compose.onNodeWithTag("thread-destination-$id").performClick()
        compose.runOnIdle { assertEquals(id.toString(), selection.value?.id?.value) }
    }
}
