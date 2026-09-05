package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftReply
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.ui.PrimaryDestination

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposerRetentionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun failedSaveSurvivesSettingsAndRoster() = destinationRoundTrip(false)
    @Test fun throwingSaveSurvivesSettingsAndRoster() = destinationRoundTrip(null)
    @Test fun successfulSaveSurvivesSettingsAndRoster() = destinationRoundTrip(true)

    @Test fun fullKeysRemainIsolatedAndAccountReplacementDropsProvisionalInput() {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            val replacement = fixture.presenter("replacement")
            val owner = mutableStateOf(presenter)
            val home = mutableStateOf(true)
            val saves = mutableListOf<CompletableDeferred<Boolean>>()
            val thread = ThreadId.require("same-child")
            val keys = listOf(
                DirectConversationKey(fixture.account, fixture.peer),
                DirectConversationKey(fixture.account, fixture.peer, ThreadRef(thread, ThreadId.require("parent-a"))),
                DirectConversationKey(fixture.account, fixture.peer, ThreadRef(thread, ThreadId.require("parent-b"))),
                DirectConversationKey(fixture.account, "room@conference.example.org"),
                DirectConversationKey(fixture.account, "room@conference.example.org", ThreadRef(thread)),
            )
            composeRule.setContent {
                val current by owner.value.state.collectAsState()
                val retained = rememberComposerOwner(current.accountId)
                MaterialTheme {
                    if (home.value) ConversationContent(
                        state = current, composerOwner = retained, connectionStatus = "Connected",
                        onSelectPeer = owner.value::selectPeer, onCloseConversation = owner.value::closeConversation,
                        onDraftChange = { CompletableDeferred<Boolean>().also(saves::add) }, onSend = owner.value::sendDraft,
                    )
                }
            }
            fun open(key: DirectConversationKey) {
                runBlocking {
                    if (key.canonicalBarePeer.startsWith("room@")) presenter.joinRoom(key.canonicalBarePeer)
                    else presenter.selectPeer(key.canonicalBarePeer)
                    key.thread?.let { presenter.continueThread(it) }
                }
                composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready && presenter.state.value.selectedThread == key.thread }
            }
            keys.forEachIndexed { index, key ->
                open(key)
                composeRule.onNodeWithTag("message-composer").performTextReplacement("retained-$index")
                composeRule.runOnIdle { presenter.closeConversation(); home.value = false }
                composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
                composeRule.runOnIdle { home.value = true }
            }
            keys.forEachIndexed { index, key ->
                open(key)
                composeRule.onNodeWithTag("message-composer").assertTextEquals("retained-$index")
            }
            runBlocking { replacement.selectPeer(fixture.peer) }
            composeRule.runOnIdle { owner.value = replacement }
            composeRule.waitUntil { replacement.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.runOnIdle { saves.forEach { it.complete(false) } }
            composeRule.onNodeWithTag("message-composer").assertTextEquals("other account draft")
            composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
        }
    }

    @Test fun restoringPendingSaveShowsRecoverableFailureAndIgnoresOldCompletion() {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(composeRule)
            val pending = CompletableDeferred<Boolean>()
            lateinit var owner: ComposerOwner
            restoration.setContent {
                val current by presenter.state.collectAsState()
                owner = rememberComposerOwner(current.accountId)
                MaterialTheme {
                    ConversationContent(state = current, composerOwner = owner, connectionStatus = "Connected",
                        onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                        onDraftChange = { pending }, onSend = presenter::sendDraft)
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithTag("message-composer").performTextReplacement("exact pending input")
            composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            composeRule.onNodeWithTag("message-composer").assertTextEquals("exact pending input")
            composeRule.onNodeWithText("Draft not saved").assertExists()
            composeRule.runOnIdle {
                assertTrue(owner.pendingDraftAttempts.value.isEmpty())
                pending.complete(true)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Draft not saved").assertExists()
        }
    }

    @Test fun presenterSendSettlesWhileHomeIsAbsent() {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            val home = mutableStateOf(true)
            composeRule.setContent {
                val current by presenter.state.collectAsState()
                val retained = rememberComposerOwner(current.accountId)
                MaterialTheme {
                    if (home.value) ConversationContent(
                        state = current, composerOwner = retained, connectionStatus = "Connected",
                        onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                        onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft,
                        onAcknowledgeCompletedSends = presenter::acknowledgeCompletedSends,
                    )
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithContentDescription("Send").performClick()
            composeRule.waitUntil { fixture.sendEntered.isCompleted }
            composeRule.runOnIdle { presenter.closeConversation(); home.value = false }
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            fixture.sendRelease.complete(Unit)
            composeRule.waitUntil { presenter.state.value.completedSendSnapshots.isNotEmpty() }
            composeRule.runOnIdle { home.value = true }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithTag("message-composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            composeRule.waitUntil { presenter.state.value.completedSendSnapshots.isEmpty() }
        }
    }

    @Test fun acceptedAttachmentsHydrateWithNewPresenterAndDatabaseConnection() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "composer-retention-${java.util.UUID.randomUUID()}.db"
        val saved = mutableListOf<org.thanosapollo.nema.chat.DraftSnapshot>()
        try {
            RoutePresentationFixture(name).use { fixture ->
                val presenter = fixture.presenter()
                for ((index, peer) in listOf(fixture.peer, fixture.other).withIndex()) {
                    runBlocking { presenter.selectPeer(peer) }
                    composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
                    val snapshot = org.thanosapollo.nema.chat.DraftSnapshot(
                        key = DirectConversationKey(fixture.account, peer), body = if (index == 0) "caption" else "",
                        composerRevision = 1L, attachmentUrl = "https://example.org/$index",
                        attachmentName = "$index.pdf", attachmentMime = "application/pdf", attachmentSize = 17L,
                        reply = if (index == 0) DraftReply("ref", peer, "quoted", "sender") else null,
                    )
                    assertTrue(runBlocking { presenter.updateDraft(snapshot).await() })
                    saved += snapshot
                }
            }
            RoutePresentationFixture(name, seed = false).use { fixture ->
                val presenter = fixture.presenter()
                lateinit var retained: ComposerOwner
                composeRule.setContent {
                    val current by presenter.state.collectAsState()
                    retained = rememberComposerOwner(current.accountId)
                    MaterialTheme {
                        ConversationContent(state = current, composerOwner = retained, connectionStatus = "Connected",
                            onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                            onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft)
                    }
                }
                saved.forEach { snapshot ->
                    runBlocking { presenter.selectPeer(snapshot.key.canonicalBarePeer) }
                    composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
                    composeRule.onNodeWithTag("message-composer").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(snapshot.body)))
                    composeRule.runOnIdle {
                        assertEquals(snapshot.copy(composerRevision = 0L),
                            retained.composerStates.value.getValue(snapshot.key).toDraftSnapshot(ConversationVenue.Direct))
                    }
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun destinationRoundTrip(result: Boolean?) {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            val destination = mutableStateOf(PrimaryDestination.HOME)
            val save = CompletableDeferred<Boolean>()
            val key = DirectConversationKey(fixture.account, fixture.peer)
            runBlocking {
                fixture.repository.saveDraft(key, "stored A", DraftReply("reply", fixture.peer, "quoted", "A"),
                    "https://example.org/file", "file.pdf", "application/pdf", 19L)
            }
            lateinit var retained: ComposerOwner
            composeRule.setContent {
                val current by presenter.state.collectAsState()
                val composerOwner = rememberComposerOwner(current.accountId)
                retained = composerOwner
                MaterialTheme {
                    if (destination.value == PrimaryDestination.HOME) {
                        ConversationContent(
                            composerOwner = composerOwner,
                            state = current, connectionStatus = "Connected",
                            onSelectPeer = presenter::selectPeer,
                            onCloseConversation = presenter::closeConversation,
                            onDraftChange = { save }, onSend = presenter::sendDraft,
                        )
                    }
                }
            }
            fun reopen() {
                runBlocking { presenter.selectPeer(fixture.peer) }
                composeRule.waitUntil { presenter.state.value.selectedPeer == fixture.peer && presenter.state.value.contentStatus == ChatContentStatus.Ready }
            }
            reopen()
            composeRule.onNodeWithTag("message-composer").performTextReplacement("exact unsaved caption")
            composeRule.runOnIdle {
                presenter.closeConversation()
                destination.value = PrimaryDestination.SETTINGS
            }
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            composeRule.runOnIdle {
                if (result == null) save.completeExceptionally(IllegalStateException("save rejected"))
                else save.complete(result)
                destination.value = PrimaryDestination.ROSTER
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { destination.value = PrimaryDestination.HOME }
            reopen()
            composeRule.onNodeWithTag("message-composer").assertTextEquals("exact unsaved caption")
            composeRule.onNodeWithTag("composer-reply-preview").assertExists()
            if (result != true) composeRule.onNodeWithText("Draft not saved").assertExists()
            composeRule.runOnIdle {
                val entry = retained.composerStates.value.getValue(key)
                assertEquals("https://example.org/file", entry.attachmentUrl)
                assertEquals("file.pdf", entry.attachmentName)
                assertEquals("application/pdf", entry.attachmentMime)
                assertEquals(19L, entry.attachmentSize)
                assertEquals(result != true, entry.ordinarySaveUnconfirmed)
                assertTrue(retained.pendingDraftAttempts.value.isEmpty())
            }
        }
    }
}
