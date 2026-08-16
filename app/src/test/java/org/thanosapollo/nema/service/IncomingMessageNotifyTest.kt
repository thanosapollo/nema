package org.thanosapollo.nema.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncomingMessageNotifyTest {
    @Test
    fun messageChannelHeadsUpWithSoundAndVibration() {
        val channel = incomingMessageChannel("Messages", "Incoming messages")
        assertEquals(INCOMING_MESSAGE_CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.shouldVibrate())
        assertTrue(channel.canShowBadge())
        assertTrue(channel.shouldShowLights())
    }

    @Test
    fun messageNotificationUsesMessageCategoryAndAlertChannel() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, context.javaClass),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = incomingMessageNotification(
            context = context,
            peer = "alice@example.org",
            preview = "hello",
            openIntent = intent,
            icon = android.R.drawable.stat_notify_chat,
        )
        assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
        assertEquals(INCOMING_MESSAGE_CHANNEL_ID, notification.channelId)
        assertEquals("alice@example.org", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }
}
