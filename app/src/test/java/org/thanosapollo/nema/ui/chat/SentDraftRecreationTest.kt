package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.DirectChatPresenter
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftReply
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SentDraftRecreationTest {
    @get:Rule val composeRule = createRobolectricComposeRule()

    @Test fun acceptedSendCannotResurrectAfterOwnerAndPresenterRecreation() = recreate()
    @Test fun acceptedSendPreservesNewerOrdinaryInputAcrossRecreation() = recreate(newerInput = true)
    @Test fun acceptedCorrectionRestoresCompleteOrdinaryBackupAcrossRecreation() = recreate(correction = true)
    @Test fun acceptedCorrectionPreservesNewerEditAndBackupAcrossRecreation() = recreate(correction = true, newerInput = true)
    @Test fun failedSendRemainsExactAndRetryableAcrossRecreation() = recreate(rejectFirst = true)

    private fun recreate(newerInput: Boolean = false, correction: Boolean = false, rejectFirst: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sent-draft-${UUID.randomUUID()}.db"
        var fixture = RoutePresentationFixture(name)
        val home = mutableStateOf(true)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Unit>()
        val attempts = mutableListOf<DraftSnapshot>()
        fun presenter(): DirectChatPresenter {
            val active = fixture
            return DirectChatPresenter(
                AccountConfiguration.create(AccountId.require(active.account), "${active.account}@example.org",
                    active.account, null, "example.org", null),
                active.repository, active.scope,
                enqueue = { account, snapshot ->
                    attempts += snapshot
                    entered.complete(Unit)
                    release.await()
                    val accepted = if (rejectFirst && attempts.size == 1) false else {
                        val id = UUID.randomUUID().toString()
                        MessageStore(active.database).composeDirectDraft(
                            accountId = account.id.value, operationId = id, localMessageId = id, originId = id,
                            peerJid = snapshot.key.canonicalBarePeer, senderJid = account.bareJid.value,
                            body = snapshot.body, thread = snapshot.outboundThread ?: snapshot.key.thread,
                            draftThread = snapshot.key.thread, attachmentUrl = snapshot.attachmentUrl,
                            attachmentName = snapshot.attachmentName, attachmentMime = snapshot.attachmentMime,
                            attachmentSize = snapshot.attachmentSize, replyToId = snapshot.reply?.id,
                            replyToJid = snapshot.reply?.to, replyFallbackBody = snapshot.reply?.body,
                            replyFallbackSender = snapshot.reply?.senderLabel,
                            replaceId = snapshot.correction?.referenceId,
                            correctionTargetMessageId = snapshot.correction?.localMessageId,
                        ) != null
                    }
                    settled.complete(Unit)
                    accepted
                },
            ).also(active.presenters::add)
        }
        try {
            val key = DirectConversationKey(fixture.account, fixture.peer)
            val otherKey = DirectConversationKey(fixture.account, fixture.other)
            val reply = DraftReply("reply", fixture.peer, "quoted", "A")
            runBlocking {
                if (correction) fixture.seedEditableMessage()
                fixture.repository.saveDraft(key, "stored A", reply,
                    "https://example.org/file", "file.pdf", "application/pdf", 19L)
            }
            var currentPresenter = presenter()
            lateinit var retained: ComposerOwner
            val restoration = StateRestorationTester(composeRule)
            restoration.setContent {
                val current by currentPresenter.state.collectAsState()
                retained = rememberComposerOwner(current.accountId)
                MaterialTheme {
                    if (home.value) ConversationContent(
                        state = current, composerOwner = retained, connectionStatus = "Connected",
                        onSelectPeer = currentPresenter::selectPeer,
                        onCloseConversation = currentPresenter::closeConversation,
                        onDraftChange = currentPresenter::updateDraft, onSend = currentPresenter::sendDraft,
                        onAcknowledgeCompletedSends = currentPresenter::acknowledgeCompletedSends,
                    )
                }
            }
            fun open(peer: String) {
                runBlocking { currentPresenter.selectPeer(peer) }
                composeRule.waitUntil {
                    currentPresenter.state.value.selectedPeer == peer &&
                        currentPresenter.state.value.contentStatus == ChatContentStatus.Ready
                }
                composeRule.waitForIdle()
            }
            open(fixture.other)
            composeRule.onNodeWithTag("message-composer").performTextReplacement("unrelated retained B")
            composeRule.waitUntil { retained.pendingDraftAttempts.value.isEmpty() }
            open(fixture.peer)
            if (correction) {
                composeRule.onNodeWithText("editable original").performTouchInput { longClick() }
                composeRule.onNodeWithText("Edit").performClick()
                composeRule.onNodeWithTag("message-composer").performTextReplacement("corrected text")
            }
            val original = retained.composerStates.value.getValue(key)
            composeRule.onNodeWithContentDescription("Send").performClick()
            composeRule.waitUntil { entered.isCompleted }
            if (newerInput) composeRule.onNodeWithTag("message-composer").performTextReplacement("newer input")
            val before = retained.composerStates.value.getValue(key)
            composeRule.runOnIdle { currentPresenter.closeConversation(); home.value = false }
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            release.complete(Unit)
            composeRule.waitUntil { settled.isCompleted && retained.pendingDraftAttempts.value.isEmpty() }
            composeRule.waitForIdle()
            val expectedCount = (if (correction) 1 else 0) + (if (rejectFirst) 0 else 1)
            assertEquals(expectedCount, runBlocking { fixture.database.messageDao().outboxes(fixture.account).size })
            if (!correction && !newerInput && !rejectFirst) {
                assertNull(runBlocking { fixture.database.messageDao().directDraft(fixture.account, fixture.peer) })
            }
            val oldOwner = retained
            val oldPresenter = currentPresenter
            // Reopen the on-disk database and replace the presenter, not just the visible route.
            fixture.close()
            fixture = RoutePresentationFixture(name, seed = false)
            currentPresenter = presenter()
            restoration.emulateSavedInstanceStateRestore()
            assertNotSame(oldOwner, retained)
            assertNotSame(oldPresenter, currentPresenter)
            assertTrue(currentPresenter.state.value.completedSendSnapshots.isEmpty())
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            composeRule.runOnIdle { home.value = true }
            open(fixture.peer)
            val expected = when {
                rejectFirst || newerInput -> before.toDraftSnapshot(ConversationVenue.Direct)
                correction -> DraftSnapshot(key, "stored A", original.revision + 1, reply = reply,
                    attachmentUrl = "https://example.org/file", attachmentName = "file.pdf",
                    attachmentMime = "application/pdf", attachmentSize = 19L)
                else -> DraftSnapshot(key, "", original.revision + 1)
            }
            composeRule.onNodeWithTag("message-composer").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, AnnotatedString(expected.body),
            ))
            val restored = retained.composerStates.value.getValue(key)
            assertEquals(expected, restored.toDraftSnapshot(ConversationVenue.Direct))
            assertEquals(if (rejectFirst || newerInput) before.correctionBackup else null, restored.correctionBackup)
            if (!correction && !newerInput && !rejectFirst) {
                composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
                composeRule.onNodeWithContentDescription("Send").performClick()
                composeRule.waitForIdle()
                assertEquals(1, attempts.size)
            }
            if (rejectFirst) {
                composeRule.onNodeWithContentDescription("Send").performClick()
                composeRule.waitUntil { attempts.size == 2 && retained.composerStates.value.getValue(key).body.isEmpty() }
                assertEquals(attempts[0], attempts[1])
            }
            open(fixture.other)
            assertEquals("unrelated retained B", retained.composerStates.value.getValue(otherKey).body)
            assertEquals(if (rejectFirst) 1 else expectedCount,
                runBlocking { fixture.database.messageDao().outboxes(fixture.account).size })
        } finally {
            release.complete(Unit)
            fixture.close()
            context.deleteDatabase(name)
        }
    }
}
