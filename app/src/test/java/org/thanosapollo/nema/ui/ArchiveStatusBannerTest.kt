package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ArchiveFailureKind
import org.thanosapollo.nema.chat.ArchiveSyncState
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArchiveStatusBannerTest {
    @get:Rule val compose = createComposeRule()
    private val owner = SessionIdentity(AccountId.require("owner"), ConnectionGeneration.require(1))
    private val connected = ConnectionState.Connected(owner.accountId, owner.generation)
    private val capabilities = SessionCapabilities(true, CarbonCapabilityState.UNSUPPORTED, false)

    @Test fun `incomplete retry sends displayed token and disappears for successor connection`() {
        val incomplete = ArchiveSyncState.Incomplete(ArchiveSyncState.RetryableError(owner, capabilities, "private detail", ArchiveFailureKind.TRANSIENT))
        val connection = mutableStateOf<ConnectionState>(connected)
        var submitted: ArchiveSyncState? = null
        compose.setContent { MaterialTheme {
            ArchiveStatusBanner(incomplete, owner.accountId, connection.value) { submitted = it }
        } }
        compose.onNodeWithText("Server history is unavailable. History may be incomplete.").assertIsDisplayed()
        compose.onNodeWithText("Retry history").performClick()
        assertSame(incomplete, submitted)
        compose.onNodeWithText("private detail", substring = true).assertDoesNotExist()
        compose.runOnIdle { connection.value = connected.copy(generation = ConnectionGeneration.require(2)) }
        compose.onNodeWithTag("archive-status").assertDoesNotExist()
    }

    @Test fun `continuation has separate action and waiting never offers premature manual retry`() {
        val continuation = ArchiveSyncState.ContinuationRequired(owner)
        val state = mutableStateOf<ArchiveSyncState>(ArchiveSyncState.WaitingToRetry(owner, 1, 2_000))
        var submitted: ArchiveSyncState? = null
        compose.setContent { MaterialTheme { ArchiveStatusBanner(state.value, owner.accountId, connected) { submitted = it } } }
        compose.onNodeWithText("History may be incomplete. Retrying shortly (attempt 1).").assertIsDisplayed()
        compose.runOnIdle { state.value = ArchiveSyncState.WaitingToRetry(owner, 4, 15_000) }
        compose.onNodeWithText("History may be incomplete. Retrying shortly (attempt 4).").assertIsDisplayed()
        compose.onNodeWithText("Retry history").assertDoesNotExist()
        compose.runOnIdle { state.value = continuation }
        compose.onNodeWithText("Continue sync").performClick()
        assertSame(continuation, submitted)
        compose.runOnIdle { state.value = ArchiveSyncState.Ready(owner, capabilities) }
        compose.onNodeWithTag("archive-status").assertDoesNotExist()
    }

    @Test fun `unsupported status is honest and account disconnect or generation mismatch hides stale history status`() {
        val unsupported = ArchiveSyncState.Unsupported(owner, capabilities.copy(mamV2 = false))
        val notice = requireNotNull(archiveNotice(unsupported, owner.accountId, connected))
        assertEquals("This server does not support message history.", notice.text)
        assertNull(notice.action)
        assertNull(archiveNotice(unsupported, AccountId.require("other"), connected))
        assertNull(archiveNotice(unsupported, owner.accountId, ConnectionState.Stopped))
        assertNull(archiveNotice(unsupported, owner.accountId, connected.copy(generation = ConnectionGeneration.require(2))))
        val invalid = ArchiveSyncState.Incomplete(ArchiveSyncState.RetryableError(owner, capabilities, "private payload"))
        assertEquals("Server history could not be processed. History may be incomplete.", archiveNotice(invalid, owner.accountId, connected)?.text)
    }
}
