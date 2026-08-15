package org.thanosapollo.nema.ui.chat

import android.app.Application
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.MessageReplyPresentation
import org.thanosapollo.nema.chat.ThreadSummary
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.ui.theme.AppearanceSpec
import org.thanosapollo.nema.ui.theme.NemaTheme
import org.thanosapollo.nema.ui.theme.MIN_TEXT_CONTRAST
import org.thanosapollo.nema.ui.theme.PaletteChoice
import org.thanosapollo.nema.ui.theme.ThemeMode
import org.thanosapollo.nema.ui.theme.contrastRatio
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DirectChatContentTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    fun signalStyleHeaderOpensAccountQualifiedContactInfo() {
        var nicknameKey: DirectConversationKey? = null
        var nickname: String? = null
        var blockKey: DirectConversationKey? = null
        var blockValue: Boolean? = null
        var sharedPeer: String? = null
        val thread = ThreadRef(ThreadId.require("topic"))
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
        composeRule.onNodeWithText("Remote Name").assertIsDisplayed()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(5)
        composeRule.onNodeWithText("Plaintext").assertIsDisplayed()
        composeRule.onNodeWithTag("encryption-info").assert(
            SemanticsMatcher("has no click action") { !it.config.contains(SemanticsActions.OnClick) },
        )
        composeRule.waitUntil { blockKey != null }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), blockKey)

        composeRule.onNodeWithTag("share-xmpp-action").performClick()
        assertEquals(PEER_A, sharedPeer)

        composeRule.onNodeWithTag("nickname-action").performClick()
        composeRule.onNodeWithTag("nickname-input").performTextReplacement("Chosen")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil { nickname != null }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), nicknameKey)
        assertEquals("Chosen", nickname)

        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(6)
        composeRule.onNodeWithTag("block-action").performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.waitUntil { blockValue != null }
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), blockKey)
        assertEquals(true, blockValue)
    }

    @Test
    fun domainBlockConfirmationNamesDomainWideUnblock() {
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
        composeRule.onNodeWithTag("block-action").performClick()

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
                DirectChatContent(
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
        composeRule.onNodeWithTag("block-action").performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.waitUntil { mutationStarted.isCompleted }

        composeRule.runOnIdle { show(state(ACCOUNT_B, PEER_B)) }
        composeRule.onNodeWithContentDescription("Open contact info").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact-info-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("block-action").assertIsDisplayed()

        mutationResult.complete(PeerBlockingMutationResult.Uncertain)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Block outcome unknown. Reopen contact info to refresh.")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("block-action").assertIsDisplayed()
    }

    @Test
    fun sameAccountGenerationReplacementClearsAndReloadsBlockingState() {
        lateinit var reconnect: () -> Unit
        composeRule.setContent {
            MaterialTheme {
                var generation by remember { mutableStateOf(1L) }
                reconnect = { generation = 2L }
                DirectChatContent(
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
                DirectChatContent(
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
        composeRule.onNodeWithTag("block-action").performClick()
        composeRule.onNodeWithTag("confirm-block").performClick()
        composeRule.waitUntil { oldMutationStarted.isCompleted }

        composeRule.runOnIdle(reconnect)
        composeRule.waitUntil {
            composeRule.onAllNodesWithTag("block-action").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("block-action").performClick()
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
                DirectChatContent(
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
    fun threadActionUsesReadableTextColorWithoutUncertainRetryNoise() {
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
                            thread = ThreadRef(ThreadId.require("thread")),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("incoming").performTouchInput { longClick() }
        val incoming = contrastRatio(renderedTextColor("Open thread"), containers.second)

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
                    DirectChatContent(
                        state = current,
                        connectionStatus = "Connected",
                        onSelectPeer = { true },
                        onCloseConversation = { closeConversation++ },
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
    }

    @Test
    fun incomingMessagesUseBlackBubbleWithWhiteTextWithoutChangingOutgoingPalette() {
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
                    0xFF000000.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFF2C6BED.toInt(),
                    0xFFFFFFFF.toInt(),
                ),
                observed,
            )
        }
        assertEquals(0xFFFFFFFF.toInt(), renderedTextColor("material-incoming"))
        assertEquals(0xFFFFFFFF.toInt(), renderedTextColor("material-outgoing"))
    }

    @Test
    fun longRoomSubjectCannotExpandConversationTopBar() {
        val subject = "NOTICE: your client says no one is here but there are over 1000 here, " +
            "please ignore that and continue discussing open hardware\n\nCode of Conduct:\n1. Stay on topic"
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
    fun quoteActionPlacesQuoteBeforeDraftAndLeavesAnswerOutsideQuote() {
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "unsent message").copy(
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

        composeRule.onNodeWithContentDescription("Send").performClick()
        sendResult.complete(false)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("message-100").assertDoesNotExist()
        composeRule.onNodeWithText("unsent message").assertIsDisplayed()
    }

    @Test
    fun pendingComposerRevisionAllowsOneSendAndRetryAfterFailure() {
        val firstResult = CompletableDeferred<Boolean>()
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
        composeRule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(2, sends)
    }

    @Test
    fun successfulSettlementStillAllowsOnlyOneSendForRevision() {
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
    fun pendingThreadSendDisablesNormalSendForSameRevision() {
        val threadResult = CompletableDeferred<Boolean>()
        var normalSends = 0
        var threadSends = 0
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
                    state = state(ACCOUNT_A, PEER_A, draft = "root"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = {
                        normalSends += 1
                        CompletableDeferred(true)
                    },
                    onSendAsNewThread = {
                        threadSends += 1
                        threadResult
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        composeRule.onNodeWithText("Send as thread").performClick()
        composeRule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send").performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(1, threadSends)
        assertEquals(0, normalSends)
    }

    @Test
    fun laterComposerRevisionCanSendWhileEarlierRevisionIsPending() {
        val firstResult = CompletableDeferred<Boolean>()
        var sends = 0
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
    fun longPressSendOffersExplicitSendAsThreadAction() {
        var threaded: DraftSnapshot? = null
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
                    state = state(ACCOUNT_A, PEER_A, "root"),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onSendAsNewThread = { snapshot ->
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
        composeRule.onNodeWithText("Send as thread").performClick()
        composeRule.waitForIdle()

        assertEquals("root", threaded?.body)
        assertEquals(DirectConversationKey(ACCOUNT_A, PEER_A), threaded?.key)
    }

    @Test
    fun emptyComposerKeepsSendActionDisabled() {
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { draftResult },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("Message").performTextInput("visible")
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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

        composeRule.onNodeWithText("Message").performTextInput("N")
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
                DirectChatContent(
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

        composeRule.onNodeWithText("Message").performTextInput("N")
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
    fun cancelledDraftResultDoesNotReportFailureOrClearBody() {
        val draftResult = CompletableDeferred<Boolean>()

        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
                    state = state(ACCOUNT_A, PEER_A),
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { draftResult },
                    onSend = { CompletableDeferred(true) },
                )
            }
        }

        composeRule.onNodeWithText("Message").performTextInput("visible")
        draftResult.cancel()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("visible").assertIsDisplayed()
        composeRule.onNodeWithText("Draft not saved").assertDoesNotExist()
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
                DirectChatContent(
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

        composeRule.onNodeWithText("Message").performTextInput("draft A")
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
                DirectChatContent(
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
        composeRule.onNodeWithText("Thread · 1 reply").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Back to conversation").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Thread · 1 reply").assertIsDisplayed()
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
                                    thread = thread,
                                    replyReferenceId = "thread-root-wire-id",
                                ),
                            ),
                        ),
                    )
                }
                show = { state = it }
                DirectChatContent(
                    state = state,
                    connectionStatus = "Connected",
                    onSelectPeer = { true },
                    onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) },
                    onStartNewThread = { newThreads++; true },
                    onContinueThread = { opened = it; true },
                    onStartChildThread = { true },
                    onStartThreadFrom = { threadSource = it; true },
                    onCloseThread = {},
                )
            }
        }

        composeRule.onNodeWithText("thread root").performTouchInput { longClick() }
        composeRule.onNodeWithText("Open thread").performClick()
        composeRule.waitForIdle()
        assertEquals("thread-a", opened?.id?.value)
        assertTrue(opened?.id?.value != "message-identity")
        composeRule.onNodeWithText("thread root").performTouchInput { longClick() }
        composeRule.onNodeWithText("Reply as a thread").performClick()
        composeRule.waitForIdle()
        assertEquals("message-identity", threadSource?.id)
        assertEquals(thread, threadSource?.thread)
        composeRule.onNodeWithContentDescription("Conversation actions").performClick()
        composeRule.onNodeWithText("New thread").performClick()
        composeRule.waitForIdle()
        assertEquals(1, newThreads)

        composeRule.runOnIdle {
            show(state(ACCOUNT_A, PEER_A).copy(selectedThread = thread))
        }
        composeRule.onNodeWithContentDescription("Conversation actions").performClick()
        composeRule.onNodeWithText("Child thread").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to conversation").assertIsDisplayed()
    }

    @Test
    fun inThreadAppBarShowsThreadSubtitleAndBackToConversation() {
        composeRule.setContent {
            MaterialTheme {
                DirectChatContent(
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
    fun threadedBubbleChipOpensThatThread() {
        var opened: ThreadRef? = null
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
                    onContinueThread = { opened = it },
                )
            }
        }

        composeRule.onNodeWithTag("thread-chip").performClick()
        composeRule.waitForIdle()
        assertEquals(thread, opened)
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
