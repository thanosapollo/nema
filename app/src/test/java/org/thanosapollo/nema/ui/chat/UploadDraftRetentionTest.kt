package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.httpupload.UploadedFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UploadDraftRetentionTest {
    @get:Rule val composeRule = createRobolectricComposeRule()

    @Test fun heldPickerAcrossPeerSavesOrigin() = completion("peer", false, true)
    @Test fun heldPickerAcrossThreadRetainsFalseSave() = completion("thread", false, false)
    @Test fun heldPickerAcrossTabRetainsThrowingSave() = completion("tab", false, null)
    @Test fun heldUploadAcrossTabSavesOrigin() = completion("tab", true, true)
    @Test fun heldPickerAcrossAccountDoesNotUpload() = completion("account", false, true)
    @Test fun heldUploadAcrossAccountDoesNotSave() = completion("account", true, true)
    @Test fun heldPickerRecreationIgnoresLateResult() = completion("recreation", false, true)
    @Test fun heldUploadRecreationCancelsUnacceptedWork() = completion("recreation", true, true)
    @Test fun secondPickerDoesNotReplaceOrigin() = completion("second", false, true)

    @Test fun emptyDirectUploadReopensAfterPeerChange() = durableCompletion("direct", "")
    @Test fun heldRoomUploadReopensAfterVenueChange() = durableCompletion("room", " \t\n")
    @Test fun heldThreadUploadReopensFullLineage() = durableCompletion("thread", " literal caption ")

    private fun durableCompletion(origin: String, body: String) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "upload-${java.util.UUID.randomUUID()}.db"
        val active = mutableStateOf<org.thanosapollo.nema.chat.DirectChatPresenter?>(null)
        val registry = PickerRegistry()
        val uri = Uri.parse("content://test/file.pdf")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, "file".byteInputStream())
        val upload = CompletableDeferred<UploadedFile?>()
        var entered = false
        val saves = mutableListOf<DraftSnapshot>()
        val sends = mutableListOf<DraftSnapshot>()
        val restoration = StateRestorationTester(composeRule)
        val peer = if (origin == "room") "room@conference.example.org" else "a@example.org"
        val thread = if (origin == "thread") ThreadRef(ThreadId.require("child"), ThreadId.require("parent")) else null
        val key = DirectConversationKey("route-account", peer, thread)
        val venue = if (origin == "room") ConversationVenue.Room(null, 0) else ConversationVenue.Direct
        val reply = org.thanosapollo.nema.chat.DraftReply("literal-reply", peer, " quoted\nbody ", " Sender ")
        val expected = DraftSnapshot(key = key, body = body, composerRevision = 1L, groupChat = origin == "room",
            reply = reply, attachmentUrl = "https://example.org/file", attachmentName = "file.pdf",
            attachmentMime = "application/pdf", attachmentSize = 4L)
        lateinit var owner: ComposerOwner
        fun open(presenter: org.thanosapollo.nema.chat.DirectChatPresenter) {
            runBlocking {
                if (origin == "room") assertTrue(presenter.joinRoom(peer)) else assertTrue(presenter.selectPeer(peer))
                if (thread != null) assertTrue(presenter.continueThread(thread))
            }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready &&
                presenter.state.value.selectedThread == thread }
            composeRule.waitForIdle()
        }
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                active.value?.let { presenter ->
                    val state by presenter.state.collectAsState()
                    owner = rememberComposerOwner(state.accountId)
                    MaterialTheme {
                        ConversationContent(state = state, composerOwner = owner, connectionStatus = "Connected",
                            onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                            onSend = { sends += it; presenter.sendDraft(it) },
                            onDraftChange = { saves += it; presenter.updateDraft(it) },
                            onUploadFile = { _, _, _ -> entered = true; upload.await() })
                    }
                }
            }
        }
        try {
            RoutePresentationFixture(database).use { fixture ->
                runBlocking { fixture.repository.saveDraft(key, body, reply) }
                val presenter = fixture.presenter()
                composeRule.runOnIdle { active.value = presenter }
                open(presenter)
                composeRule.onNodeWithContentDescription("Attach file").performClick()
                composeRule.runOnIdle { registry.deliver(uri) }
                composeRule.waitUntil { entered }
                // The result arrives with a different peer/venue or same thread ID with different lineage visible.
                val destination = if (thread == null) DirectConversationKey(fixture.account, fixture.other)
                    else key.copy(thread = ThreadRef(thread.id, ThreadId.require("different-parent")))
                runBlocking {
                    if (thread == null) presenter.selectPeer(fixture.other) else presenter.continueThread(requireNotNull(destination.thread))
                }
                composeRule.waitUntil { destination in owner.composerStates.value }
                val untouched = owner.composerStates.value.getValue(destination)
                composeRule.runOnIdle { upload.complete(UploadedFile("https://example.org/file", "file.pdf", "application/pdf", 4L)) }
                composeRule.waitUntil { owner.composerStates.value[key]?.attachmentUrl != null && owner.pendingDraftAttempts.value.isEmpty() }
                composeRule.runOnIdle {
                    assertEquals(listOf(expected), saves)
                    assertEquals(expected, owner.composerStates.value.getValue(key).toDraftSnapshot(venue))
                    assertEquals(untouched, owner.composerStates.value.getValue(destination))
                    assertFalse(owner.composerStates.value.getValue(key).ordinarySaveUnconfirmed)
                    active.value = null
                }
            }
            RoutePresentationFixture(database, seed = false).use { fixture ->
                val presenter = fixture.presenter()
                composeRule.runOnIdle { active.value = presenter }
                open(presenter)
                composeRule.runOnIdle {
                    assertEquals(expected.copy(composerRevision = 0), owner.composerStates.value.getValue(key).toDraftSnapshot(venue))
                }
                fun assertPreview() {
                    composeRule.onNodeWithTag("composer-attachment-preview").assertIsDisplayed()
                    composeRule.onNodeWithText("file.pdf").assertIsDisplayed()
                    composeRule.onNodeWithText("application/pdf").assertIsDisplayed()
                    composeRule.onNodeWithContentDescription("Remove attachment").assertHasClickAction()
                        .assertHeightIsAtLeast(48.dp)
                    composeRule.onNodeWithTag("message-composer").assert(
                        androidx.compose.ui.test.SemanticsMatcher.expectValue(
                            androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                            androidx.compose.ui.text.AnnotatedString(body),
                        ),
                    )
                }
                assertPreview()
                restoration.emulateSavedInstanceStateRestore()
                assertPreview()
                composeRule.onNodeWithContentDescription("Remove attachment").performClick()
                composeRule.waitUntil { owner.pendingDraftAttempts.value.isEmpty() }
                composeRule.onNodeWithTag("composer-attachment-preview").assertDoesNotExist()
                val cleared = expected.copy(composerRevision = saves.last().composerRevision,
                    attachmentUrl = null, attachmentName = null, attachmentMime = null, attachmentSize = null)
                assertEquals(cleared, saves.last())
                assertEquals(cleared, owner.composerStates.value.getValue(key).toDraftSnapshot(venue))
                if (body.isBlank()) composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
                composeRule.runOnIdle { active.value = null }
            }
            RoutePresentationFixture(database, seed = false).use { fixture ->
                val presenter = fixture.presenter()
                composeRule.runOnIdle { active.value = presenter }
                open(presenter)
                composeRule.onNodeWithTag("composer-attachment-preview").assertDoesNotExist()
                assertEquals(expected.copy(composerRevision = 0, attachmentUrl = null, attachmentName = null,
                    attachmentMime = null, attachmentSize = null), owner.composerStates.value.getValue(key).toDraftSnapshot(venue))
                composeRule.onNodeWithTag("message-composer").performTextReplacement("text only")
                composeRule.waitUntil { owner.pendingDraftAttempts.value.isEmpty() }
                composeRule.onNodeWithContentDescription("Send").performClick()
                composeRule.waitUntil { sends.isNotEmpty() }
                assertEquals(expected.copy(body = "text only", composerRevision = sends.single().composerRevision,
                    attachmentUrl = null, attachmentName = null, attachmentMime = null, attachmentSize = null), sends.single())
                composeRule.runOnIdle { active.value = null }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun uploadDuringCorrectionCancelsAndReopens() = correctionCompletion(false, false)
    @Test fun uploadDuringCorrectionRemainsSendable() = correctionCompletion(true, false)
    @Test fun uploadDuringCorrectionRetainsFailedSaveForRetry() = correctionCompletion(false, true)

    @Test fun uploadDuringPendingCorrectionPreservesSingleSend() = correctionCompletion(true, false, true)
    @Test fun failedUploadSurvivesCorrectionEditAndSend() = correctionCompletion(true, true)
    @Test fun throwingBackupSaveSurvivesCorrectionEditAndCancel() = correctionCompletion(false, true, throwSave = true)
    @Test fun pendingBackupSaveFailsAfterCorrectionSend() = correctionCompletion(true, true, heldSave = true)
    @Test fun pendingBackupSaveFailsAfterCorrectionCancel() = correctionCompletion(false, true, heldSave = true)
    @Test fun newerCorrectionTextSurvivesOldSendAndUpload() = correctionCompletion(true, false, heldSend = true, newerEdit = true)

    @Test fun restoredCorrectionBackupAttachmentCanBeRemoved() = correctionCompletion(false, false, remove = true)

    // Controlled enqueue proves composer settlement, not durable-send acceptance (see S1 store tests).
    private fun correctionCompletion(send: Boolean, failSave: Boolean, heldSend: Boolean = false,
        throwSave: Boolean = false, heldSave: Boolean = false, newerEdit: Boolean = false, remove: Boolean = false) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "correction-upload-${java.util.UUID.randomUUID()}.db"
        val active = mutableStateOf<org.thanosapollo.nema.chat.DirectChatPresenter?>(null)
        val registry = PickerRegistry()
        val uri = Uri.parse("content://test/file.pdf")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, "file".byteInputStream())
        val upload = CompletableDeferred<UploadedFile?>()
        var entered = false
        var reject = false
        var sendAttempts = 0
        val backupSave = CompletableDeferred<Boolean>()
        val saves = mutableListOf<DraftSnapshot>()
        lateinit var owner: ComposerOwner
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                active.value?.let { presenter ->
                    val state by presenter.state.collectAsState()
                    owner = rememberComposerOwner(state.accountId)
                    MaterialTheme {
                        ConversationContent(state = state, composerOwner = owner, connectionStatus = "Connected",
                            onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                            onSend = { sendAttempts++; presenter.sendDraft(it) },
                            onDraftChange = {
                                saves += it
                                if (!reject) presenter.updateDraft(it) else if (heldSave) backupSave
                                else if (throwSave) throw IllegalStateException("backup save") else CompletableDeferred(false)
                            },
                            onUploadFile = { _, _, _ -> entered = true; upload.await() })
                    }
                }
            }
        }
        try {
            RoutePresentationFixture(database).use { fixture ->
                runBlocking { fixture.seedEditableMessage() }
                val presenter = fixture.presenter()
                if (!heldSend) fixture.sendRelease.complete(Unit)
                composeRule.runOnIdle { active.value = presenter }
                runBlocking { presenter.selectPeer(fixture.peer) }
                composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
                composeRule.waitForIdle()
                val key = DirectConversationKey(fixture.account, fixture.peer)
                composeRule.onNodeWithContentDescription("Attach file").performClick()
                composeRule.runOnIdle { registry.deliver(uri) }
                composeRule.waitUntil { entered }
                composeRule.onNodeWithText("editable original").performTouchInput { longClick() }
                composeRule.onNodeWithText("Edit").performClick()
                composeRule.onNodeWithTag("message-composer").performTextReplacement("edited correction")
                if (heldSend) {
                    composeRule.onNodeWithContentDescription("Send").performClick()
                    composeRule.waitUntil { fixture.sendEntered.isCompleted }
                }
                composeRule.runOnIdle { reject = failSave; upload.complete(UploadedFile("https://example.org/file", "file.pdf", "application/pdf", 4L)) }
                composeRule.waitUntil { owner.composerStates.value.getValue(key).correctionBackup?.attachmentUrl != null && (heldSend || heldSave || owner.pendingDraftAttempts.value.isEmpty()) }
                composeRule.runOnIdle {
                    val correction = owner.composerStates.value.getValue(key)
                    assertEquals("edited correction", correction.body)
                    assertNull(correction.attachmentUrl)
                    assertNotNull(correction.toDraftSnapshot(ConversationVenue.Direct).correction)
                    assertEquals("https://example.org/file", correction.correctionBackup?.attachmentUrl)
                    assertEquals(failSave || heldSend, correction.ordinarySaveUnconfirmed)
                    assertEquals(1, saves.size)
                    assertNull(saves.single().correction)
                    assertEquals("stored A", saves.single().body)
                    assertEquals(org.thanosapollo.nema.chat.DraftReply("reply", fixture.peer, "quoted", "A"), saves.single().reply)
                }
                composeRule.onNodeWithTag("composer-attachment-preview").assertDoesNotExist()
                composeRule.onNodeWithContentDescription("Remove attachment").assertDoesNotExist()
                if (failSave) {
                    composeRule.onNodeWithTag("message-composer").performTextReplacement("edited after save failure")
                    composeRule.runOnIdle {
                        val current = owner.composerStates.value.getValue(key)
                        assertTrue(current.ordinarySaveUnconfirmed)
                    }
                }
                if (heldSend) {
                    composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
                    if (newerEdit) composeRule.onNodeWithTag("message-composer").performTextReplacement("newer correction")
                    composeRule.runOnIdle { fixture.sendRelease.complete(Unit) }
                } else if (send) composeRule.onNodeWithContentDescription("Send").performClick()
                else composeRule.onNodeWithText("Cancel").performClick()
                if (newerEdit) {
                    composeRule.waitUntil { owner.pendingSendIdentities.value.isEmpty() }
                    composeRule.runOnIdle {
                        assertEquals("newer correction", owner.composerStates.value.getValue(key).body)
                        assertNotNull(owner.composerStates.value.getValue(key).correction)
                    }
                    composeRule.onNodeWithText("Cancel").performClick()
                }
                composeRule.waitUntil { owner.composerStates.value.getValue(key).correction == null }
                if (heldSave) {
                    composeRule.runOnIdle { backupSave.completeExceptionally(IllegalStateException("late backup save")) }
                    composeRule.waitUntil { owner.pendingDraftAttempts.value.isEmpty() }
                }
                composeRule.runOnIdle {
                    val ordinary = owner.composerStates.value.getValue(key)
                    assertEquals("stored A", ordinary.body)
                    assertEquals(org.thanosapollo.nema.chat.DraftReply("reply", fixture.peer, "quoted", "A"), ordinary.reply)
                    assertEquals("https://example.org/file", ordinary.attachmentUrl)
                    if (failSave) {
                        assertTrue(ordinary.ordinarySaveUnconfirmed)
                        reject = false
                    }
                }
                if (failSave) {
                    composeRule.onNodeWithText("Draft not saved").assertExists()
                    composeRule.onNodeWithTag("message-composer").performTextReplacement("stored A retry")
                    composeRule.onNodeWithTag("message-composer").performTextReplacement("stored A")
                }
                composeRule.waitUntil { owner.pendingDraftAttempts.value.isEmpty() }
                composeRule.onNodeWithTag("composer-attachment-preview").assertIsDisplayed()
                composeRule.onNodeWithText("file.pdf").assertIsDisplayed()
                if (remove) {
                    composeRule.onNodeWithContentDescription("Remove attachment").performClick()
                    composeRule.waitUntil { owner.pendingDraftAttempts.value.isEmpty() }
                    composeRule.onNodeWithTag("composer-attachment-preview").assertDoesNotExist()
                }
                if (send) { assertTrue(fixture.sendEntered.isCompleted); assertEquals(1, sendAttempts) }
                composeRule.runOnIdle { active.value = null }
            }
            RoutePresentationFixture(database, seed = false).use { fixture ->
                val presenter = fixture.presenter()
                composeRule.runOnIdle { active.value = presenter }
                runBlocking { presenter.selectPeer(fixture.peer) }
                composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
                composeRule.waitForIdle()
                composeRule.runOnIdle {
                    val restored = owner.composerStates.value.getValue(DirectConversationKey(fixture.account, fixture.peer))
                    assertEquals("stored A", restored.body)
                    assertEquals(org.thanosapollo.nema.chat.DraftReply("reply", fixture.peer, "quoted", "A"), restored.reply)
                    assertEquals(if (remove) null else "https://example.org/file", restored.attachmentUrl)
                    assertEquals(if (remove) null else "file.pdf", restored.attachmentName)
                    assertEquals(if (remove) null else "application/pdf", restored.attachmentMime)
                    assertEquals(if (remove) null else 4L, restored.attachmentSize)
                    active.value = null
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    private fun completion(route: String, uploadStarted: Boolean, result: Boolean?) {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            val replacement = fixture.presenter("replacement")
            val active = mutableStateOf(presenter)
            val home = mutableStateOf(true)
            val registry = PickerRegistry()
            val restoration = StateRestorationTester(composeRule)
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val uri = Uri.parse("content://test/file.pdf")
            Shadows.shadowOf(context.contentResolver).registerInputStream(uri, "file".byteInputStream())
            val upload = CompletableDeferred<UploadedFile?>()
            var uploads = 0
            val saves = mutableListOf<DraftSnapshot>()
            val save = CompletableDeferred<Boolean>()
            lateinit var owner: ComposerOwner
            restoration.setContent {
                val current by active.value.state.collectAsState()
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    owner = rememberComposerOwner(current.accountId)
                    MaterialTheme {
                        if (home.value) ConversationContent(
                            state = current, composerOwner = owner, connectionStatus = "Connected",
                            onSelectPeer = active.value::selectPeer, onCloseConversation = active.value::closeConversation,
                            onDraftChange = { saves += it; save }, onSend = active.value::sendDraft,
                            onUploadFile = { _, _, _ -> uploads++; upload.await() },
                        )
                    }
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.waitForIdle()
            val original = owner
            val key = DirectConversationKey(fixture.account, fixture.peer)
            composeRule.onNodeWithContentDescription("Attach file").performClick()
            if (uploadStarted) {
                composeRule.runOnIdle { registry.deliver(uri) }
                composeRule.waitUntil { uploads == 1 }
            }
            composeRule.onNodeWithTag("message-composer").performTextReplacement("new caption")
            composeRule.runOnIdle { saves.clear() }
            when (route) {
                "peer" -> runBlocking { presenter.selectPeer(fixture.other) }
                "second" -> runBlocking { presenter.selectPeer(fixture.other) }
                "thread" -> runBlocking { presenter.continueThread(ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))) }
                "tab" -> composeRule.runOnIdle { home.value = false }
                "recreation" -> restoration.emulateSavedInstanceStateRestore()
                "account" -> {
                    runBlocking { replacement.selectPeer(fixture.peer) }
                    composeRule.runOnIdle { active.value = replacement }
                    // Compose idle does not settle the replacement presenter's Room queries.
                    composeRule.waitUntil {
                        replacement.state.value.contentStatus == ChatContentStatus.Ready &&
                            DirectConversationKey("replacement", fixture.peer) in owner.composerStates.value
                    }
                }
            }
            if (route == "peer" || route == "thread" || route == "second") {
                val destination = if (route != "thread") DirectConversationKey(fixture.account, fixture.other)
                    else key.copy(thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent")))
                composeRule.waitUntil { destination in owner.composerStates.value }
            }
            composeRule.waitForIdle()
            val before = owner.composerStates.value.filterKeys { it != key }
            if (route == "second") {
                composeRule.onNodeWithContentDescription("Attach file").performClick()
                assertEquals(1, registry.requests.size)
            }
            if (!uploadStarted) {
                if (route in listOf("account", "recreation")) {
                    composeRule.onNodeWithContentDescription("Attach file").performClick()
                    assertEquals(2, registry.requests.size)
                    assertNotEquals(registry.requests.first(), registry.requests.last())
                }
                composeRule.runOnIdle { registry.deliver(uri, registry.requests.first()) }
            }
            if (route !in listOf("account", "recreation")) composeRule.waitUntil { uploads == 1 }
            composeRule.runOnIdle { upload.complete(UploadedFile("https://example.org/file", "file.pdf", "application/pdf", 4L)) }
            if (route in listOf("account", "recreation")) {
                composeRule.waitForIdle()
                assertEquals(if (uploadStarted) 1 else 0, uploads)
                assertTrue(saves.isEmpty())
                assertNull(original.composerStates.value.getValue(key).attachmentUrl)
            } else {
                composeRule.waitUntil { saves.isNotEmpty() }
                val snapshot = saves.single()
                assertEquals(key, snapshot.key)
                assertEquals("new caption", snapshot.body)
                assertEquals(org.thanosapollo.nema.chat.DraftReply("reply", fixture.peer, "quoted", "A"), snapshot.reply)
                assertEquals(2L, snapshot.composerRevision)
                assertEquals("https://example.org/file", snapshot.attachmentUrl)
                assertEquals("file.pdf", snapshot.attachmentName)
                assertEquals("application/pdf", snapshot.attachmentMime)
                assertEquals(4L, snapshot.attachmentSize)
                composeRule.runOnIdle {
                    if (result == null) save.completeExceptionally(IllegalStateException("save")) else save.complete(result)
                }
                composeRule.waitUntil { original.pendingDraftAttempts.value.isEmpty() }
                assertEquals(result != true, original.composerStates.value.getValue(key).ordinarySaveUnconfirmed)
            }
            composeRule.runOnIdle { assertEquals(before, owner.composerStates.value.filterKeys { it != key }) }
        }
    }

    private class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        private var request = -1
        val requests = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
            requests += requestCode
        }
        fun deliver(uri: Uri, target: Int = request) { check(target != -1); dispatchResult(target, uri) }
    }
}
