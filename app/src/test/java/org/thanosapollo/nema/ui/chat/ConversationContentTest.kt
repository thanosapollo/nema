package org.thanosapollo.nema.ui.chat

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.chat.ChatContentStatus
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DeliveryPresentation
import org.thanosapollo.nema.chat.DraftCorrection
import org.thanosapollo.nema.chat.DraftReply
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.MessageReplyPresentation
import org.thanosapollo.nema.chat.RecentThread
import org.thanosapollo.nema.chat.ThreadSummary
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.ui.theme.AppearanceSpec
import org.thanosapollo.nema.ui.theme.NemaTheme
import org.thanosapollo.nema.ui.theme.MIN_TEXT_CONTRAST
import org.thanosapollo.nema.ui.theme.PaletteChoice
import org.thanosapollo.nema.ui.theme.ThemeMode
import org.thanosapollo.nema.ui.theme.contrastRatio
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.httpupload.UploadedFile
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

private val hasButtonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
private val hasNoRole = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationContentTest {
    @get:Rule
    val composeRule = createRobolectricComposeRule()

    @Test
    fun presenterLoadingAndFailureHideOldActionsAndPreserveHomeAndDrafts() {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            var profileOpens = 0
            runBlocking { withTimeout(5_000) { presenter.state.first { it.conversations.size == 31 } } }
            composeRule.setContent {
                MaterialTheme {
                    val current by presenter.state.collectAsState()
                    ConversationContent(
                        state = current, connectionStatus = "Connected",
                        onSelectPeer = { peer -> fixture.scope.launch { presenter.selectPeer(peer) }; true },
                        onCloseConversation = presenter::closeConversation,
                        onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft,
                        onOpenOwnProfile = { profileOpens++ },
                    )
                }
            }
            composeRule.onNodeWithTag("home-conversations").performScrollToIndex(15)
            val anchor = composeRule.onNodeWithTag("home-conversations").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(anchor > 0f)
            val listBounds = composeRule.onNodeWithTag("home-conversations").fetchSemanticsNode().boundsInRoot
            val rowPoint = listBounds.center
            val fabPoint = composeRule.onNodeWithContentDescription("New chat").fetchSemanticsNode().boundsInRoot.center
            val searchPoint = composeRule.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot.center
            val profileBounds = composeRule.onNodeWithContentDescription("Own profile").fetchSemanticsNode().boundsInRoot
            // Shell Back covers Own profile; its real touch below must close only the route.
            fun touchCoveredHome() {
                val occurrence = presenter.state.value.routeOccurrence
                composeRule.onRoot().performTouchInput {
                    click(rowPoint)
                    click(fabPoint)
                    click(searchPoint)
                    swipe(rowPoint, rowPoint + androidx.compose.ui.geometry.Offset(0f, -100f))
                }
                composeRule.waitForIdle()
                if (occurrence != presenter.state.value.routeOccurrence) {
                    fixture.gate.release()
                    composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
                }
                assertEquals(occurrence, presenter.state.value.routeOccurrence)
                assertEquals(0, profileOpens)
                composeRule.onNodeWithText("Cancel").assertDoesNotExist()
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready && presenter.state.value.selectedPeer == fixture.peer }
            composeRule.onNodeWithTag("message-composer").assertTextEquals("stored A")
            composeRule.onNodeWithTag("composer-reply-preview").assertIsDisplayed()
            composeRule.onNodeWithTag("message-composer").performTextReplacement("retained edit")
            val entered = fixture.gate.hold()
            fixture.scope.launch { presenter.selectPeer(fixture.other) }
            runBlocking { withTimeout(5_000) { entered.await() } }
            composeRule.waitUntil { presenter.state.value.selectedPeer == fixture.other }
            composeRule.onNodeWithText("Loading conversation").assertIsDisplayed()
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            composeRule.onNodeWithTag("message-bubble-message-0").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Open contact info").assertDoesNotExist()
            touchCoveredHome()
            assertTrue(composeRule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot.contains(profileBounds.center))
            composeRule.onRoot().performTouchInput { click(profileBounds.center) }
            assertEquals(0, profileOpens)
            composeRule.waitUntil { presenter.state.value.selectedPeer == null }
            val restoredAnchor = composeRule.onNodeWithTag("home-conversations").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertEquals(anchor, restoredAnchor)
            composeRule.onNodeWithText("Search conversations").assertDoesNotExist()
            fixture.scope.launch { presenter.selectPeer(fixture.other) }
            composeRule.waitUntil { presenter.state.value.selectedPeer == fixture.other }
            fixture.gate.release()
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithTag("message-composer").assertTextEquals("stored B")
            fixture.failLive.complete(Unit)
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Failed }
            composeRule.onNodeWithText("Unable to load conversation").assertIsDisplayed()
            touchCoveredHome()
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            assertTrue(composeRule.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot.contains(profileBounds.center))
            composeRule.onRoot().performTouchInput { click(profileBounds.center) }
            assertEquals(0, profileOpens)
            composeRule.waitUntil { presenter.state.value.selectedPeer == null }
            assertEquals(anchor, composeRule.onNodeWithTag("home-conversations").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value())
            composeRule.onNodeWithText("Search conversations").assertDoesNotExist()
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithTag("message-composer").assertTextEquals("retained edit")
        }
    }

    @Test
    fun presenterSendCompletionAndAccountReplacementSurviveLoading() {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            val replacement = fixture.presenter("replacement")
            lateinit var replace: () -> Unit
            composeRule.setContent {
                var owner by remember { mutableStateOf(presenter) }
                replace = { owner = replacement }
                MaterialTheme {
                    val current by owner.state.collectAsState()
                    ConversationContent(
                        state = current, connectionStatus = "Connected",
                        onSelectPeer = owner::selectPeer, onCloseConversation = owner::closeConversation,
                        onDraftChange = owner::updateDraft, onSend = owner::sendDraft,
                        onAcknowledgeCompletedSends = owner::acknowledgeCompletedSends,
                    )
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.selectedPeer == fixture.peer && presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithTag("message-composer").assertTextEquals("stored A")
            composeRule.onNodeWithContentDescription("Send").performClick()
            runBlocking { withTimeout(5_000) { fixture.sendEntered.await() } }
            assertTrue(presenter.state.value.pendingSendIdentities.isNotEmpty())
            val entered = fixture.gate.hold()
            fixture.scope.launch { presenter.selectPeer(fixture.other) }
            runBlocking { withTimeout(5_000) { entered.await() } }
            composeRule.waitUntil { presenter.state.value.selectedPeer == fixture.other }
            assertTrue(presenter.state.value.pendingSendIdentities.isNotEmpty())
            fixture.sendRelease.complete(Unit)
            composeRule.waitUntil { presenter.state.value.completedSendSnapshots.isNotEmpty() }
            fixture.scope.launch { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.selectedPeer == fixture.peer }
            composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
            fixture.gate.release()
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.onNodeWithTag("message-composer").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")),
            )
            runBlocking { replacement.selectPeer(fixture.peer) }
            composeRule.runOnIdle { replace() }
            composeRule.waitUntil { replacement.state.value.contentStatus == ChatContentStatus.Ready && replacement.state.value.selectedPeer == fixture.peer }
            composeRule.onNodeWithTag("message-composer").assertTextEquals("other account draft")
        }
    }

    @Test
    fun sentTimeFormatsAtPresentationTimezone() {
        val epoch = Instant.parse("2026-08-15T10:05:00Z").toEpochMilli()

        assertEquals("10:05", formatMessageTime(epoch, ZoneId.of("UTC")))
        assertEquals("13:05", formatMessageTime(epoch, ZoneId.of("Europe/Athens")))
    }

    @Test
    fun viewportRestorePrefersStableMessageIdThenBoundedFallback() {
        val messages = (1..5).map { message("message-$it", outgoing = false) }

        assertEquals(3, restoredTimelineIndex(messages, TimelineViewportAnchor("message-2", 7, 0)))
        assertEquals(4, restoredTimelineIndex(messages, TimelineViewportAnchor("missing", 0, 99)))
    }

    @Test
    fun viewportStoreIsBoundedAndRetainsRecentlyUsedRoute() {
        val store = TimelineViewportStore(capacity = 2)
        val first = DirectConversationKey(ACCOUNT_A, PEER_A)
        val second = DirectConversationKey(ACCOUNT_A, PEER_B)
        val third = DirectConversationKey(
            ACCOUNT_A,
            PEER_A,
            ThreadRef(ThreadId.require("third-thread")),
        )
        val firstAnchor = TimelineViewportAnchor("first", 4, 1)

        store[first] = firstAnchor
        store[second] = TimelineViewportAnchor("second", 5, 2)
        assertEquals(firstAnchor, store[first])
        store[third] = TimelineViewportAnchor("third", 6, 3)

        assertEquals(2, store.size)
        assertEquals(firstAnchor, store[first])
        assertEquals(null, store[second])
    }

    @Test
    fun threadRouteRevealDirectionRequiresSamePeerAndDirectLineage() {
        val main = DirectConversationKey(ACCOUNT_A, PEER_A)
        val parent = main.copy(thread = ThreadRef(ThreadId.require("parent")))
        val child = main.copy(
            thread = ThreadRef(
                id = ThreadId.require("child"),
                parentId = parent.thread?.id,
            ),
        )

        assertEquals(ThreadRouteRevealDirection.FORWARD, threadRouteRevealDirection(main, parent))
        assertEquals(ThreadRouteRevealDirection.FORWARD, threadRouteRevealDirection(parent, child))
        assertEquals(ThreadRouteRevealDirection.BACKWARD, threadRouteRevealDirection(child, parent))
        assertEquals(ThreadRouteRevealDirection.BACKWARD, threadRouteRevealDirection(parent, main))
        assertEquals(ThreadRouteRevealDirection.NONE, threadRouteRevealDirection(parent, parent))
        assertEquals(
            ThreadRouteRevealDirection.NONE,
            threadRouteRevealDirection(parent, main.copy(thread = ThreadRef(ThreadId.require("unrelated")))),
        )
        assertEquals(ThreadRouteRevealDirection.NONE, threadRouteRevealDirection(parent, parent.copy(accountId = ACCOUNT_B)))
        assertEquals(ThreadRouteRevealDirection.NONE, threadRouteRevealDirection(parent, parent.copy(canonicalBarePeer = PEER_B)))
    }

    @Test
    fun threadRouteRevealStartsFromLogicalEdgeWithBoundedScaleAndTiming() {
        val forward = threadRouteRevealStart(ThreadRouteRevealDirection.FORWARD, LayoutDirection.Ltr)
        val backward = threadRouteRevealStart(ThreadRouteRevealDirection.BACKWARD, LayoutDirection.Ltr)

        assertEquals(0.11f, forward.offsetFraction)
        assertEquals(-0.09f, backward.offsetFraction)
        assertEquals(
            -forward.offsetFraction,
            threadRouteRevealStart(ThreadRouteRevealDirection.FORWARD, LayoutDirection.Rtl).offsetFraction,
        )
        assertEquals(
            -backward.offsetFraction,
            threadRouteRevealStart(ThreadRouteRevealDirection.BACKWARD, LayoutDirection.Rtl).offsetFraction,
        )
        assertEquals(0.985f, forward.scale)
        assertEquals(220, forward.durationMillis)
        assertEquals(190, backward.durationMillis)
        assertEquals(
            ThreadRouteRevealStart(0f, 1f, 0),
            threadRouteRevealStart(ThreadRouteRevealDirection.NONE, LayoutDirection.Ltr),
        )
    }

    @Test
    fun signalStyleHeaderOpensAccountQualifiedContactInfo() {
        var nicknameKey: DirectConversationKey? = null
        var nickname: String? = null
        var blockKey: DirectConversationKey? = null
        var blockValue: Boolean? = null
        var sharedPeer: String? = null
        var blockingLoads = 0
        val thread = ThreadRef(ThreadId.require("topic"))
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        selectedThread = thread,
                        selectedPeerDisplayName = "Remote Name",
                        selectedPeerLocalNickname = "Local Friend",
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(1),
                    onSavePeerNickname = { key, value ->
                        nicknameKey = key
                        nickname = value
                        true
                    },
                    onLoadPeerBlocking = { _, key ->
                        blockingLoads++
                        blockKey = key
                        PeerBlockingState(supported = true)
                    },
                    onSetPeerBlocked = { _, key, blocked ->
                        blockKey = key
                        blockValue = blocked
                        PeerBlockingMutationResult.Confirmed(
                            PeerBlockingState(
                                supported = true,
                                blockedAddresses = listOfNotNull(key.canonicalBarePeer.takeIf { blocked }),
                            ),
                        )
                    },
                    onSharePeer = { sharedPeer = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Voice call").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Video call").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.onNodeWithText("Contact info").assertIsDisplayed()
        composeRule.onNodeWithText(PEER_A).assertIsDisplayed()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithText("Remote Name").assertIsDisplayed()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(5)
        composeRule.onNodeWithText("Plaintext").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-row-contact-encryption").assert(
            SemanticsMatcher("has no click action") { !it.config.contains(SemanticsActions.OnClick) },
        ).assert(hasNoRole)
        composeRule.waitUntil { blockKey != null }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), blockKey)
        assertEquals(1, blockingLoads)

        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(3)
        composeRule.onNodeWithTag("settings-row-contact-share")
            .assert(hasButtonRole)
            .performClick()
        assertEquals(PEER_A, sharedPeer)

        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(1)
        composeRule.onNodeWithTag("settings-row-contact-nickname")
            .assert(hasButtonRole)
            .performClick()
        composeRule.onNodeWithTag("nickname-input").performTextReplacement("Chosen")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil { nickname != null }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), nicknameKey)
        assertEquals("Chosen", nickname)

        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(6)
        composeRule.onNodeWithTag("settings-row-contact-block")
            .assert(hasButtonRole)
            .performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.waitUntil { blockValue != null }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), blockKey)
        assertEquals(true, blockValue)
    }

    @Test
    fun contactInfoUsesSharedTaggedShellHeadersAndActionSemantics() {
        val thread = ThreadRef(ThreadId.require("shared-row-thread"))
        var shared = 0
        var closeConversation = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        selectedPeerDisplayName = "Remote Name",
                        recentThreads = listOf(
                            RecentThread(
                                thread = thread,
                                title = "Shared row thread",
                                replyCount = 2,
                                messageKind = MessageKind.CHAT,
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = { closeConversation++ },
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onSharePeer = { shared++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.onNodeWithTag("contact-profile-header").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("contact-profile-header") and
                hasAnyDescendant(hasTestTag("contact-profile-avatar")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
            .assertHasNoClickAction()
            .assert(hasNoRole)
        val list = composeRule.onNodeWithTag("contact-info-list")
        list.performScrollToIndex(1)
        composeRule.onNodeWithTag("settings-row-contact-nickname")
            .assertHeightIsAtLeast(64.dp)
            .assertHasClickAction()
            .assert(hasButtonRole)
        list.performScrollToIndex(2)
        composeRule.onNodeWithTag("settings-row-contact-address")
            .assertHeightIsAtLeast(64.dp)
            .assertHasNoClickAction()
            .assert(hasNoRole)
        list.performScrollToIndex(3)
        composeRule.onNodeWithTag("settings-row-contact-share")
            .assertHeightIsAtLeast(64.dp)
            .assertHasClickAction()
            .assert(hasButtonRole)
            .performClick()
        list.performScrollToIndex(4)
        composeRule.onNodeWithTag("settings-row-contact-remote-profile")
            .assertHeightIsAtLeast(64.dp)
            .assertHasNoClickAction()
            .assert(hasNoRole)
        list.performScrollToIndex(5)
        composeRule.onNodeWithTag("settings-row-contact-encryption")
            .assertHeightIsAtLeast(64.dp)
            .assertHasNoClickAction()
            .assert(hasNoRole)
        list.performScrollToIndex(6)
        composeRule.onNodeWithTag("settings-section-contact-recent-threads")
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assert(hasNoRole)
        list.performScrollToIndex(7)
        composeRule.onNodeWithTag("settings-row-contact-thread-${thread.draftKey()}")
            .assertHeightIsAtLeast(64.dp)
            .assertHasClickAction()
            .assert(hasButtonRole)

        assertEquals(1, shared)
        composeRule.onNodeWithContentDescription("Back to conversation").performClick()
        composeRule.onNodeWithTag("contact-info-list").assertDoesNotExist()
        assertEquals(0, closeConversation)
    }

    @Test
    fun unsupportedBlockingLoadsOnceWithoutExposingMutationAction() {
        var loadCalls = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(1),
                    onLoadPeerBlocking = { _, _ ->
                        loadCalls++
                        PeerBlockingState(supported = false)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil { loadCalls > 0 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-row-contact-block").assertDoesNotExist()
        assertEquals(1, loadCalls)
    }

    @Test
    fun nicknameSaveFailureStaysInDialogAndInvokesOwnerOnce() {
        var saveCalls = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onSavePeerNickname = { _, _ ->
                        saveCalls++
                        false
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(1)
        composeRule.onNodeWithTag("settings-row-contact-nickname").performClick()
        composeRule.onNodeWithTag("nickname-input").performTextReplacement("Not saved")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Nickname was not saved").assertIsDisplayed()
        composeRule.onNodeWithTag("nickname-input").assertIsDisplayed()
        assertEquals(1, saveCalls)
    }

    @Test
    fun profileListsOpensAndRenamesRecentThread() {
        val thread = ThreadRef(
            id = ThreadId.require("recent-child"),
            parentId = ThreadId.require("current-session"),
        )
        var opened: ThreadRef? = null
        var renamed: Pair<ThreadRef, String>? = null
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        recentThreads = listOf(
                            RecentThread(
                                thread = thread,
                                title = "Default thread title",
                                replyCount = 3,
                                messageKind = MessageKind.CHAT,
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onContinueThread = {
                        opened = it
                        true
                    },
                    onRenameThread = { selected, name ->
                        renamed = selected.thread to name
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(5)
        composeRule.onNodeWithText("Recent threads").assertIsDisplayed()
        composeRule.onNodeWithText("Default thread title").assertIsDisplayed()
        composeRule.onNodeWithText("Replies 3").assertIsDisplayed()

        composeRule.onNodeWithTag("rename-thread-${thread.draftKey()}").performClick()
        composeRule.onNodeWithTag("thread-name-input").performTextReplacement("Planning")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil { renamed != null }
        assertEquals(thread to "Planning", renamed)

        composeRule.onNodeWithTag("settings-row-contact-thread-${thread.draftKey()}").performClick()
        composeRule.waitUntil { opened != null }
        assertEquals(thread, opened)
    }

    @Test
    fun domainBlockConfirmationNamesDomainWideUnblock() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(1),
                    onLoadPeerBlocking = { _, _ ->
                        PeerBlockingState(supported = true, blockedAddresses = listOf("example.org"))
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("settings-row-contact-block")
            .assert(hasButtonRole)
            .performClick()

        composeRule.onNodeWithText("Unblock domain?").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Blocking rules for example.org will be removed. " +
                "This may allow messages from other addresses at the same domain.",
        )
            .assertIsDisplayed()
    }

    @Test
    fun blockingCompletionFromOldAccountCannotProjectIntoReplacementProfile() {
        val mutationStarted = CompletableDeferred<Unit>()
        val mutationResult = CompletableDeferred<PeerBlockingMutationResult>()
        lateinit var show: (DirectChatState) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(state(ACCOUNT_A, PEER_A)) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(if (current.accountId == ACCOUNT_A) 1 else 2, current.accountId),
                    onLoadPeerBlocking = { _, _ -> PeerBlockingState(supported = true) },
                    onSetPeerBlocked = { _, _, _ ->
                        mutationStarted.complete(Unit)
                        withContext(NonCancellable) { mutationResult.await() }
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("settings-row-contact-block").performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.waitUntil { mutationStarted.isCompleted }
        composeRule.onNodeWithTag("settings-row-contact-block")
            .assertIsNotEnabled()
            .assertHasNoClickAction()
            .assert(hasButtonRole)

        composeRule.runOnIdle { show(state(ACCOUNT_B, PEER_B)) }
        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("settings-row-contact-block").assertIsDisplayed()

        mutationResult.complete(PeerBlockingMutationResult.Uncertain)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Block outcome unknown. Reopen contact info to refresh.")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("settings-row-contact-block").assertIsDisplayed()
    }

    @Test
    fun sameAccountGenerationReplacementClearsAndReloadsBlockingState() {
        lateinit var reconnect: () -> Unit
        composeRule.setContent {
            MaterialTheme {
                var generation by remember { mutableStateOf(1L) }
                reconnect = { generation = 2L }
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(generation),
                    onLoadPeerBlocking = { identity, _ ->
                        if (identity.generation.value == 1L) {
                            PeerBlockingState(true, listOf(PEER_A))
                        } else {
                            PeerBlockingState(supported = true)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithText("Unblock").assertIsDisplayed()

        composeRule.runOnIdle(reconnect)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Block").assertIsDisplayed()
        composeRule.onNodeWithText("Unblock").assertDoesNotExist()
    }

    @Test
    fun sameAccountGenerationReplacementDropsOldMutationAfterNewMutationWins() {
        val oldMutationStarted = CompletableDeferred<Unit>()
        val oldMutation = CompletableDeferred<PeerBlockingMutationResult>()
        lateinit var reconnect: () -> Unit
        composeRule.setContent {
            MaterialTheme {
                var generation by remember { mutableStateOf(1L) }
                reconnect = { generation = 2L }
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(generation),
                    onLoadPeerBlocking = { _, _ -> PeerBlockingState(supported = true) },
                    onSetPeerBlocked = { identity, key, blocked ->
                        if (identity.generation.value == 1L) {
                            oldMutationStarted.complete(Unit)
                            withContext(NonCancellable) { oldMutation.await() }
                        } else {
                            PeerBlockingMutationResult.Confirmed(
                                PeerBlockingState(
                                    supported = true,
                                    blockedAddresses = listOfNotNull(
                                        key.canonicalBarePeer.takeIf { blocked },
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("settings-row-contact-block").performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.waitUntil { oldMutationStarted.isCompleted }

        composeRule.runOnIdle(reconnect)
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("settings-row-contact-block").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("settings-row-contact-block").performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.onNodeWithText("Unblock").assertIsDisplayed()

        oldMutation.complete(PeerBlockingMutationResult.Uncertain)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Unblock").assertIsDisplayed()
        composeRule.onNodeWithText("Block outcome unknown. Reopen contact info to refresh.")
            .assertDoesNotExist()
    }

    @Test
    fun conversationOverflowOpensContactInfoBeforeThreadActions() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Conversation actions").performClick()
        composeRule.onNodeWithText("Contact info").performClick()
        composeRule.onNodeWithText("XMPP address").assertIsDisplayed()
    }

    @Test
    fun roomInfoUsesRoomLabelsAndShowsCommonAndRoomFactsOnly() {
        val room = "room@conference.example.org"
        val thread = ThreadRef(ThreadId.require("room-topic"))
        var sharedPeer: String? = null
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, room).copy(
                        selectedPeerDisplayName = "Remote profile must stay hidden",
                        selectedPeerLocalNickname = "Local nickname must stay hidden",
                        selectedPeerGroupChat = true,
                        selectedRoomSubject = "Open hardware",
                        selectedRoomOccupantCount = 3,
                        recentThreads = listOf(
                            RecentThread(
                                thread = thread,
                                title = "Room topic",
                                replyCount = 2,
                                messageKind = MessageKind.GROUPCHAT,
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onSharePeer = { sharedPeer = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open room info").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Conversation actions").performClick()
        composeRule.onNodeWithText("Contact info").assertDoesNotExist()
        composeRule.onNodeWithText("Room info").performClick()
        composeRule.onNodeWithText("Room info").assertIsDisplayed()
        val list = composeRule.onNodeWithTag("room-info-list")
        list.performScrollToIndex(1)
        composeRule.onNode(
            hasTestTag("settings-row-room-address") and
                hasAnyDescendant(hasText("XMPP address")) and
                hasAnyDescendant(hasText(room)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        list.performScrollToIndex(2)
        composeRule.onNodeWithTag("settings-row-room-share").performClick()
        assertEquals(room, sharedPeer)
        list.performScrollToIndex(3)
        composeRule.onNode(
            hasTestTag("settings-row-room-subject") and
                hasAnyDescendant(hasText("Subject")) and
                hasAnyDescendant(hasText("Open hardware")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        list.performScrollToIndex(4)
        composeRule.onNode(
            hasTestTag("settings-row-room-occupants") and
                hasAnyDescendant(hasText("Occupants")) and
                hasAnyDescendant(hasText("3 occupants")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        list.performScrollToIndex(5)
        composeRule.onNode(
            hasTestTag("settings-row-room-encryption") and
                hasAnyDescendant(hasText("Encryption")) and
                hasAnyDescendant(hasText("Plaintext")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        list.performScrollToIndex(6)
        composeRule.onNodeWithTag("settings-section-room-recent-threads").assertIsDisplayed()
        list.performScrollToIndex(7)
        composeRule.onNodeWithTag("settings-row-room-thread-${thread.draftKey()}").assertIsDisplayed()
        composeRule.onNodeWithText("Nickname").assertDoesNotExist()
        composeRule.onNodeWithText("Remote profile").assertDoesNotExist()
        composeRule.onNodeWithText("Block").assertDoesNotExist()
        composeRule.onNodeWithText("Unblock").assertDoesNotExist()
    }

    @Test
    fun roomInfoNeverInvokesContactEffectsAcrossSubjectAndOccupantUpdates() {
        val room = "room@conference.example.org"
        val initial = state(ACCOUNT_A, room).copy(
            selectedPeerGroupChat = true,
            selectedRoomSubject = "First subject",
            selectedRoomOccupantCount = 2,
        )
        lateinit var show: (DirectChatState) -> Unit
        var blockingLoads = 0
        var blockingMutations = 0
        var nicknameSaves = 0
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(initial) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    blockingSession = session(1),
                    onSavePeerNickname = { _, _ ->
                        nicknameSaves++
                        true
                    },
                    onLoadPeerBlocking = { _, _ ->
                        blockingLoads++
                        PeerBlockingState(supported = true)
                    },
                    onSetPeerBlocked = { _, _, _ ->
                        blockingMutations++
                        PeerBlockingMutationResult.NotAttempted
                    },
                )
            }
        }

        composeRule.onNode(
            hasContentDescription("Open room info") or hasContentDescription("Open contact info"),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(0, blockingLoads)
            assertEquals(0, blockingMutations)
            assertEquals(0, nicknameSaves)
            show(
                initial.copy(
                    selectedRoomSubject = "Updated subject",
                    selectedRoomOccupantCount = 7,
                ),
            )
        }
        composeRule.onNodeWithText("Updated subject").assertIsDisplayed()
        composeRule.onNodeWithText("7 occupants").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, blockingLoads)
            assertEquals(0, blockingMutations)
            assertEquals(0, nicknameSaves)
        }
    }

    @Test
    fun roomInfoShowsStableMissingSubjectAndZeroOccupants() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, "empty@conference.example.org").copy(
                        selectedPeerGroupChat = true,
                        selectedRoomSubject = null,
                        selectedRoomOccupantCount = 0,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open room info").performClick()
        val list = composeRule.onNodeWithTag("room-info-list")
        list.performScrollToIndex(2)
        composeRule.onNode(
            hasTestTag("settings-row-room-subject") and
                hasAnyDescendant(hasText("Subject")) and
                hasAnyDescendant(hasText("Not set")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        list.performScrollToIndex(3)
        composeRule.onNode(
            hasTestTag("settings-row-room-occupants") and
                hasAnyDescendant(hasText("Occupants")) and
                hasAnyDescendant(hasText("0 occupants")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun replySummaryUsesReadableTextColorWithoutUncertainRetryNoise() {
        val appearance = AppearanceSpec(
            themeMode = ThemeMode.LIGHT,
            palette = PaletteChoice.Custom.require("#767676", "#FFFFFF"),
        )
        lateinit var containers: Pair<Int, Int>
        composeRule.setContent {
            NemaTheme(appearance) {
                val colors = MaterialTheme.colorScheme
                SideEffect {
                    containers = colors.primaryContainer.toArgb() to colors.surfaceVariant.toArgb()
                }
                MessageTimeline(
                    messages = listOf(
                        TimelineMessage(
                            id = "outgoing-retry",
                            senderJid = ACCOUNT_A,
                            body = "outgoing",
                            outgoing = true,
                            delivery = null,
                            retryUncertainKey = RetryUncertainKey(ACCOUNT_A, "operation", 1, 1),
                            thread = null,
                        ),
                        TimelineMessage(
                            id = "incoming-thread",
                            senderJid = PEER_A,
                            body = "incoming",
                            outgoing = false,
                            delivery = null,
                            retryUncertainKey = null,
                            thread = null,
                            threadSummaries = listOf(
                                ThreadSummary(
                                    thread = ThreadRef(ThreadId.require("thread")),
                                    replyCount = 1,
                                    latestMessageId = "incoming-thread",
                                    latestPreview = "incoming",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        val incoming = contrastRatio(renderedTextColor("Thread · 1 replies"), containers.second)

        composeRule.onNodeWithText("Retry — duplicate possible").assertDoesNotExist()
        composeRule.onNodeWithText("May have sent").assertDoesNotExist()
        assertTrue("Bubble action contrast: incoming=$incoming", incoming >= MIN_TEXT_CONTRAST)
    }

    @Test
    fun systemBackUnwindsThreadThenConversation() {
        val owner = TestNavigationEventOwner()
        lateinit var show: (DirectChatState) -> Unit
        var closeThread = 0
        var closeConversation = 0
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides owner,
            ) {
                var current by remember {
                    mutableStateOf(
                        state(ACCOUNT_A, PEER_A).copy(
                            selectedThread = ThreadRef(ThreadId.require("topic")),
                        ),
                    )
                }
                show = { current = it }
                MaterialTheme {
                    ConversationContent(
                        state = current,
                        connectionStatus = "Connected",
                        onSelectPeer = { true },
                        onCloseConversation = {
                            closeConversation++
                            current = current.copy(selectedPeer = null)
                        },
                        onDraftChange = { CompletableDeferred(true) },
                        onSend = { CompletableDeferred(true) },
                        onCloseThread = { closeThread++ },
                    )
                }
            }
        }

        composeRule.runOnIdle { owner.completeBack() }
        assertEquals(1, closeThread)
        assertEquals(0, closeConversation)

        composeRule.runOnIdle { show(state(ACCOUNT_A, PEER_A)) }
        composeRule.runOnIdle { owner.completeBack() }
        assertEquals(1, closeThread)
        assertEquals(1, closeConversation)

        composeRule.runOnIdle { show(state(ACCOUNT_A, PEER_A).copy(selectedPeer = null)) }
        composeRule.waitForIdle()
        assertEquals(1, closeConversation)
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun rootAppBarBackClosesAuthoritativeConversationExactlyOnce() {
        var closeConversation = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = { closeConversation++ },
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.runOnIdle { assertEquals(1, closeConversation) }
    }

    @Test
    fun incomingMessagesUseSiteDarkBubblesWithoutChangingOutgoingPalette() {
        lateinit var observed: List<Int>
        composeRule.setContent {
            NemaTheme(AppearanceSpec(themeMode = ThemeMode.DARK)) {
                val incoming = messageBubbleColors(outgoing = false)
                val outgoing = messageBubbleColors(outgoing = true)
                SideEffect {
                    observed = listOf(
                        incoming.first.toArgb(),
                        incoming.second.toArgb(),
                        outgoing.first.toArgb(),
                        outgoing.second.toArgb(),
                    )
                }
                MessageTimeline(
                    messages = listOf(
                        message("material-incoming", outgoing = false),
                        message("material-outgoing", outgoing = true),
                    ),
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    0xFF0A0A0A.toInt(),
                    0xFFD4C5A0.toInt(),
                    0xFF201A14.toInt(),
                    0xFFD4C5A0.toInt(),
                ),
                observed,
            )
        }
        assertEquals(0xFFD4C5A0.toInt(), renderedTextColor("material-incoming"))
        assertEquals(0xFFD4C5A0.toInt(), renderedTextColor("material-outgoing"))
    }

    @Test
    fun longRoomSubjectCannotExpandConversationTopBar() {
        val subject = "NOTICE: your client says no one is here but there are over 1000 here, " +
            "please ignore that and continue discussing open hardware\n\nCode of Conduct:\n1. Stay on topic"
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, "room@conference.example.org").copy(
                        selectedPeerGroupChat = true,
                        selectedRoomSubject = subject,
                        selectedRoomOccupantCount = 1000,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("conversation-top-bar").assertHeightIsEqualTo(64.dp)
        composeRule.onNodeWithText(subject).assertIsDisplayed()
    }

    @Test
    fun roomJoinIdentityIgnoresSubjectAndOccupantUpdates() {
        RoutePresentationFixture().use { fixture ->
            runBlocking { fixture.repository.markRoom(fixture.account, fixture.peer) }
            fixture.room.value = org.thanosapollo.nema.xmpp.muc.RoomView(fixture.peer, "First subject")
            val presenter = fixture.presenter()
            var navigationJoins = 0
            composeRule.setContent {
                MaterialTheme {
                    val current by presenter.state.collectAsState()
                    ConversationContent(
                        state = current, connectionStatus = "Connected",
                        onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                        onJoinRoom = { navigationJoins++; presenter.joinRoom(it) },
                        onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft,
                    )
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { fixture.joins.get() == 1 && presenter.state.value.selectedRoomSubject == "First subject" }
            val occurrence = presenter.state.value.routeOccurrence
            fixture.room.value = org.thanosapollo.nema.xmpp.muc.RoomView(
                fixture.peer, "Updated subject", occupants = List(7) { org.thanosapollo.nema.xmpp.muc.RoomOccupant("nick-$it") },
            )
            composeRule.waitUntil { presenter.state.value.selectedRoomOccupantCount == 7 }
            composeRule.onNodeWithText("Updated subject").assertIsDisplayed()
            assertEquals(occurrence, presenter.state.value.routeOccurrence)
            val thread = ThreadRef(ThreadId.require("room-topic"), ThreadId.require("parent"))
            runBlocking { presenter.continueThread(thread) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready && presenter.state.value.selectedThread == thread }
            composeRule.waitForIdle()
            assertEquals(thread, presenter.state.value.selectedThread)
            assertEquals(1, fixture.joins.get())
            assertEquals(0, navigationJoins)
        }
    }

    @Test
    fun semanticRepliesAndManualQuotesHaveDistinctVisuals() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("semantic reply", outgoing = false).copy(
                            reply = MessageReplyPresentation(
                                senderLabel = "alice",
                                body = "original message",
                            ),
                        ),
                        message("> manually quoted\nplain answer", outgoing = false),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("message-reply-preview", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reply to alice", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("original message", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("message-quote-block", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("manually quoted", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("plain answer", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun attachmentRowShowsNameAndIsClickable() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("file body", outgoing = false).copy(
                            attachmentUrl = "https://example.org/abc",
                            attachmentName = "notes.txt",
                            attachmentSize = 42,
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("message-attachment", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Download notes.txt", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("message-attachment", useUnmergedTree = true).assertHasClickAction()
    }

    @Test
    fun attachmentDownloadBecomesOpenAfterSuccessfulUse() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("file body", outgoing = false).copy(
                            attachmentUrl = "https://example.org/abc",
                            attachmentName = "notes.txt",
                            attachmentSize = 42,
                        ),
                    ),
                    onUseAttachment = { _, _, _ -> true },
                )
            }
        }

        composeRule.onNodeWithText("Download notes.txt", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open notes.txt", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun cachedAttachmentShowsOpenWithoutDownload() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val hit = java.util.concurrent.atomic.AtomicBoolean(false)
        val onMain = java.util.concurrent.atomic.AtomicBoolean(true)
        val uses = java.util.concurrent.atomic.AtomicInteger()
        val url = "https://example.org/abc"
        try {
            composeRule.setContent {
                MaterialTheme {
                    MessageTimeline(
                        messages = listOf(
                            message("file body", outgoing = false).copy(
                                attachmentUrl = url,
                                attachmentName = "notes.txt",
                            ),
                        ),
                        isAttachmentCached = {
                            onMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                            entered.countDown()
                            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            (it == url).also(hit::set)
                        },
                        onUseAttachment = { _, _, _ -> uses.incrementAndGet(); true },
                    )
                }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(onMain.get())
            composeRule.onNodeWithText("Download notes.txt", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onAllNodesWithText("Open notes.txt", useUnmergedTree = true).assertCountEquals(0)
            release.countDown()
            // A returned cache hit alone does not prove publication on the Compose thread.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("Open notes.txt", useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
            }
            assertTrue(hit.get())
            composeRule.onNodeWithText("Open notes.txt", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onAllNodesWithText("Download notes.txt", useUnmergedTree = true).assertCountEquals(0)
            assertEquals(0, uses.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun attachmentDownloadFailureKeepsDownloadLabel() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("file body", outgoing = false).copy(
                            attachmentUrl = "https://example.org/abc",
                            attachmentName = "notes.txt",
                        ),
                    ),
                    onUseAttachment = { _, _, _ -> error("timeout") },
                )
            }
        }

        composeRule.onNodeWithText("Download notes.txt", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Download notes.txt", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun directChatImageRendersInline() {
        val preview = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("photo", outgoing = false).copy(
                            attachmentUrl = "https://example.org/pic.png",
                            attachmentName = "pic.png",
                            attachmentMime = "image/png",
                        ),
                    ),
                    onLoadInlineImage = { preview },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-inline-image", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("message-attachment", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun inlineImageHidesOobUrlAndKeepsCaption() {
        val preview = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("https://example.org/pic.png", outgoing = false).copy(
                            body = "https://example.org/pic.png",
                            attachmentUrl = "https://example.org/pic.png",
                            attachmentName = "pic.png",
                            attachmentMime = "image/png",
                        ),
                    ),
                    onLoadInlineImage = { preview },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-inline-image", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("https://example.org/pic.png").assertDoesNotExist()
    }

    @Test
    fun inlineImageKeepsRealCaption() {
        val preview = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("look at this", outgoing = false).copy(
                            body = "look at this",
                            attachmentUrl = "https://example.org/pic.png",
                            attachmentName = "pic.png",
                            attachmentMime = "image/png",
                        ),
                    ),
                    onLoadInlineImage = { preview },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-inline-image", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("look at this").assertIsDisplayed()
    }

    @Test
    fun roomImageKeepsDownloadButton() {
        val preview = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("photo", outgoing = false).copy(
                            attachmentUrl = "https://example.org/pic.png",
                            attachmentName = "pic.png",
                            attachmentMime = "image/png",
                            groupChat = true,
                        ),
                    ),
                    venue = ConversationVenue.Room(null, 0),
                    onLoadInlineImage = { preview },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-inline-image", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Download pic.png", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun mixedDirectRowInRoomKeepsTimelinePolicyAndRowEvidenceDistinct() {
        val preview = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        val thread = ThreadRef(ThreadId.require("mixed-thread"))
        val mixedDirect = message("mixed direct", outgoing = false).copy(
            attachmentUrl = "https://example.org/mixed.png",
            attachmentName = "mixed.png",
            attachmentMime = "image/png",
            replyReferenceId = "mixed-wire-id",
            markable = true,
            markerTargetId = "mixed-marker-id",
            threadSummaries = listOf(
                ThreadSummary(thread, 1, "mixed direct", "mixed direct"),
            ),
            reactions = listOf(
                org.thanosapollo.nema.xmpp.reactions.ReactionDisplay(
                    "mixed direct",
                    "👍",
                    1,
                    false,
                    listOf(PEER_A),
                ),
            ),
        )
        val editableDirect = message("mixed editable", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "mixed-edit-wire-id",
        )
        var displayed = 0
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(mixedDirect),
                    venue = ConversationVenue.Room("Room", 3),
                    readReceiptsEnabled = true,
                    activityResumed = true,
                    onMessageDisplayed = {
                        displayed++
                        true
                    },
                    onLoadInlineImage = { preview },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            emptyList<TimelineMessage>(),
            displayedMarkerCandidates(
                messages = listOf(mixedDirect),
                visibleMessageIds = setOf(mixedDirect.id),
                enabled = true,
                resumed = true,
                venue = ConversationVenue.Room("Room", 3),
            ),
        )
        assertNull(editableDirect.correctionTargetOrNull(ConversationVenue.Room("Room", 3)))
        assertFalse(canReact(ConversationVenue.Room("Room", 3), mixedDirect))
        assertEquals(0, displayed)
        composeRule.onNodeWithTag("message-inline-image", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Download mixed.png", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("thread-summary-${thread.draftKey()}").assertDoesNotExist()
        composeRule.onNodeWithTag("reaction-chip-mixed direct-👍").assertIsNotEnabled()
        composeRule.onNodeWithTag("message-bubble-mixed direct")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Reply as a thread").assertDoesNotExist()
        composeRule.onNodeWithText("Reactions").assertDoesNotExist()
    }

    @Test
    fun quoteActionPlacesQuoteBeforeDraftAndLeavesAnswerOutsideQuote() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "my answer").copy(
                        messages = listOf(message("original", outgoing = false)),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("original").performTouchInput { longClick() }
        composeRule.onNodeWithText("Quote").performClick()

        composeRule.onNodeWithTag("message-composer").assertTextEquals(
            "> peer-a wrote:\n> original\n\nmy answer",
        )
    }

    @Test
    fun openingTimelineShowsLatestMessage() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = (1..100).map { number -> message("message-$number", outgoing = false) },
                )
            }
        }

        composeRule.onNodeWithText("message-100").assertIsDisplayed()
        composeRule.onNodeWithText("message-1").assertDoesNotExist()
    }

    @Test
    fun equivalentTimelineReplacementDoesNotStructurallyCompareMessages() {
        lateinit var recompose: () -> Unit
        val snapshots = List(2) {
            object : AbstractList<TimelineMessage>() {
                private val values = (1..100).map { number -> message("message-$number", outgoing = false) }

                override val size: Int = values.size

                override fun get(index: Int): TimelineMessage = values[index]

                override fun equals(other: Any?): Boolean = error("Timeline list was structurally compared")
            }
        }

        composeRule.setContent {
            MaterialTheme {
                var revision by remember { mutableStateOf(0) }
                recompose = { revision++ }
                MessageTimeline(
                    messages = snapshots[revision],
                    modifier = Modifier.testTag("timeline-$revision"),
                )
            }
        }

        composeRule.runOnIdle(recompose)
        composeRule.onNodeWithTag("timeline-1").assertIsDisplayed()
    }

    @Test
    fun displayedMarkersRequireOptInForegroundVisibleDirectInboundEvidence() {
        val eligible = message("eligible", outgoing = false).copy(
            markable = true,
            markerTargetId = "wire-eligible",
        )
        val outgoing = message("outgoing", outgoing = true).copy(
            markable = true,
            markerTargetId = "wire-outgoing",
        )
        val room = message("room", outgoing = false).copy(
            groupChat = true,
            markable = true,
            markerTargetId = "wire-room",
        )
        val messages = listOf(eligible, outgoing, room)

        assertEquals(
            listOf(eligible),
            displayedMarkerCandidates(
                messages = messages,
                visibleMessageIds = messages.map(TimelineMessage::id).toSet(),
                enabled = true,
                resumed = true,
                venue = ConversationVenue.Direct,
            ),
        )
        assertTrue(
            displayedMarkerCandidates(
                messages,
                setOf("eligible"),
                enabled = false,
                resumed = true,
                venue = ConversationVenue.Direct,
            ).isEmpty(),
        )
        assertTrue(
            displayedMarkerCandidates(
                messages,
                setOf("eligible"),
                enabled = true,
                resumed = false,
                venue = ConversationVenue.Direct,
            ).isEmpty(),
        )
        assertTrue(
            displayedMarkerCandidates(
                messages,
                setOf("eligible"),
                enabled = true,
                resumed = true,
                venue = ConversationVenue.Room(null, 0),
            ).isEmpty(),
        )
    }

    @Test
    fun outgoingMessagesExposeHonestSentDeliveredAndReadLabels() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("sent", outgoing = true).copy(delivery = DeliveryPresentation.SENT),
                        message("confirmed", outgoing = true).copy(delivery = DeliveryPresentation.CONFIRMED),
                        message("delivered", outgoing = true).copy(delivery = DeliveryPresentation.DELIVERED),
                        message("read", outgoing = true).copy(delivery = DeliveryPresentation.READ),
                    ),
                )
            }
        }

        composeRule.onAllNodesWithText("Sent").assertCountEquals(2)
        composeRule.onNodeWithText("Delivered").assertDoesNotExist()
        composeRule.onNodeWithText("Read").assertDoesNotExist()
        composeRule.onNode(
            hasTestTag("message-bubble-delivered") and hasAnyDescendant(hasContentDescription("Delivered")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("message-bubble-read") and hasAnyDescendant(hasContentDescription("Read")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun typingIndicatorShowsInTimelineNotTopBar() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(message("live", outgoing = false)),
                        typingLabel = "Talos is typing...",
                    ),
                    connectionStatus = "Reconnecting",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNode(
            hasTestTag("message-timeline") and hasAnyDescendant(hasTestTag("typing-indicator")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Talos is typing...").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("conversation-top-bar") and hasAnyDescendant(
                androidx.compose.ui.test.hasText("Reconnecting"),
            ),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("conversation-top-bar") and hasAnyDescendant(hasTestTag("typing-indicator")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun typingIndexSkipsTheEphemeralRow() {
        assertEquals(0, timelineMessageIndex(0, typingPresent = true, messageCount = 5))
        assertEquals(0, timelineMessageIndex(1, typingPresent = true, messageCount = 5))
        assertEquals(1, timelineMessageIndex(2, typingPresent = true, messageCount = 5))
        assertEquals(0, timelineMessageIndex(0, typingPresent = false, messageCount = 5))
        assertEquals(0, timelineListIndex(0, typingPresent = true))
        assertEquals(2, timelineListIndex(1, typingPresent = true))
        assertEquals(1, timelineListIndex(1, typingPresent = false))
    }

    @Test
    fun typingAppearsAtNewestEdgeWithoutJump() {
        lateinit var show: (String?) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var typing by remember { mutableStateOf<String?>(null) }
                show = { typing = it }
                MessageTimeline(
                    messages = (1..20).map { number -> message("message-$number", outgoing = false) },
                    typingLabel = typing,
                )
            }
        }
        composeRule.onNodeWithText("message-20").assertIsDisplayed()
        composeRule.runOnIdle { show("Talos is typing...") }
        composeRule.waitForIdle()

        composeRule.onNode(
            hasTestTag("message-timeline") and hasAnyDescendant(hasTestTag("typing-indicator")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Talos is typing...").assertIsDisplayed()
        composeRule.onNodeWithText("+1").assertDoesNotExist()
    }

    @Test
    fun typingDoesNotYankHistoryReader() {
        lateinit var show: (String?) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var typing by remember { mutableStateOf<String?>(null) }
                show = { typing = it }
                MessageTimeline(
                    messages = (1..20).map { number -> message("message-$number", outgoing = false) },
                    typingLabel = typing,
                )
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(5)
        composeRule.runOnIdle { show("Talos is typing...") }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Talos is typing...").assertDoesNotExist()
    }

    @Test
    fun typingViewportKeepsNewestMessageAnchor() {
        val anchors = mutableListOf<TimelineViewportAnchor>()
        lateinit var show: (String?) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var typing by remember { mutableStateOf<String?>(null) }
                show = { typing = it }
                MessageTimeline(
                    messages = listOf(
                        message("old", outgoing = false),
                        message("newest", outgoing = false),
                    ),
                    typingLabel = typing,
                    onViewportChanged = { anchors.add(it) },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { show("Talos is typing...") }
        composeRule.waitForIdle()

        assertEquals("newest", anchors.last().messageId)
        assertEquals(0, anchors.last().fallbackIndex)
    }

    @Test
    fun typingViewportKeepsDetachedMessageAnchor() {
        val anchors = mutableListOf<TimelineViewportAnchor>()
        lateinit var show: (String?) -> Unit
        val messages = (1..20).map { number -> message("message-$number", outgoing = false) }
        composeRule.setContent {
            MaterialTheme {
                var typing by remember { mutableStateOf<String?>(null) }
                show = { typing = it }
                MessageTimeline(
                    messages = messages,
                    typingLabel = typing,
                    onViewportChanged = { anchors.add(it) },
                )
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(5)
        composeRule.waitForIdle()
        val detached = anchors.last()
        composeRule.runOnIdle { show("Talos is typing...") }
        composeRule.waitForIdle()
        assertEquals(detached.messageId, anchors.last().messageId)
        assertEquals(detached.fallbackIndex, anchors.last().fallbackIndex)
        composeRule.runOnIdle { show(null) }
        composeRule.waitForIdle()
        assertEquals(detached.messageId, anchors.last().messageId)
        assertEquals(detached.fallbackIndex, anchors.last().fallbackIndex)
    }

    @Test
    fun outgoingReceiptCheckSitsOnTheSameRowAsTime() {
        val sentAt = 1_704_067_500_000L
        val time = formatMessageTime(sentAt)
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("read", outgoing = true).copy(
                            delivery = DeliveryPresentation.READ,
                            sentAtEpochMs = sentAt,
                        ),
                    ),
                )
            }
        }

        composeRule.onNode(
            hasTestTag("message-status") and
                hasAnyDescendant(hasContentDescription("Read")) and
                hasAnyDescendant(androidx.compose.ui.test.hasText(time)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("message-bubble-read") and hasAnyDescendant(hasTestTag("message-status")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun correctedMessageExposesAccessibleEditedIndicator() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(message("edited", outgoing = false).copy(edited = true)),
                )
            }
        }

        composeRule.onNodeWithText("Edited").assertIsDisplayed()
    }

    @Test
    fun correctionActionRequiresOwnSentDirectTextWithExactWireTarget() {
        val eligible = message("editable", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "editable-wire-id",
        )

        assertNotNull(eligible.correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(outgoing = false).correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(delivery = DeliveryPresentation.QUEUED).correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(correctionReferenceId = null).correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(groupChat = true).correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(attachmentUrl = "https://example.org/file").correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(attachmentName = "file").correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(attachmentMime = "text/plain").correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(attachmentSize = 1).correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(replyToId = "reply-target").correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(replyToJid = PEER_A).correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(replyFallbackBody = "quoted").correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.copy(body = " ").correctionTargetOrNull(ConversationVenue.Direct))
        assertNull(eligible.correctionTargetOrNull(ConversationVenue.Room(null, 0)))
    }

    @Test
    fun editActionSendsExactCorrectionAndRestoresUnrelatedDraft() {
        var sent: DraftSnapshot? = null
        val editable = message("original text", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "original-wire-id",
        )
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(editable),
                        draft = "unrelated draft",
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = {
                        sent = it
                        CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithText("original text").performTouchInput { longClick() }
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Editing message").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").performTextReplacement("corrected text")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sent != null }

        assertEquals(DraftCorrection("original text", "original-wire-id", "original text"), sent?.correction)
        assertEquals("corrected text", sent?.body)
        composeRule.onNodeWithTag("message-composer").assertTextEquals("unrelated draft")
        composeRule.onNodeWithText("Editing message").assertDoesNotExist()
    }

    @Test
    fun composerSaverPreservesExactCorrectionAndCompleteBackup() {
        val key = DirectConversationKey(ACCOUNT_A, PEER_A)
        val correction = DraftCorrection("local-target", "wire-target", "original body")
        val reply = DraftReply("reply-id", PEER_A, "reply body", "Peer A")
        val backup = ComposerBackup(
            body = "ordinary draft",
            attachmentUrl = "https://example.org/attachment",
            attachmentName = "attachment.txt",
            attachmentMime = "text/plain",
            attachmentSize = 42,
            reply = reply,
        )
        val original = ComposerState(
            key = key,
            body = "corrected body",
            revision = 7,
            correction = correction,
            correctionBackup = backup,
        )
        val saver = composerStateSaver(key)
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(saver) { with(scope) { save(mutableStateOf(original)) } }
        val restored = saver.restore(checkNotNull(saved))?.value

        assertEquals(original, restored)
        assertEquals(correction, restored?.toDraftSnapshot(ConversationVenue.Direct)?.correction)
        assertEquals(backup, restored?.correctionBackup)
    }

    @Test
    fun editModeSurvivesStateRestorationAndCancelRestoresDraft() {
        val home = mutableStateOf(true)
        val restoration = StateRestorationTester(composeRule)
        val sent = mutableListOf<DraftSnapshot>()
        val savedDrafts = mutableListOf<DraftSnapshot>()
        val backupReply = DraftReply("backup-reply-id", PEER_A, "backup reply body", "Peer A")
        val editable = message("original text", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "original-wire-id",
        )
        restoration.setContent {
            val composerOwner = rememberComposerOwner(ACCOUNT_A)
            MaterialTheme {
                if (home.value) ConversationContent(
                    composerOwner = composerOwner,
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(editable),
                        draft = "unrelated draft",
                        draftReply = backupReply,
                        draftAttachmentUrl = "https://example.org/backup",
                        draftAttachmentName = "backup.txt",
                        draftAttachmentMime = "text/plain",
                        draftAttachmentSize = 7L,
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = {
                        savedDrafts += it
                        CompletableDeferred(true)
                    },
                    onSend = {
                        sent += it
                        CompletableDeferred(false)
                    },
                )
            }
        }

        composeRule.onNodeWithText("original text").performTouchInput { longClick() }
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithTag("message-composer").performTextReplacement("corrected text")
        composeRule.runOnIdle { home.value = false }
        composeRule.onNodeWithTag("message-composer").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { home.value = true }

        composeRule.onNodeWithText("Editing message").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").assertTextEquals("corrected text")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sent.isNotEmpty() }
        assertEquals("corrected text", sent.single().body)
        assertEquals(DraftCorrection("original text", "original-wire-id", "original text"), sent.single().correction)
        assertNull(sent.single().attachmentUrl)
        assertNull(sent.single().reply)
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Editing message").assertDoesNotExist()
        composeRule.onNodeWithTag("composer-reply-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").assertTextEquals("unrelated draft")
        assertTrue(savedDrafts.isEmpty())
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sent.size == 2 }
        val restored = sent.last()
        assertEquals("unrelated draft", restored.body)
        assertEquals(backupReply, restored.reply)
        assertEquals("https://example.org/backup", restored.attachmentUrl)
        assertEquals("backup.txt", restored.attachmentName)
        assertEquals("text/plain", restored.attachmentMime)
        assertEquals(7L, restored.attachmentSize)
        assertNull(restored.correction)
    }

    @Test
    fun replyActionLeavesEditModeAndUsesRestoredDraft() {
        val savedDrafts = mutableListOf<DraftSnapshot>()
        val sent = mutableListOf<DraftSnapshot>()
        val editable = message("original text", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "original-wire-id",
        )
        val replyTarget = message("reply target", outgoing = false).copy(
            replyReferenceId = "reply-wire-id",
        )
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(editable, replyTarget),
                        draft = "unrelated draft",
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = {
                        savedDrafts += it
                        CompletableDeferred(true)
                    },
                    onSend = {
                        sent += it
                        CompletableDeferred(false)
                    },
                )
            }
        }

        composeRule.onNodeWithText("original text").performTouchInput { longClick() }
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithTag("message-composer").performTextReplacement("discarded correction")
        composeRule.onNodeWithText("reply target").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply").performClick()

        composeRule.onNodeWithText("Editing message").assertDoesNotExist()
        composeRule.onNodeWithTag("composer-reply-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").assertTextEquals("unrelated draft")
        composeRule.waitUntil { savedDrafts.size == 1 }
        val expectedReply = DraftReply("reply-wire-id", PEER_A, "reply target", "peer-a")
        assertEquals("unrelated draft", savedDrafts.single().body)
        assertEquals(expectedReply, savedDrafts.single().reply)
        assertNull(savedDrafts.single().correction)
        assertNull(savedDrafts.single().attachmentUrl)
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sent.size == 1 }
        assertEquals("unrelated draft", sent.single().body)
        assertEquals(expectedReply, sent.single().reply)
        assertNull(sent.single().correction)
        assertNull(sent.single().attachmentUrl)
        assertEquals(1, savedDrafts.size)
    }

    @Test
    fun editActionIsUnavailableWhileOrdinarySendIsPending() {
        val sendResult = CompletableDeferred<Boolean>()
        val editable = message("original text", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "original-wire-id",
        )
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(messages = listOf(editable), draft = "ordinary draft"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { sendResult },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithText("original text").performTouchInput { longClick() }
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        sendResult.complete(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ordinary draft").assertDoesNotExist()
    }

    @Test
    fun editActionIsUnavailableForPendingOrFailedDraftSave() {
        val draftResult = CompletableDeferred<Boolean>()
        val editable = message("original text", outgoing = true).copy(
            delivery = DeliveryPresentation.SENT,
            correctionReferenceId = "original-wire-id",
        )
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(messages = listOf(editable), draft = "ordinary draft"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { draftResult },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextReplacement("changed draft")
        composeRule.onNodeWithText("original text").performTouchInput { longClick() }
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
        draftResult.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun visibleMarkableMessageReportsOnceWhenReadReceiptsAreEnabled() {
        var displayedCalls = 0
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("same-id", outgoing = false).copy(
                            markable = true,
                            markerTargetId = "wire-same-id",
                        ),
                    ),
                    readReceiptsEnabled = true,
                    activityResumed = true,
                    onMessageDisplayed = {
                        displayedCalls++
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithText("same-id").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) { displayedCalls == 1 }
        composeRule.waitForIdle()
        assertEquals(1, displayedCalls)
    }

    @Test
    fun visibleMessageReportsWhenMarkableEvidenceArrivesWithoutChangingItsId() {
        lateinit var addMarkerEvidence: () -> Unit
        var displayedCalls = 0
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(message("same-id", outgoing = false)) }
                addMarkerEvidence = {
                    current = current.copy(markable = true, markerTargetId = "wire-same-id")
                }
                MessageTimeline(
                    messages = listOf(current),
                    readReceiptsEnabled = true,
                    activityResumed = true,
                    onMessageDisplayed = {
                        displayedCalls++
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithText("same-id").assertIsDisplayed()
        composeRule.runOnIdle(addMarkerEvidence)
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) { displayedCalls == 1 }
        composeRule.waitForIdle()
        assertEquals(1, displayedCalls)
    }

    @Test
    fun newIncomingCountIgnoresHistoricalBackfill() {
        val previous = listOf(message("old", outgoing = false), message("latest", outgoing = true))

        assertEquals(
            1,
            countAppendedIncoming(
                previousLatestId = "latest",
                messages = previous + message("incoming", outgoing = false) + message("own", outgoing = true),
            ),
        )
        assertEquals(
            0,
            countAppendedIncoming(
                previousLatestId = "latest",
                messages = listOf(message("history", outgoing = false)) + previous,
            ),
        )
    }

    @Test
    fun outgoingAppendIsNotCountedAsNewIncoming() {
        assertEquals(
            0,
            countAppendedIncoming(
                previousLatestId = "latest",
                messages = listOf(
                    message("latest", outgoing = false),
                    message("own", outgoing = true),
                ),
            ),
        )
    }

    @Test
    fun successfulSendJumpsScrolledTimelineToLatest() {
        val sendResult = CompletableDeferred<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "new message").copy(
                        messages = (1..100).map { number ->
                            message("message-$number", outgoing = false)
                        },
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { sendResult },
                )
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(80)
        composeRule.onNodeWithText("message-100").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Send").performClick()
        sendResult.complete(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("message-100").assertIsDisplayed()
    }

    @Test
    fun failedSendPreservesScrolledPositionAndDraft() {
        val sendResult = CompletableDeferred<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "unsent message").copy(
                        messages = (1..100).map { number ->
                            message("message-$number", outgoing = false)
                        },
                        draftReply = DraftReply("reply-id", PEER_A, "quoted body", "Peer A"),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { sendResult },
                )
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(80)
        composeRule.onNodeWithTag("message-composer").performClick().assertIsFocused()

        composeRule.onNodeWithContentDescription("Send").performClick()
        sendResult.complete(false)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("message-100").assertDoesNotExist()
        composeRule.onNodeWithText("unsent message").assertIsDisplayed()
        composeRule.onNodeWithTag("composer-reply-preview").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").assertIsFocused()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, "Message not sent"),
        )
    }

    @Test
    fun synchronousSendExceptionShowsFailureAndPreservesDraft() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "sync draft"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { throw IllegalStateException("send failed") },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()

        composeRule.onNodeWithText("sync draft").assertIsDisplayed()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
    }

    @Test
    fun deferredSendExceptionShowsFailureAndPreservesDraft() {
        val result = CompletableDeferred<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "deferred draft"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { result },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()
        result.completeExceptionally(IllegalStateException("send failed"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("deferred draft").assertIsDisplayed()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
    }

    @Test
    fun failedSendPreservesExactAttachmentForRetry() {
        val file = Files.createTempFile("nema-attachment", ".txt").toFile().apply {
            writeText("attachment")
        }
        val resultOwner = TestActivityResultOwner(Uri.fromFile(file))
        val sends = mutableListOf<DraftSnapshot>()
        var uploads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides resultOwner) {
                MaterialTheme {
                    ConversationContent(
                        state = state(ACCOUNT_A, PEER_A, "attachment draft"),
                        connectionStatus = "Connected",
                        onSelectPeer = { true },
                        onCloseConversation = {},
                        onDraftChange = { CompletableDeferred(true) },
                        onSend = { sends += it; CompletableDeferred(false) },
                        onUploadFile = { name, mime, bytes ->
                            uploads++
                            UploadedFile("https://example.org/file", name, mime, bytes.size.toLong())
                        },
                    )
                }
            }
        }
        resultOwner.resume()

        composeRule.onNodeWithContentDescription("Attach file").performClick()
        composeRule.waitUntil { uploads == 1 }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sends.size == 2 }
        assertEquals(sends.first(), sends.last())
        assertEquals("https://example.org/file", sends.last().attachmentUrl)
    }

    @Test
    fun pendingComposerRevisionAllowsOneSendAndRetryAfterFailure() {
        val firstResult = CompletableDeferred<Boolean>()
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "send once"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = {
                        sends += 1
                        if (sends == 1) firstResult else CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performTouchInput {
            click()
            click()
        }
        composeRule.waitForIdle()
        assertEquals(1, sends)
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()

        firstResult.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("send once").assertIsDisplayed()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(2, sends)
        composeRule.onNodeWithText("Message not sent").assertDoesNotExist()
        composeRule.onNodeWithText("send once").assertDoesNotExist()
    }

    @Test
    fun editingClearsSendFailure() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "failed draft"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(false) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
        composeRule.onNodeWithText("failed draft").performTextReplacement("edited draft")

        composeRule.onNodeWithText("Message not sent").assertDoesNotExist()
        composeRule.onNodeWithText("edited draft").assertIsDisplayed()
    }

    @Test
    fun staleSendFailureDoesNotAffectNewRevisionOrAnotherPeer() {
        var result = CompletableDeferred<Boolean>()
        lateinit var show: (DirectChatState) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(state(ACCOUNT_A, PEER_A, "old draft")) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { result },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithText("old draft").performTextReplacement("new draft")
        result.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Message not sent").assertDoesNotExist()
        composeRule.onNodeWithText("new draft").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
        composeRule.runOnIdle { show(state(ACCOUNT_A, PEER_B, "other draft")) }
        composeRule.onNodeWithText("Message not sent").assertDoesNotExist()
        composeRule.onNodeWithText("other draft").assertIsDisplayed()
        composeRule.runOnIdle { show(state(ACCOUNT_A, PEER_A, "new draft")) }
        composeRule.onNodeWithText("Message not sent").assertIsDisplayed()
        composeRule.onNodeWithText("new draft").performTextReplacement("group draft")
        result = CompletableDeferred()
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle { show(state(ACCOUNT_A, PEER_A, "group draft").copy(selectedPeerGroupChat = true)) }
        composeRule.waitForIdle()
        result.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Message not sent").assertDoesNotExist()
    }

    @Test
    fun successfulSettlementStillAllowsOnlyOneSendForRevision() {
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "send once"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = {
                        sends += 1
                        CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performTouchInput {
            click()
            click()
        }
        composeRule.waitForIdle()

        assertEquals(1, sends)
    }

    @Test
    fun pendingSendRemainsDeduplicatedAcrossConversationReentry() {
        val sendResult = CompletableDeferred<Boolean>()
        val conversation = state(ACCOUNT_A, PEER_A)
        lateinit var show: (DirectChatState) -> Unit
        var persistedDraft = ""
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(conversation) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = {
                        persistedDraft = it.body
                        CompletableDeferred(true)
                    },
                    onSend = {
                        sends += 1
                        sendResult
                    },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("send once")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle { show(conversation.copy(selectedPeer = null, draft = persistedDraft)) }
        composeRule.runOnIdle { show(conversation.copy(draft = persistedDraft)) }
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(1, sends)
    }

    @Test
    fun successfulSendCompletionAppliesAfterConversationReentry() {
        val sendResult = CompletableDeferred<Boolean>()
        val conversation = state(ACCOUNT_A, PEER_A).copy(
            messages = (1..100).map { number -> message("message-$number", outgoing = false) },
        )
        lateinit var show: (DirectChatState) -> Unit
        var persistedDraft = ""
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(conversation) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = {
                        persistedDraft = it.body
                        CompletableDeferred(true)
                    },
                    onSend = { sendResult },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("send once")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle { show(conversation.copy(selectedPeer = null, draft = persistedDraft)) }
        composeRule.runOnIdle { show(conversation.copy(draft = persistedDraft)) }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(80)
        sendResult.complete(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("send once").assertDoesNotExist()
        composeRule.onNodeWithText("message-100").assertIsDisplayed()
    }

    @Test
    fun pendingTypedSendSurvivesProfileReentryAndSettlesCurrentComposer() {
        val sendResult = CompletableDeferred<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = (1..100).map { number ->
                            message("message-$number", outgoing = false)
                        },
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { sendResult },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("send once")
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(80)
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.onNodeWithContentDescription("Back to conversation").performClick()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        sendResult.complete(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("send once").assertDoesNotExist()
        composeRule.onNodeWithText("message-100").assertIsDisplayed()
    }

    @Test
    fun heldSendDisablesAnotherSendForSameRevision() {
        val threadResult = CompletableDeferred<Boolean>()
        var normalSends = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "root"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = {
                        normalSends += 1
                        threadResult
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        composeRule.onNodeWithText("Send as thread").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(1, normalSends)
    }

    @Test
    fun laterComposerRevisionCanSendWhileEarlierRevisionIsPending() {
        val firstResult = CompletableDeferred<Boolean>()
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "first"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = {
                        sends += 1
                        if (sends == 1) firstResult else CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.onNodeWithTag("message-composer").performTextReplacement("second")
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(2, sends)
    }

    @Test
    fun nearLatestTimelineFollowsIncomingAppend() {
        lateinit var show: (List<TimelineMessage>) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var messages by remember {
                    mutableStateOf((1..20).map { number -> message("message-$number", outgoing = false) })
                }
                show = { messages = it }
                MessageTimeline(messages = messages)
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(1)
        composeRule.runOnIdle {
            show((1..21).map { number -> message("message-$number", outgoing = false) })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("message-21").assertIsDisplayed()
        composeRule.onNodeWithText("+1").assertDoesNotExist()
    }

    @Test
    fun slightlyDetachedTimelineKeepsIncomingCount() {
        lateinit var show: (List<TimelineMessage>) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var messages by remember {
                    mutableStateOf((1..20).map { number -> message("message-$number", outgoing = false) })
                }
                show = { messages = it }
                MessageTimeline(messages = messages)
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(2)
        composeRule.runOnIdle {
            show((1..21).map { number -> message("message-$number", outgoing = false) })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("message-21").assertDoesNotExist()
        composeRule.onNodeWithText("+1").assertIsDisplayed()
    }

    @Test
    fun catchUpBatchDoesNotYankHistoryReader() {
        lateinit var show: (List<TimelineMessage>) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var messages by remember {
                    mutableStateOf((1..20).map { number -> message("message-$number", outgoing = false) })
                }
                show = { messages = it }
                MessageTimeline(messages = messages)
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(5)
        composeRule.runOnIdle {
            show((1..30).map { number -> message("message-$number", outgoing = false) })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("message-30").assertDoesNotExist()
        composeRule.onNodeWithText("+10").assertIsDisplayed()
    }

    @Test
    fun scrolledUpTimelineCountsOnlyFreshIncomingAndJumpsToLatest() {
        lateinit var show: (List<TimelineMessage>) -> Unit
        composeRule.setContent {
            MaterialTheme {
                var messages by remember {
                    mutableStateOf((1..100).map { number -> message("message-$number", outgoing = false) })
                }
                show = { messages = it }
                MessageTimeline(messages = messages)
            }
        }
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(80)
        composeRule.runOnIdle {
            show((1..101).map { number -> message("message-$number", outgoing = false) })
        }

        composeRule.onNodeWithText("+1").assertIsDisplayed()
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(0)
        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(80)
        composeRule.onNodeWithText("+1").assertDoesNotExist()
        composeRule.runOnIdle {
            show((1..102).map { number -> message("message-$number", outgoing = false) })
        }
        composeRule.onNodeWithText("+1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest messages").performClick()
        composeRule.onNodeWithText("message-102").assertIsDisplayed()
        composeRule.onNodeWithText("+1").assertDoesNotExist()
    }

    @Test
    fun holdingSendUsesOrdinaryActionWithoutHiddenNavigation() {
        var threaded: DraftSnapshot? = null
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "root"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { snapshot ->
                        threaded = snapshot
                        CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Hold for thread").assertDoesNotExist()
        composeRule.onNodeWithText("Send as thread").assertDoesNotExist()
        composeRule.onNodeWithText("Send").assertDoesNotExist()
        composeRule.onNodeWithTag("message-composer-container").assertHeightIsEqualTo(44.dp)
        composeRule.onNodeWithContentDescription("Send")
            .assertHeightIsEqualTo(48.dp)
            .assertIsEnabled()
        composeRule.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        composeRule.onNodeWithText("Send as thread").assertDoesNotExist()
        composeRule.waitForIdle()

        assertEquals("root", threaded?.body)
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), threaded?.key)
    }

    @Test
    fun emptyComposerKeepsSendActionDisabled() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send")
            .assertHeightIsEqualTo(48.dp)
            .assertIsNotEnabled()
    }

    @Test
    fun longPressMessageOffersReplyActionsWithoutInlineActionNoise() {
        val parent = ThreadRef(ThreadId.require("parent-thread"))
        var replied: TimelineMessage? = null
        var repliedAsThread: TimelineMessage? = null
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("ordinary message", outgoing = false).copy(replyReferenceId = "wire-id"),
                        message("threaded message", outgoing = false).copy(
                            thread = parent,
                            replyReferenceId = "thread-wire-id",
                        ),
                    ),
                    onReply = { replied = it },
                    onQuote = {},
                    onReplyAsThread = { repliedAsThread = it },
                )
            }
        }

        composeRule.onNodeWithText("Reply").assertDoesNotExist()
        composeRule.onNodeWithText("Open thread").assertDoesNotExist()
        composeRule.onNodeWithText("ordinary message").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply").performClick()
        assertEquals("ordinary message", replied?.id)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("threaded message").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Quote").assertIsDisplayed()
        composeRule.onNodeWithText("Reply as a thread").performClick()
        assertEquals("threaded message", repliedAsThread?.id)
    }

    @Test
    fun cancellingBackgroundReadClosesItsActiveSource() = runBlocking {
        val source = BlockingInputStream()
        val decode = launch(Dispatchers.Default) {
            source.useCancellable { it.read() }
        }
        assertTrue(source.readStarted.await(5, TimeUnit.SECONDS))

        decode.cancelAndJoin()

        assertTrue(source.closed.await(5, TimeUnit.SECONDS))
        assertTrue(decode.isCancelled)
    }


    @Test
    fun accountSwitchClearsPeerInputAndCancelsOldSelectionCompletion() {
        val selectionStarted = CompletableDeferred<Unit>()
        val selectionResult = CompletableDeferred<Boolean>()
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var current by remember {
                    mutableStateOf(state(ACCOUNT_A, PEER_A).copy(selectedPeer = null))
                }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = {
                        selectionStarted.complete(Unit)
                        selectionResult.await()
                    },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("New chat").performClick()
        composeRule.onNodeWithText("Direct message JID").performTextInput("old@example.org")
        composeRule.onNodeWithText("Open conversation").performClick()
        composeRule.waitUntil { selectionStarted.isCompleted }
        composeRule.runOnIdle {
            show(state(ACCOUNT_B, PEER_B).copy(selectedPeer = null))
        }

        composeRule.onNodeWithText("old@example.org").assertDoesNotExist()
        selectionResult.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enter a valid person JID").assertDoesNotExist()
    }

    @Test
    fun currentDraftExceptionPreservesBodyAndShowsSafeFailure() {
        val draftResult = CompletableDeferred<Boolean>()
        var sends = 0

        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "saved"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { draftResult },
                    onSend = {
                        sends++
                        CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithText("saved").performTextReplacement("visible")
        draftResult.completeExceptionally(IllegalStateException("injected"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("visible").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
        assertEquals(0, sends)
    }

    @Test
    fun currentFalseDraftResultPreservesBodyAndShowsSafeFailure() {
        val draftResult = CompletableDeferred<Boolean>()

        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { draftResult },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("visible")
        draftResult.complete(false)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("visible").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
    }

    @Test
    fun delayedDraftFailureSettlesRetainedConversationWhileAnotherIsOpen() {
        val draftResult = CompletableDeferred<Boolean>()
        val snapshots = mutableListOf<DraftSnapshot>()
        val conversationA = state(ACCOUNT_A, PEER_A)
        val conversationB = state(ACCOUNT_A, PEER_B)
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(conversationA) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { snapshot ->
                        snapshots += snapshot
                        draftResult
                    },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("unsaved A")
        composeRule.waitUntil { snapshots.size == 1 }
        composeRule.runOnIdle { show(conversationB) }
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()

        draftResult.complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()

        composeRule.runOnIdle { show(conversationA) }
        composeRule.onNodeWithText("unsaved A").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
    }

    @Test
    fun currentGroupChatAuthorityOverridesRetainedComposerAcrossReentry() {
        val firstDraftResult = CompletableDeferred<Boolean>()
        val draftSnapshots = mutableListOf<DraftSnapshot>()
        val sendSnapshots = mutableListOf<DraftSnapshot>()
        val direct = state(ACCOUNT_A, PEER_A)
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(direct) }
                show = { current = it }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { snapshot ->
                        draftSnapshots += snapshot
                        if (draftSnapshots.size == 1) firstDraftResult else CompletableDeferred(true)
                    },
                    onSend = { snapshot ->
                        sendSnapshots += snapshot
                        CompletableDeferred(false)
                    },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("retained body")
        composeRule.waitUntil { draftSnapshots.size == 1 }
        composeRule.runOnIdle { show(direct.copy(selectedPeer = null)) }
        firstDraftResult.complete(false)
        composeRule.waitForIdle()
        composeRule.runOnIdle { show(direct.copy(selectedPeerGroupChat = true)) }
        composeRule.onNodeWithText("retained body").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()

        composeRule.onNodeWithTag("message-composer").performTextReplacement("room body")
        composeRule.waitUntil { draftSnapshots.size == 2 }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sendSnapshots.size == 1 }

        assertEquals(false, draftSnapshots.first().groupChat)
        assertTrue(draftSnapshots.last().groupChat)
        assertTrue(sendSnapshots.single().groupChat)
    }

    @Test
    fun staleDraftFailureCannotAffectNewerRevision() {
        val snapshots = mutableListOf<DraftSnapshot>()
        val results = mutableListOf<CompletableDeferred<Boolean>>()

        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { snapshot ->
                        snapshots += snapshot
                        CompletableDeferred<Boolean>().also(results::add)
                    },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("N")
        composeRule.waitUntil { results.size == 1 }
        composeRule.onNodeWithText("N").performTextReplacement("N+1")
        composeRule.waitUntil { results.size == 2 }
        results[0].completeExceptionally(IllegalStateException("stale"))
        composeRule.waitForIdle()

        assertEquals(listOf(1L, 2L), snapshots.map(DraftSnapshot::composerRevision))
        assertEquals(
            listOf(DirectConversationKey(ACCOUNT_A, PEER_A)),
            snapshots.map(DraftSnapshot::key).distinct(),
        )
        composeRule.onNodeWithText("N+1").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()

        results[1].completeExceptionally(IllegalStateException("current"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
    }

    @Test
    fun staleDraftSuccessCannotClearNewerFailure() {
        val results = mutableListOf<CompletableDeferred<Boolean>>()

        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = {
                        CompletableDeferred<Boolean>().also(results::add)
                    },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("N")
        composeRule.waitUntil { results.size == 1 }
        composeRule.onNodeWithText("N").performTextReplacement("N+1")
        composeRule.waitUntil { results.size == 2 }
        results[1].complete(false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()

        results[0].complete(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("N+1").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
    }

    @Test
    fun cancelledDraftResultReportsFailureWithoutClearingBody() {
        val draftResult = CompletableDeferred<Boolean>()

        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { draftResult },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("visible")
        draftResult.cancel()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("visible").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
    }

    @Test
    fun delayedDraftAndSendStayBoundToCapturedPeer() {
        val draftStarted = CompletableDeferred<DraftSnapshot>()
        val sendStarted = CompletableDeferred<DraftSnapshot>()
        val draftResult = CompletableDeferred<Boolean>()
        val sendResult = CompletableDeferred<Boolean>()
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var state by remember { mutableStateOf(state(ACCOUNT_A, PEER_A)) }
                show = { state = it }
                ConversationContent(
                    state = state,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { snapshot ->
                        draftStarted.complete(snapshot)
                        draftResult
                    },
                    onSend = { snapshot ->
                        sendStarted.complete(snapshot)
                        sendResult
                    },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").performTextInput("draft A")
        composeRule.waitUntil { draftStarted.isCompleted }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sendStarted.isCompleted }
        composeRule.runOnIdle { show(state(ACCOUNT_A, PEER_B, "draft B")) }
        composeRule.onNodeWithText("draft B").assertIsDisplayed()

        draftResult.completeExceptionally(IllegalStateException("old peer"))
        sendResult.complete(true)
        composeRule.waitForIdle()

        val draft = runBlocking { draftStarted.await() }
        val send = runBlocking { sendStarted.await() }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), draft.key)
        assertEquals(1L, draft.composerRevision)
        assertEquals("draft A", draft.body)
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), send.key)
        assertEquals(1L, send.composerRevision)
        assertEquals("draft A", send.body)
        composeRule.onNodeWithText("draft B").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
    }

    @Test
    fun samePeerComposerIsKeyedByAccount() {
        val draftStarted = CompletableDeferred<DraftSnapshot>()
        val sendStarted = CompletableDeferred<DraftSnapshot>()
        val draftResult = CompletableDeferred<Boolean>()
        val sendResult = CompletableDeferred<Boolean>()
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var state by remember { mutableStateOf(state(ACCOUNT_A, PEER_A, "account A draft")) }
                show = { state = it }
                ConversationContent(
                    state = state,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { snapshot ->
                        draftStarted.complete(snapshot)
                        draftResult
                    },
                    onSend = { snapshot ->
                        sendStarted.complete(snapshot)
                        sendResult
                    },
                )
            }
        }

        composeRule.onNodeWithText("account A draft").performTextReplacement("account A edited")
        composeRule.waitUntil { draftStarted.isCompleted }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sendStarted.isCompleted }
        composeRule.runOnIdle { show(state(ACCOUNT_B, PEER_A, "account B draft")) }
        composeRule.onNodeWithText("account B draft").assertIsDisplayed()

        draftResult.complete(false)
        sendResult.complete(true)
        composeRule.waitForIdle()

        val draft = runBlocking { draftStarted.await() }
        val send = runBlocking { sendStarted.await() }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), draft.key)
        assertEquals(1L, draft.composerRevision)
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), send.key)
        assertEquals(1L, send.composerRevision)
        composeRule.onNodeWithText("account B draft").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
    }

    @Test
    fun composerIsKeyedByCompleteThreadLineage() {
        val sharedChild = ThreadId.require("shared-child")
        val firstLineage = ThreadRef(sharedChild, ThreadId.require("first-parent"))
        val secondLineage = ThreadRef(sharedChild, ThreadId.require("second-parent"))
        val firstDraftResult = CompletableDeferred<Boolean>()
        val firstSendResult = CompletableDeferred<Boolean>()
        val sends = mutableListOf<DraftSnapshot>()
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var state by remember {
                    mutableStateOf(
                        state(ACCOUNT_A, PEER_A, "first draft").copy(selectedThread = firstLineage),
                    )
                }
                show = { state = it }
                ConversationContent(
                    state = state,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { firstDraftResult },
                    onSend = { snapshot ->
                        sends += snapshot
                        if (sends.size == 1) firstSendResult else CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithText("first draft").performTextReplacement("first edited")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sends.size == 1 }
        composeRule.runOnIdle {
            show(state(ACCOUNT_A, PEER_A, "second draft").copy(selectedThread = secondLineage))
        }
        composeRule.onNodeWithText("second draft").assertIsDisplayed()

        firstDraftResult.complete(false)
        firstSendResult.complete(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sends.size == 2 }

        assertEquals(
            DraftSnapshot(DirectConversationKey(ACCOUNT_A, PEER_A, firstLineage), "first edited", 1L),
            sends[0],
        )
        assertEquals(
            DraftSnapshot(DirectConversationKey(ACCOUNT_A, PEER_A, secondLineage), "second draft", 0L),
            sends[1],
        )
    }

    @Test
    fun composerRestoresOneCoherentStateForSameConversation() {
        val restorationTester = StateRestorationTester(composeRule)
        val sends = mutableListOf<DraftSnapshot>()

        restorationTester.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "room draft"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(false) },
                    onSend = { snapshot ->
                        sends += snapshot
                        CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithText("room draft").performTextReplacement("edited draft")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
        composeRule.onNodeWithTag("message-composer").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Error, "Draft not saved"),
        )

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("edited draft").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sends.size == 1 }
        assertEquals(
            DraftSnapshot(DirectConversationKey(ACCOUNT_A, PEER_A), "edited draft", 1L),
            sends.single(),
        )
    }

    @Test
    fun restoredComposerFromAnotherLineageFailsClosedToCurrentRoomDraft() {
        val restorationTester = StateRestorationTester(composeRule)
        val firstLineage = ThreadRef(ThreadId.require("shared-child"), ThreadId.require("first-parent"))
        val secondLineage = ThreadRef(ThreadId.require("shared-child"), ThreadId.require("second-parent"))
        var restoredState = state(ACCOUNT_A, PEER_A, "first room draft").copy(selectedThread = firstLineage)
        val sends = mutableListOf<DraftSnapshot>()

        restorationTester.setContent {
            MaterialTheme {
                ConversationContent(
                    state = restoredState,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(false) },
                    onSend = { snapshot ->
                        sends += snapshot
                        CompletableDeferred(true)
                    },
                )
            }
        }

        composeRule.onNodeWithText("first room draft").performTextReplacement("first edited")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Draft not saved").assertIsDisplayed()
        composeRule.runOnIdle {
            restoredState = state(ACCOUNT_A, PEER_A, "second room draft").copy(selectedThread = secondLineage)
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("second room draft").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sends.size == 1 }
        assertEquals(
            DraftSnapshot(DirectConversationKey(ACCOUNT_A, PEER_A, secondLineage), "second room draft", 0L),
            sends.single(),
        )
    }

    @Test
    fun appliedSendDoesNotClearNewerInput() {
        val sendStarted = CompletableDeferred<DraftSnapshot>()
        val sendResult = CompletableDeferred<Boolean>()

        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A, "first"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { snapshot ->
                        sendStarted.complete(snapshot)
                        sendResult
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil { sendStarted.isCompleted }
        composeRule.onNodeWithText("first").performTextReplacement("newer")
        sendResult.complete(true)
        composeRule.waitForIdle()

        assertEquals("first", runBlocking { sendStarted.await() }.body)
        composeRule.onNodeWithText("newer").assertIsDisplayed()
    }

    @Test
    fun replyAsThreadIsHiddenWithoutTrustedReference() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(message("untrusted message", outgoing = false)),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("untrusted message").performTouchInput { longClick() }

        composeRule.onNodeWithText("Quote").assertIsDisplayed()
        composeRule.onNodeWithText("Reply").assertDoesNotExist()
        composeRule.onNodeWithText("Reply as a thread").assertDoesNotExist()
    }

    @Test
    fun replyAsThreadIsHiddenForGroupChatEvenWithTrustedReference() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        selectedPeerGroupChat = true,
                        messages = listOf(
                            message("room message", outgoing = false).copy(
                                groupChat = true,
                                replyReferenceId = "room-stanza-id",
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("room message").performTouchInput { longClick() }

        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Quote").assertIsDisplayed()
        composeRule.onNodeWithText("Reply as a thread").assertDoesNotExist()
    }

    @Test
    fun replyAsThreadIsHiddenForChatRowInsideRoomConversation() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        selectedPeerGroupChat = true,
                        messages = listOf(
                            message("private room message", outgoing = false).copy(
                                groupChat = false,
                                replyReferenceId = "private-stanza-id",
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("private room message").performTouchInput { longClick() }

        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Quote").assertIsDisplayed()
        composeRule.onNodeWithText("Reply as a thread").assertDoesNotExist()
    }

    @Test
    fun replyAsThreadFocusesComposerOnlyAfterSuccessfulOpen() {
        var succeeds = false
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(
                            message("thread target", outgoing = false).copy(replyReferenceId = "wire-id"),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onStartThreadFrom = { succeeds },
                )
            }
        }

        composeRule.onNodeWithTag("message-composer").assertIsNotFocused()
        composeRule.onNodeWithText("thread target").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply as a thread").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-composer").assertIsNotFocused()

        composeRule.runOnIdle { succeeds = true }
        composeRule.onNodeWithText("thread target").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply as a thread").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-composer").assertIsFocused()
    }

    @Test
    fun rootThreadSummaryShowsCountLatestPreviewAndOpensExactLineage() {
        val thread = ThreadRef(
            ThreadId.require("thread-summary"),
            ThreadId.require("parent-thread"),
        )
        var opened: ThreadRef? = null
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        messages = listOf(
                            message("root body", outgoing = false).copy(
                                threadSummaries = listOf(
                                    ThreadSummary(
                                        thread,
                                        replyCount = 2,
                                        latestMessageId = "thread-latest",
                                        latestPreview = "latest answer",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onContinueThread = { opened = it; true },
                )
            }
        }

        composeRule.onNodeWithText("Thread · 2 replies").assertIsDisplayed()
        composeRule.onNodeWithText("latest answer").assertIsDisplayed()
        composeRule.onNodeWithText("Thread · 2 replies").performClick()
        composeRule.waitForIdle()
        assertEquals(thread, opened)
    }

    @Test
    fun outgoingRootShowsThreadSummaryChip() {
        val thread = ThreadRef(
            ThreadId.require("own-thread"),
            ThreadId.require("parent-thread"),
        )
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("own send", outgoing = true).copy(
                            threadSummaries = listOf(
                                ThreadSummary(
                                    thread,
                                    replyCount = 1,
                                    latestMessageId = "peer-reply",
                                    latestPreview = "peer answer",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNode(
            hasTestTag("message-bubble-own send") and
                hasAnyDescendant(androidx.compose.ui.test.hasText("Thread · 1 replies")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }

    @Test
    fun threadBackRestoresParentViewportByMessageIdentity() {
        val thread = ThreadRef(ThreadId.require("viewport-thread"))
        val parentMessages = (1..100).map { number ->
            message("message-$number", outgoing = false).let { original ->
                if (number == 21) {
                    original.copy(
                        threadSummaries = listOf(
                            ThreadSummary(
                                thread,
                                replyCount = 1,
                                latestMessageId = "viewport-reply",
                                latestPreview = "viewport reply",
                            ),
                        ),
                    )
                } else {
                    original
                }
            }
        }
        val parent = state(ACCOUNT_A, PEER_A).copy(messages = parentMessages)
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(parent) }
                ConversationContent(
                    state = current,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onContinueThread = {
                        current = parent.copy(
                            selectedThread = thread,
                            messages = listOf(
                                message("message-21", outgoing = false),
                                message("viewport reply", outgoing = false).copy(thread = thread),
                            ),
                        )
                        true
                    },
                    onCloseThread = { current = parent },
                )
            }
        }

        composeRule.onNodeWithTag("message-timeline").performScrollToIndex(79)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Thread · 1 replies").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Back to conversation").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Thread · 1 replies").assertIsDisplayed()
        composeRule.onNodeWithText("message-21").assertIsDisplayed()
    }

    @Test
    fun conversationAndThreadActionsUseThreadLineageNotMessageIdentity() {
        val thread = ThreadRef(ThreadId.require("thread-a"))
        var opened: ThreadRef? = null
        var threadSource: TimelineMessage? = null
        var newThreads = 0
        lateinit var show: (DirectChatState) -> Unit

        composeRule.setContent {
            MaterialTheme {
                var state by remember {
                    mutableStateOf(
                        state(ACCOUNT_A, PEER_A).copy(
                            messages = listOf(
                                TimelineMessage(
                                    id = "message-identity",
                                    senderJid = PEER_A,
                                    body = "thread root",
                                    outgoing = false,
                                    delivery = null,
                                    retryUncertainKey = null,
                                    thread = null,
                                    replyReferenceId = "thread-root-wire-id",
                                    threadSummaries = listOf(
                                        ThreadSummary(thread, 1, "message-identity", "thread root"),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                show = { state = it }
                ConversationContent(
                    state = state,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onCreateNamedThread = { _, _ -> newThreads++; true },
                    onContinueThread = { opened = it; true },
                    onStartChildThread = { true },
                    onStartThreadFrom = { threadSource = it; true },
                    onCloseThread = {},
                )
            }
        }

        composeRule.onNodeWithText("Thread · 1 replies").performClick()
        composeRule.waitForIdle()
        assertEquals("thread-a", opened?.id?.value)
        assertTrue(opened?.id?.value != "message-identity")
        composeRule.onNodeWithTag("message-bubble-message-identity")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reply as a thread").performClick()
        composeRule.waitForIdle()
        assertEquals("message-identity", threadSource?.id)
        assertEquals(null, threadSource?.thread)
        composeRule.onNodeWithTag("thread-switcher").performClick()
        composeRule.onNodeWithText("New thread").performClick()
        composeRule.onNodeWithTag("thread-name-input").performTextInput("Project")
        composeRule.onNodeWithText("Create thread").performClick()
        composeRule.waitForIdle()
        assertEquals(1, newThreads)

        composeRule.runOnIdle {
            show(state(ACCOUNT_A, PEER_A).copy(selectedThread = thread))
        }
        composeRule.onNodeWithTag("thread-switcher").assertIsDisplayed()
        composeRule.onNodeWithText("Child thread").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back to conversation").assertIsDisplayed()
    }

    @Test
    fun inThreadAppBarShowsThreadSubtitleAndBackToConversation() {
        composeRule.setContent {
            MaterialTheme {
                ConversationContent(
                    state = state(ACCOUNT_A, PEER_A).copy(
                        selectedThread = ThreadRef(ThreadId.require("topic")),
                    ),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("Thread").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to conversation").assertIsDisplayed()
        composeRule.onNodeWithText(PEER_A).assertIsDisplayed()
    }

    @Test
    fun sessionThreadMetadataDoesNotExposePerMessageNavigation() {
        val thread = ThreadRef(ThreadId.require("topic"))
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        TimelineMessage(
                            id = "incoming-thread",
                            senderJid = PEER_A,
                            body = "incoming",
                            outgoing = false,
                            delivery = null,
                            retryUncertainKey = null,
                            thread = thread,
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("thread-chip").assertDoesNotExist()
        composeRule.onNodeWithText("incoming").performTouchInput { doubleClick() }
        composeRule.onNodeWithText("Open thread").assertDoesNotExist()
    }

    @Test
    fun roomThreadMetadataDoesNotExposeUnsupportedThreadNavigation() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        TimelineMessage(
                            id = "room-thread",
                            senderJid = "room@conference.example.org/alice",
                            body = "room message",
                            outgoing = false,
                            delivery = null,
                            retryUncertainKey = null,
                            thread = ThreadRef(ThreadId.require("thread")),
                            groupChat = true,
                        ),
                    ),
                    venue = ConversationVenue.Room(null, 0),
                )
            }
        }

        composeRule.onNodeWithTag("thread-chip").assertDoesNotExist()
        composeRule.onNodeWithText("room message").performTouchInput { doubleClick() }
        composeRule.onNodeWithText("Open thread").assertDoesNotExist()
    }

    @Test
    fun doubleTapOnThreadedBubbleOpensMessageActions() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        TimelineMessage(
                            id = "incoming-thread",
                            senderJid = PEER_A,
                            body = "incoming",
                            outgoing = false,
                            delivery = null,
                            retryUncertainKey = null,
                            thread = ThreadRef(ThreadId.require("thread")),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("incoming").performTouchInput { doubleClick() }
        composeRule.onNodeWithText("Quote").assertIsDisplayed()
        composeRule.onNodeWithText("Open thread").assertDoesNotExist()
    }

    @Test
    fun messageActionsHaveAccessibleActivationWithoutChangingPhysicalSingleTap() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(message("accessible", outgoing = false)),
                )
            }
        }

        composeRule.onNodeWithTag("message-bubble-accessible").performTouchInput { click() }
        composeRule.onNodeWithText("Quote").assertDoesNotExist()
        composeRule.onNodeWithTag("message-bubble-accessible")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Quote").assertIsDisplayed()
    }

    @Test
    fun replySummaryStacksInsideBubbleWithAccessibleTouchTarget() {
        val thread = ThreadRef(ThreadId.require("thread"))
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        TimelineMessage(
                            id = "stacked-thread",
                            senderJid = PEER_A,
                            body = "incoming",
                            outgoing = false,
                            delivery = null,
                            retryUncertainKey = null,
                            thread = null,
                            threadSummaries = listOf(
                                ThreadSummary(thread, 1, "stacked-thread", "incoming"),
                            ),
                        ),
                    ),
                )
            }
        }

        val summaryTag = "thread-summary-${thread.draftKey()}"
        composeRule.onNodeWithTag(summaryTag).assertHeightIsAtLeast(48.dp)
        val bubble = composeRule.onNodeWithTag("message-bubble-stacked-thread")
            .fetchSemanticsNode().boundsInRoot
        val summary = composeRule.onNodeWithTag(summaryTag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "reply summary escapes its message bubble",
            summary.top >= bubble.top && summary.bottom <= bubble.bottom,
        )
    }

    @Test
    fun reactionChipIsDisplayedOnTimelineMessage() {
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(
                        message("reacted", outgoing = false).copy(
                            reactions = listOf(
                                org.thanosapollo.nema.xmpp.reactions.ReactionDisplay(
                                    "reacted",
                                    "👍",
                                    1,
                                    false,
                                    listOf(PEER_A),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        composeRule.onNodeWithTag("reaction-chip-reacted-👍").assertIsDisplayed()
    }

    @Test
    fun reactionPolicyRequiresAuthoritativeMatchingRoomMessageAndPreservesDirect() {
        val direct = message("direct", outgoing = false)
        val authorizedRoom = direct.copy(groupChat = true, replyReferenceId = "room-sid")

        assertTrue(canReact(ConversationVenue.Direct, direct))
        assertTrue(canReact(ConversationVenue.Room(null, 0), authorizedRoom))
        assertFalse(canReact(ConversationVenue.Room(null, 0), authorizedRoom.copy(replyReferenceId = null)))
        assertFalse(canReact(ConversationVenue.Room(null, 0), direct))
        assertFalse(canReact(ConversationVenue.Direct, authorizedRoom))
    }

    @Test
    fun authorizedRoomReactionChipAndPickerInvokeLocalMessageCallback() {
        val message = message("room-local", outgoing = false).copy(
            groupChat = true,
            replyReferenceId = "room-sid",
            reactions = listOf(
                org.thanosapollo.nema.xmpp.reactions.ReactionDisplay(
                    "room-local",
                    "❤️",
                    1,
                    false,
                    listOf(PEER_A),
                ),
            ),
        )
        var reacted = emptyList<Pair<String, String>>()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(message),
                    venue = ConversationVenue.Room(null, 0),
                    onReact = { selected, emoji ->
                        reacted = reacted + (selected.id to emoji)
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("reaction-chip-room-local-❤️").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-bubble-room-local")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reactions").performClick()
        composeRule.onNodeWithTag("reaction-picker").assertIsDisplayed()
        composeRule.onNodeWithText("👍").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("room-local" to "❤️", "room-local" to "👍"), reacted)
        }
    }

    @Test
    fun roomReactionWithoutAuthorityIsReadOnlyAndHidden() {
        val message = message("room-untrusted", outgoing = false).copy(
            groupChat = true,
            reactions = listOf(
                org.thanosapollo.nema.xmpp.reactions.ReactionDisplay(
                    "room-untrusted",
                    "👍",
                    1,
                    false,
                    listOf(PEER_A),
                ),
            ),
        )
        var reacted = emptyList<String>()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(message),
                    venue = ConversationVenue.Room(null, 0),
                    onReact = { _, emoji ->
                        reacted = reacted + emoji
                        true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("reaction-chip-room-untrusted-👍")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("message-bubble-room-untrusted")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reactions").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), reacted) }
    }

    @Test
    fun reactionsMenuOpensPickerInsteadOfEmojiRows() {
        var reacted = emptyList<String>()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(message("pick", outgoing = false)),
                    onReact = { _, emoji ->
                        reacted = reacted + emoji
                        true
                    },
                )
            }
        }
        composeRule.onNodeWithTag("message-bubble-pick")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reactions").assertIsDisplayed()
        composeRule.onNodeWithTag("reaction-picker").assertDoesNotExist()
        composeRule.onNodeWithText("Reactions").performClick()
        composeRule.onNodeWithTag("reaction-picker").assertIsDisplayed()
        composeRule.onNodeWithText("Quote").assertDoesNotExist()
        composeRule.onNodeWithText("👍").performClick()
        composeRule.runOnIdle { assertEquals(listOf("👍"), reacted) }
        composeRule.onNodeWithTag("reaction-picker").assertDoesNotExist()
    }

    @Test
    fun clickingOutsideClosesReactionPickerWithoutReacting() {
        var reacted = emptyList<String>()
        composeRule.setContent {
            MaterialTheme {
                MessageTimeline(
                    messages = listOf(message("dismiss", outgoing = false)),
                    onReact = { _, emoji ->
                        reacted = reacted + emoji
                        true
                    },
                )
            }
        }
        composeRule.onNodeWithTag("message-bubble-dismiss")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Reactions").performClick()
        composeRule.onNodeWithTag("reaction-picker").assertIsDisplayed()
        composeRule.onNodeWithTag("reaction-picker-dismiss").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithTag("reaction-picker").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("reaction-picker").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), reacted) }
    }

    private fun state(accountId: String, peer: String, draft: String = "") = DirectChatState(
        accountId = accountId,
        selectedPeer = peer,
        draft = draft,
    )

    private fun session(generation: Long, accountId: String = ACCOUNT_A) = SessionIdentity(
        AccountId.require(accountId),
        ConnectionGeneration.require(generation),
    )

    private fun message(id: String, outgoing: Boolean) = TimelineMessage(
        id = id,
        senderJid = if (outgoing) ACCOUNT_A else PEER_A,
        body = id,
        outgoing = outgoing,
        delivery = null,
        retryUncertainKey = null,
        thread = null,
    )

    private fun renderedTextColor(label: String): Int {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val action = composeRule.onNodeWithText(label).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
        checkNotNull(action.action)(layouts)
        return layouts.single().layoutInput.style.color.toArgb()
    }

    companion object {
        private const val ACCOUNT_A = "account-a"
        private const val ACCOUNT_B = "account-b"
        private const val PEER_A = "peer-a@example.org"
        private const val PEER_B = "peer-b@example.org"
    }

    private class TestActivityResultOwner(private val uri: Uri) : ActivityResultRegistryOwner, LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.CREATED
        }
        override val lifecycle: Lifecycle = lifecycleRegistry
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }

        fun resume() { lifecycleRegistry.currentState = Lifecycle.State.RESUMED }
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "read was not cancelled" }
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }
}
