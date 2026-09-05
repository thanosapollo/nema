package org.thanosapollo.nema.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.toPresentation
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.TimelineRow
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UnknownDeliveryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun unknownIsNeutralAndReadableInLightDirectChat() = assertUnknown(dark = false, room = false)
    @Test fun unknownIsNeutralAndReadableInDarkRoom() = assertUnknown(dark = true, room = true)

    private fun assertUnknown(dark: Boolean, room: Boolean) {
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        val row = row().copy(
            body = "A longer message body that wraps across several lines without obscuring delivery status.",
            messageKind = if (room) MessageKind.GROUPCHAT else MessageKind.CHAT,
            sentAtEpochMs = 253402300799000L,
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.width(240.dp)) {
                    MessageTimeline(messages = listOf(row.toPresentation(emptySet())))
                }
            }
        }
        val label = composeRule.onNodeWithText("Delivery unknown", useUnmergedTree = true)
        label.assertIsDisplayed().assertHasNoClickAction()
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(colors.onSurfaceVariant, layouts.single().layoutInput.style.color)
        val layout = layouts.single()
        assertFalse(layout.isLineEllipsized(0))
        org.junit.Assert.assertTrue(layout.getLineRight(0) <= layout.size.width + 1f)
        org.junit.Assert.assertTrue(layout.getLineBottom(0) <= layout.size.height + 1f)
        composeRule.onNodeWithText(row.body, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(formatMessageTime(requireNotNull(row.sentAtEpochMs)), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delivered", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Read", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("✓", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Failed", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun laterReceiptsReplaceUnknownAtTheConsumedTimelineBoundary() {
        val current = mutableStateOf(row())
        composeRule.setContent {
            MaterialTheme { MessageTimeline(messages = listOf(current.value.toPresentation(emptySet()))) }
        }
        composeRule.onNodeWithText("Delivery unknown", useUnmergedTree = true).assertIsDisplayed()
        for ((receipt, label) in listOf(
            MessageReceiptStage.RECEIVED to "Delivered",
            MessageReceiptStage.DISPLAYED to "Read",
            MessageReceiptStage.ACKNOWLEDGED to "Read",
        )) {
            composeRule.runOnIdle { current.value = row().copy(receiptStage = receipt.name) }
            composeRule.onNodeWithText("Delivery unknown", useUnmergedTree = true).assertDoesNotExist()
            composeRule.onNodeWithContentDescription(label, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun existingOutboxLabelsRemainUnchanged() {
        val current = mutableStateOf(row().copy(outboxStatus = OutboxStatus.PENDING.name))
        composeRule.setContent {
            MaterialTheme { MessageTimeline(messages = listOf(current.value.toPresentation(emptySet()))) }
        }
        for ((status, label) in listOf(
            OutboxStatus.PENDING to "Queued",
            OutboxStatus.IN_FLIGHT to "Sending",
            OutboxStatus.ACKNOWLEDGED to "Sent",
            OutboxStatus.CONFIRMED to "Sent",
            OutboxStatus.FAILED to "Failed",
        )) {
            composeRule.runOnIdle { current.value = row().copy(outboxStatus = status.name) }
            composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("Delivery unknown", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun row() = TimelineRow(
        accountId = "account", localMessageId = "message", peerJid = "peer@example.org",
        senderJid = "self@example.org", direction = MessageDirection.OUTBOUND,
        messageKind = MessageKind.CHAT, threadId = null, parentThreadId = null,
        body = "Hello", localSequence = 1, archiveOrdinal = null, sentAtEpochMs = null,
        sentTimeSource = null, operationId = "operation", outboxStatus = OutboxStatus.UNCERTAIN.name,
        outboxGeneration = 1, outboxAttempt = 1,
        conversationArchiveAuthority = "", conversationArchiveScope = "",
    )
}
