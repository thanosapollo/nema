package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.storage.PeerEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RosterContentTest {
    @get:Rule val composeRule = createRobolectricComposeRule()

    @Test fun bottomBarOrderSelectionAndTargetSize() {
        composeRule.setContent {
            MaterialTheme { SessionBottomBar(selected = PrimaryDestination.ROSTER, onSelect = {}) }
        }
        val labels = listOf("Roster", "Home", "Settings")
        assertEquals(labels, PrimaryDestination.entries.map { it.label })
        labels.forEach { composeRule.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
        composeRule.onNodeWithText("Roster").assertIsSelected()
        composeRule.onAllNodesWithContentDescription("Primary destinations").assertCountEquals(1)
    }

    @Test fun emptyThenOfflineCachedRosterUsesSortedLabelsAndAvatar() {
        val peers = MutableStateFlow(emptyList<PeerEntity>())
        composeRule.setContent {
            MaterialTheme { RosterContent(peers = peers, onSelectPeer = { true }, onAccepted = {}) }
        }
        composeRule.onNodeWithText("No roster contacts").assertIsDisplayed()
        peers.value = listOf(
            peer("z@example.org", display = "VCard", roster = "Server", local = "Local", photo = byteArrayOf(1)),
            peer("a@example.org"),
        )
        composeRule.onNodeWithText("Local").assertIsDisplayed()
        composeRule.onNodeWithText("a@example.org").assertIsDisplayed()
        assertEquals(listOf("a@example.org", "z@example.org"),
            composeRule.onNodeWithTag("roster-list").fetchSemanticsNode().children.map {
                it.config[SemanticsProperties.TestTag].removePrefix("roster-row-")
            })
        assertEquals("Server", rosterLabel(peer("x", display = "VCard", roster = "Server")))
        assertEquals("VCard", rosterLabel(peer("x", display = "VCard")))
    }

    @Test fun accountSwitchClearsRowsAndCancelsPendingSelection() {
        val decision = CompletableDeferred<Boolean>()
        val first = MutableStateFlow(listOf(peer("a@example.org")))
        val second = MutableStateFlow(emptyList<PeerEntity>())
        var destination = PrimaryDestination.ROSTER
        var selected: String? = null
        lateinit var switchAccount: () -> Unit
        composeRule.setContent {
            var account by remember { mutableStateOf("a") }
            switchAccount = { account = "b" }
            MaterialTheme {
                key(account) { RosterContent(
                    peers = if (account == "a") first else second,
                    onSelectPeer = { jid -> selected = jid; decision.await() },
                    onAccepted = { destination = PrimaryDestination.HOME },
                ) }
            }
        }
        composeRule.onNodeWithText("a@example.org").performClick()
        composeRule.runOnIdle { switchAccount() }
        composeRule.onNodeWithText("a@example.org").assertDoesNotExist()
        composeRule.onNodeWithText("No roster contacts").assertIsDisplayed()
        decision.complete(true)
        composeRule.waitForIdle()
        assertEquals(PrimaryDestination.ROSTER, destination)
        assertEquals("a@example.org", selected)
    }

    @Test fun acceptedSelectionOpensHomeWithSelectedPeer() {
        var destination = PrimaryDestination.ROSTER
        var selected: String? = null
        var accepted = false
        composeRule.setContent {
            MaterialTheme {
                RosterContent(
                    MutableStateFlow(listOf(peer("b@example.org"))),
                    { selected = it; accepted },
                    { destination = PrimaryDestination.HOME },
                )
            }
        }
        composeRule.onNodeWithText("b@example.org").performClick()
        composeRule.waitForIdle()
        assertEquals(PrimaryDestination.ROSTER, destination)
        accepted = true
        composeRule.onNodeWithText("b@example.org").performClick()
        composeRule.waitForIdle()
        assertEquals(PrimaryDestination.HOME, destination)
        assertEquals("b@example.org", selected)
    }

    private fun peer(jid: String, display: String? = null, roster: String? = null,
        local: String? = null, photo: ByteArray? = null) = PeerEntity(
        accountId = "account-a", jid = jid, displayName = display, localNickname = local,
        rosterName = roster, inRoster = true, photoBytes = photo,
    )
}
