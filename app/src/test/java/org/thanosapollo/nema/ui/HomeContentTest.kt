package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ConversationSummary
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.ui.chat.ConversationContent
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeShowsCompactChromeWithoutPermanentJidForm() {
        var profileClicks = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary(
                                peerJid = "alice@example.org",
                                preview = "<message><body>raw</body></message>",
                                localSequence = 2,
                                displayName = "Alice",
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    ownLabel = "me@example.org",
                    onOpenOwnProfile = { profileClicks++ },
                )
            }
        }

        composeRule.onNodeWithText("Nema").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
        composeRule.onNodeWithText("Direct message JID").assertDoesNotExist()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Message").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("New chat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Own profile").performClick()
        composeRule.runOnIdle { assertEquals(1, profileClicks) }
    }

    @Test
    fun homeShowsUnreadCountOnConversationRow() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary(
                                peerJid = "alice@example.org",
                                preview = "new message",
                                localSequence = 2,
                                displayName = "Alice",
                                unreadCount = 3,
                            ),
                        ),
                        conversationsReady = true,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("conversation-unread", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3 unread", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun homeGroupPreviewShowsOccupantNotGroupLabel() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary(
                                peerJid = "jabber-el@conference.hmm.st",
                                preview = "Thanks! How about emacs-jabber 0.13.1?",
                                localSequence = 2,
                                displayName = "jabber.el",
                                groupChat = true,
                                previewSender = "debacle",
                            ),
                        ),
                        conversationsReady = true,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("debacle", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Thanks! How about emacs-jabber 0.13.1?", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Group ·", substring = true).assertDoesNotExist()
    }

    @Test
    fun homeDirectOutgoingPreviewShowsYou() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary(
                                peerJid = "alice@example.org",
                                preview = "on my way",
                                localSequence = 2,
                                displayName = "Alice",
                                previewSender = "You",
                            ),
                        ),
                        conversationsReady = true,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("You", substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("on my way", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun homeDoesNotClaimEmptyUntilConversationQueryCompletes() {
        lateinit var show: (Boolean) -> Unit
        composeRule.setContent {
            var ready by remember { mutableStateOf(false) }
            show = { ready = it }
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(accountId = "account-a", conversationsReady = ready),
                    connectionStatus = "Connecting",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("No conversations").assertDoesNotExist()
        composeRule.onNodeWithText("Loading conversations").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("New chat").assertIsDisplayed()
        composeRule.runOnIdle { show(true) }
        composeRule.onNodeWithText("No conversations").assertIsDisplayed()
    }

    @Test
    fun cachedConversationsShowBeforeReadyFlag() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary("alice@example.org", "hi", 1, "Alice"),
                        ),
                        conversationsReady = false,
                    ),
                    connectionStatus = "Connecting",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    ownLabel = "me@example.org",
                )
            }
        }

        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("hi").assertIsDisplayed()
        composeRule.onNodeWithText("Loading conversations").assertDoesNotExist()
        composeRule.onNodeWithText("No conversations").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("New chat").assertIsDisplayed()
    }

    @Test
    fun searchFiltersConversationsAndNewChatOpensDialog() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary("alice@example.org", "hi", 1, "Alice"),
                            ConversationSummary("bob@example.org", "later", 2, "Bob"),
                        ),
                    ),
                    connectionStatus = "Connecting",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    ownLabel = "me@example.org",
                )
            }
        }

        composeRule.onNodeWithText("Connecting").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithText("Search conversations").performTextInput("bob")
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule.onNodeWithText("Alice").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("New chat").performClick()
        composeRule.onNodeWithText("Direct message JID").assertIsDisplayed()
        composeRule.onNodeWithText("Open conversation").assertIsDisplayed()
    }

    @Test
    fun chatAppBarExposesBackAndKeepsPeerLabel() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        selectedPeer = "alice@example.org",
                        selectedPeerDisplayName = "Alice",
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Conversation actions").assertIsDisplayed()
    }

    @Test
    fun homeStaysComposedUnderOpenChatWithoutOwningSessionBar() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversations = listOf(
                            ConversationSummary("alice@example.org", "hi", 1, "Alice"),
                        ),
                        conversationsReady = true,
                        selectedPeer = "alice@example.org",
                        selectedPeerDisplayName = "Alice",
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    ownLabel = "me@example.org",
                )
            }
        }

        composeRule.onNodeWithText("Nema", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Primary destinations", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    @Test
    fun emptyHomeKeepsSettingsClickable() {
        var openedSettings = false
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = DirectChatState(
                        accountId = "account-a",
                        conversationsReady = true,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    ownLabel = "me@example.org",
                    onOpenOwnProfile = { openedSettings = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("New chat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Own profile").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(openedSettings) }
    }
}
