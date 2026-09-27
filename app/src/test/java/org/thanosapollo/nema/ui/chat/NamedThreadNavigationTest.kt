package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatRoute
import org.thanosapollo.nema.chat.ChatRouteOccurrence
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.RecentThread
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NamedThreadNavigationTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun directChatCreatesRenamesAndSwitchesWithoutLosingDrafts() = journey(false)
    @Test fun roomCreatesRenamesAndSwitchesWithoutLosingDrafts() = journey(true)

    private fun journey(room: Boolean) {
        val thread = ThreadRef(ThreadId.require("project-id"))
        val kind = if (room) MessageKind.GROUPCHAT else MessageKind.CHAT
        val created = mutableListOf<String>()
        var renamed: RecentThread? = null
        compose.setContent {
            MaterialTheme {
                var state by remember { mutableStateOf(DirectChatState(
                    accountId = "account", selectedPeer = "peer@example.org", selectedPeerGroupChat = room,
                    routeOccurrence = ChatRouteOccurrence(ChatRoute("peer@example.org"), 1),
                    // Legacy implicit sessions must not manufacture a named list item.
                    recentThreads = listOf(RecentThread(ThreadRef(ThreadId.require("implicit")), "Legacy session", 0, kind)),
                )) }
                fun select(next: ThreadRef?) {
                    state = state.copy(
                        selectedThread = next,
                        routeOccurrence = ChatRouteOccurrence(ChatRoute("peer@example.org", next), state.routeOccurrence.generation + 1),
                        draft = "",
                    )
                }
                ConversationContent(
                    state = state, connectionStatus = "Offline", onSelectPeer = { true }, onCloseConversation = {},
                    onCloseThread = { select(null) },
                    onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(false) },
                    onSelectThreadDestination = { origin, next -> assertEquals(state.routeOccurrence, origin); select(next) },
                    onCreateNamedThread = { origin, name ->
                        assertEquals(state.routeOccurrence, origin)
                        created += name
                        state = state.copy(recentThreads = state.recentThreads + RecentThread(thread, name, 0, kind, locallyNamed = true))
                        select(thread)
                        true
                    },
                    onRenameNamedThread = { origin, target, name, _ ->
                        assertEquals(state.routeOccurrence, origin)
                        renamed = target
                        state = state.copy(recentThreads = state.recentThreads.map { if (it.thread == target.thread) it.copy(title = name) else it })
                        true
                    },
                )
            }
        }
        compose.onNodeWithTag("thread-switcher").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Message Main").assertIsDisplayed()
        compose.onNodeWithTag("message-composer").performTextInput("main draft")
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithTag("thread-destination-main").assertIsDisplayed()
        compose.onNodeWithText("Legacy session").assertDoesNotExist()
        compose.onNodeWithText("New thread").performClick()
        compose.onNodeWithText("Create thread").performClick()
        compose.onNodeWithText("Enter a thread name.").assertIsDisplayed()
        assertTrue(created.isEmpty())
        compose.onNodeWithTag("thread-name-input").performTextInput("Project")
        compose.onNodeWithText("Create thread").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Project"), created)
        compose.onNodeWithText("Message Project").assertIsDisplayed()
        compose.onNodeWithTag("message-composer").performTextInput("project draft")
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithContentDescription("Rename Project locally").performClick()
        compose.onNodeWithTag("thread-name-input").performTextReplacement("Task")
        compose.onNodeWithText("Save name").performClick()
        compose.waitForIdle()
        assertEquals(thread, renamed?.thread)
        compose.onNodeWithTag("message-composer").assertTextContains("project draft")
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithTag("thread-destination-main").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("message-composer").assertTextContains("main draft")
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithTag("thread-destination-project-id").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("message-composer").assertTextContains("project draft")
        compose.onNodeWithContentDescription("Back to conversation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("message-composer").assertTextContains("main draft")
    }

    @Test fun failedLocalSavePreservesInputAndRetriesWithoutInventingDirectoryRows() {
        val attempts = mutableListOf<String>()
        val occurrence = ChatRouteOccurrence(ChatRoute("peer@example.org"), 7)
        compose.setContent {
            MaterialTheme {
                ThreadSwitcher(occurrence, null, emptyList(), { _, _ -> }, { origin, name ->
                    assertEquals(occurrence, origin)
                    attempts += name
                    attempts.size > 1
                }, { _, _, _, _ -> false })
            }
        }
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithText("New thread").performClick()
        compose.onNodeWithTag("thread-name-input").performTextInput("Project")
        compose.onNodeWithText("Create thread").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not saved. Try again.").assertIsDisplayed()
        compose.onNodeWithTag("thread-name-input").assertTextContains("Project")
        compose.onNodeWithText("Create thread").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Project", "Project"), attempts)
        compose.onNodeWithTag("thread-name-input").assertDoesNotExist()
    }
}
