package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.createRobolectricComposeRule
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.ui.chat.ConversationContent
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionRecoveryTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val account = AccountId.require("account-a")

    @Test fun homeSearchKeepsAccountQualifiedReconnectAndConnectingDisablesIt() {
        val enabled = mutableStateOf(true)
        val status = mutableStateOf("Waiting to reconnect")
        val taps = mutableListOf<AccountId>()
        compose.setContent { MaterialTheme {
            HomeContent(emptyList(), true, status.value, "Own", { true },
                connectionRecovery = ConnectionRecovery(account, enabled.value), onReconnect = { taps += it })
        } }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Waiting to reconnect").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(listOf(account), taps)
        compose.runOnIdle { enabled.value = false; status.value = "Connecting" }
        compose.onNodeWithText("Reconnect").assertIsNotEnabled().performClick()
        assertEquals(listOf(account), taps)
        compose.runOnIdle { status.value = "Connected" }
        compose.onNodeWithTag("connection-status").assertDoesNotExist()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
    }

    @Test fun readyLoadingAndFailedChatKeepReconnectWithoutConnectedBanner() {
        val state = mutableStateOf(DirectChatState(accountId = account.value, selectedPeer = "peer@example.org",
            contentStatus = ChatContentStatus.Loading))
        val taps = mutableListOf<AccountId>()
        compose.setContent { MaterialTheme {
            ConversationContent(state = state.value, connectionStatus = "Stopped", onSelectPeer = { true },
                onCloseConversation = {}, onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(true) },
                connectionRecovery = ConnectionRecovery(account), onReconnect = { taps += it })
        } }
        for (phase in listOf(ChatContentStatus.Loading, ChatContentStatus.Failed, ChatContentStatus.Ready)) {
            compose.runOnIdle { state.value = state.value.copy(contentStatus = phase) }
            compose.onNodeWithText("Stopped").assertIsDisplayed()
            compose.onNodeWithText("Reconnect").assertIsDisplayed().performClick()
        }
        assertEquals(listOf(account, account, account), taps)
    }

    @Test fun accountSettingsSessionHasReconnectAndCapturesRenderedAccount() {
        val model = mutableStateOf(ConnectionRecovery(account))
        val taps = mutableListOf<AccountId>()
        compose.setContent { MaterialTheme {
            AccountSettingsContent(connectionStatus = "Stopped", activeAccountId = model.value.accountId,
                connectionRecovery = model.value, onReconnect = { taps += it })
        } }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("connection-reconnect"))
        compose.onNodeWithTag("connection-reconnect").performClick()
        val replacement = AccountId.require("account-b")
        compose.runOnIdle { model.value = ConnectionRecovery(replacement) }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("connection-reconnect"))
        compose.onNodeWithTag("connection-reconnect").performClick()
        assertEquals(listOf(account, replacement), taps)
    }
}
