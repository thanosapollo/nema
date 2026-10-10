package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.createRobolectricComposeRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class TimelineDaySeparatorTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private val athens = ZoneId.of("Europe/Athens")

    private fun message(id: String, sentAt: String?, protectedState: String = "NONE") =
        TimelineMessage(id, "peer@example.org", "body $id", false, delivery = null,
            retryUncertainKey = null, thread = null,
            sentAtEpochMs = sentAt?.let { Instant.parse(it).toEpochMilli() }, protectedState = protectedState)

    private fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try { return block() } finally { Locale.setDefault(previous) }
    }

    @Test fun labelsTheFirstMessageOfEachLocalDay() = withLocale(Locale.ENGLISH) {
        val messages = listOf(
            message("a", "2026-10-04T20:50:00Z"), // 23:50 Sun 4 Oct in Athens
            message("b", "2026-10-04T21:10:00Z"), // 00:10 Mon 5 Oct
            message("c", "2026-10-05T08:00:00Z"),
            message("d", "2026-10-06T06:00:00Z"),
        )
        val labels = timelineDayLabels(messages, TimelineDay(athens, LocalDate.of(2026, 10, 6)))

        assertEquals(mapOf("a" to "Sun 4 Oct", "b" to "Yesterday", "d" to "Today"), labels)
    }

    @Test fun boundariesFollowThePresentationTimezone() {
        val messages = listOf(message("a", "2026-10-04T20:30:00Z"), message("b", "2026-10-04T21:30:00Z"))
        val today = LocalDate.of(2026, 10, 5)

        assertEquals(setOf("a"), timelineDayLabels(messages, TimelineDay(ZoneId.of("UTC"), today)).keys)
        assertEquals(setOf("a", "b"), timelineDayLabels(messages, TimelineDay(athens, today)).keys)
    }

    @Test fun midnightTurnsTodayIntoYesterday() {
        val messages = listOf(message("a", "2026-10-05T12:00:00Z"))
        val beforeMidnight = TimelineDay.now(Instant.parse("2026-10-05T20:59:59Z").toEpochMilli(), athens)
        val afterMidnight = TimelineDay.now(Instant.parse("2026-10-05T21:00:00Z").toEpochMilli(), athens)

        assertEquals(1_000L, beforeMidnight.millisUntilNextDay(Instant.parse("2026-10-05T20:59:59Z").toEpochMilli()))
        assertEquals(mapOf("a" to "Today"), timelineDayLabels(messages, beforeMidnight))
        assertEquals(mapOf("a" to "Yesterday"), timelineDayLabels(messages, afterMidnight))
    }

    @Test fun missingTimestampsGetNoLabelAndDoNotEndTheDay() {
        val today = TimelineDay(athens, LocalDate.of(2026, 10, 6))
        val sameDay = listOf(message("a", "2026-10-06T06:00:00Z"), message("x", null), message("b", "2026-10-06T07:00:00Z"))
        val newDay = listOf(message("a", "2026-10-05T06:00:00Z"), message("x", null), message("b", "2026-10-06T07:00:00Z"))

        assertEquals(setOf("a"), timelineDayLabels(sameDay, today).keys)
        assertEquals(setOf("a", "b"), timelineDayLabels(newDay, today).keys)
        assertTrue(timelineDayLabels(listOf(message("x", null)), today).isEmpty())
    }

    @Test fun olderPageMovesTheLabelInsteadOfRepeatingIt() {
        val today = TimelineDay(athens, LocalDate.of(2026, 10, 6))
        val latestPage = listOf(message("c", "2026-10-06T08:00:00Z"), message("d", "2026-10-06T09:00:00Z"))
        val olderPage = listOf(message("a", "2026-10-05T08:00:00Z"), message("b", "2026-10-06T07:00:00Z"))

        assertEquals(mapOf("c" to "Today"), timelineDayLabels(latestPage, today))
        assertEquals(mapOf("a" to "Yesterday", "b" to "Today"), timelineDayLabels(olderPage + latestPage, today))
    }

    @Test fun olderYearsIncludeTheYear() = withLocale(Locale.ENGLISH) {
        val today = LocalDate.of(2026, 1, 2)

        assertEquals("Sun 1 Mar", formatDayLabel(LocalDate.of(2026, 3, 1), today))
        assertEquals("31 Dec 2025", formatDayLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test fun separatorsSitAboveTheirRowsIncludingProtectedRows() {
        val older = message("older", "2025-03-01T10:00:00Z", protectedState = "UNSUPPORTED_PAYLOAD")
        val newer = message("newer", "2025-03-02T10:00:00Z")
        val sameDay = message("same-day", "2025-03-02T11:00:00Z")
        val labels = timelineDayLabels(listOf(older, newer, sameDay), TimelineDay.now())
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(older, newer, sameDay))
            }
        }

        assertEquals(2, compose.onAllNodesWithTag("day-separator", useUnmergedTree = true).fetchSemanticsNodes().size)
        val olderLabel = compose.onNodeWithText(labels.getValue("older"), useUnmergedTree = true).assertIsDisplayed()
        val newerLabel = compose.onNodeWithText(labels.getValue("newer"), useUnmergedTree = true).assertIsDisplayed()
        val protectedCard = compose.onNodeWithTag("protected-message-older", useUnmergedTree = true)
        val bubble = compose.onNodeWithTag("message-bubble-newer", useUnmergedTree = true)
        assertTrue(olderLabel.getUnclippedBoundsInRoot().bottom <= protectedCard.getUnclippedBoundsInRoot().top)
        assertTrue(newerLabel.getUnclippedBoundsInRoot().bottom <= bubble.getUnclippedBoundsInRoot().top)
        assertTrue(protectedCard.getUnclippedBoundsInRoot().bottom <= newerLabel.getUnclippedBoundsInRoot().top)
    }
}
