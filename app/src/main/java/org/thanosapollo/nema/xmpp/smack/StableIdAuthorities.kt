package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jxmpp.jid.Jid
import org.thanosapollo.nema.session.SessionAttemptIdentity

internal data class RoomStableIdLease(
    val attempt: SessionAttemptIdentity,
    val authority: String,
    val incarnation: Long,
)

internal data class RoomFeatureSupport(
    val stableIds: Boolean,
    val occupantIds: Boolean,
)

internal class RoomStableIdAuthorityRegistry {
    private data class Entry(
        val lease: RoomStableIdLease,
        val stableIds: Boolean?,
        val occupantIds: Boolean?,
    )

    private var attempt: SessionAttemptIdentity? = null
    private var nextIncarnation = 0L
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun begin(next: SessionAttemptIdentity) {
        if (attempt == next) return
        attempt = next
        entries.clear()
    }

    @Synchronized
    fun beginJoin(current: SessionAttemptIdentity, authority: String): RoomStableIdLease? {
        if (attempt != current) return null
        val lease = RoomStableIdLease(current, authority, ++nextIncarnation)
        entries[authority] = Entry(lease, null, null)
        return lease
    }

    @Synchronized
    fun publish(lease: RoomStableIdLease, stableIds: Boolean, occupantIds: Boolean): Boolean {
        if (attempt != lease.attempt || entries[lease.authority]?.lease != lease) return false
        entries[lease.authority] = Entry(lease, stableIds, occupantIds)
        return true
    }

    @Synchronized
    fun stableIdSupport(current: SessionAttemptIdentity, authority: String): Boolean? =
        entries[authority]?.stableIds.takeIf { attempt == current }

    @Synchronized
    fun occupantIdSupport(current: SessionAttemptIdentity, authority: String): Boolean? =
        entries[authority]?.occupantIds.takeIf { attempt == current }

    @Synchronized
    fun lease(current: SessionAttemptIdentity, authority: String): RoomStableIdLease? =
        entries[authority]?.takeIf { attempt == current && it.stableIds == true }?.lease

    @Synchronized
    fun isCurrent(lease: RoomStableIdLease): Boolean {
        val entry = entries[lease.authority]
        return attempt == lease.attempt && entry?.lease == lease && entry?.stableIds == true
    }

    @Synchronized
    fun revoke(lease: RoomStableIdLease): Boolean {
        if (attempt != lease.attempt) return false
        val entry = entries[lease.authority] ?: return false
        if (entry.lease != lease && entry.stableIds != null) return false
        entries.remove(lease.authority)
        return true
    }

    @Synchronized
    fun retireAll() {
        attempt = null
        entries.clear()
    }
}

internal class RoomStableIdRevocationListener(
    private val registry: RoomStableIdAuthorityRegistry,
    private val lease: RoomStableIdLease,
    private val onRevoked: () -> Unit,
) : UserStatusListener {
    private fun revoke() {
        if (registry.revoke(lease)) onRevoked()
    }

    override fun kicked(actor: Jid?, reason: String?) = revoke()
    override fun banned(actor: Jid?, reason: String?) = revoke()
    override fun removed(mucUser: MUCUser, presence: Presence) = revoke()
    override fun membershipRevoked() = revoke()
    override fun roomDestroyed(multiUserChat: MultiUserChat, reason: String?) = revoke()
}

internal fun trustedStableIdAuthority(
    message: Message,
    attempt: SessionAttemptIdentity,
    expectedBareJid: String,
    accountSupported: Boolean,
    roomAuthorities: RoomStableIdAuthorityRegistry,
): String? {
    if (message.type != Message.Type.groupchat) return expectedBareJid.takeIf { accountSupported }
    val room = message.from?.asBareJid()?.takeIf { it.isEntityBareJid }?.toString() ?: return null
    return room.takeIf { roomAuthorities.stableIdSupport(attempt, room) == true }
}
