package org.thanosapollo.nema.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

internal const val INCOMING_MESSAGE_CHANNEL_ID = "xmpp_messages_alert"
internal const val LEGACY_MESSAGE_CHANNEL_ID = "xmpp_messages"

internal fun incomingMessageChannel(name: String, description: String): NotificationChannel =
    NotificationChannel(INCOMING_MESSAGE_CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
        this.description = description
        setShowBadge(true)
        enableLights(true)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 200, 80, 200)
        setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build(),
        )
    }

internal fun incomingMessageNotification(
    context: Context,
    peer: String,
    preview: String,
    openIntent: PendingIntent,
    icon: Int,
): Notification {
    val body = preview.ifBlank { "New message" }
    val builder = Notification.Builder(context, INCOMING_MESSAGE_CHANNEL_ID)
        .setSmallIcon(icon)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setContentTitle(peer)
        .setContentText(body)
        .setContentIntent(openIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(false)
    if (Build.VERSION.SDK_INT >= 28) {
        val you = Person.Builder().setName("You").build()
        val them = Person.Builder().setName(peer).build()
        builder.setStyle(
            Notification.MessagingStyle(you).addMessage(body, System.currentTimeMillis(), them),
        )
    }
    return builder.build()
}
