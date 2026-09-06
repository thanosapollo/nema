package org.thanosapollo.nema

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.thanosapollo.nema.chat.canonicalDirectPeer
import org.thanosapollo.nema.service.XmppConnectionService

/** Navigation input only: possession never authorizes account activation. */
internal data class MessageNotificationTarget(val accountId: String, val peerJid: String) {
    // Android ignores extras when identifying PendingIntents. Use the complete pair,
    // with encoded path segments, for both PendingIntent and notification identity.
    val notificationTag: String
        get() = Uri.Builder().scheme("nema-message").authority("conversation")
            .appendPath(accountId).appendPath(peerJid).build().toString()

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_MESSAGE)
        .setData(Uri.parse(notificationTag))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(XmppConnectionService.EXTRA_ACCOUNT_ID, accountId)
        .putExtra(XmppConnectionService.EXTRA_PEER_JID, peerJid)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_OPEN_MESSAGE = "org.thanosapollo.nema.action.OPEN_MESSAGE"

        fun fromIntent(intent: Intent?): MessageNotificationTarget? = try {
            if (intent?.action != ACTION_OPEN_MESSAGE) null else {
                val account = intent.getStringExtra(XmppConnectionService.EXTRA_ACCOUNT_ID)
                val peer = intent.getStringExtra(XmppConnectionService.EXTRA_PEER_JID)
                if (account.isNullOrBlank() || account.length > 1024 || account.any(Char::isISOControl) ||
                    peer == null || peer.length > 3071 || canonicalDirectPeer(peer) != peer
                ) null else MessageNotificationTarget(account, peer)
            }
        } catch (_: RuntimeException) {
            null // Exported activity extras may be malformed or not strings.
        }
    }
}
