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

internal data class RoomMembershipSnapshot(
    val lease: RoomStableIdLease,
    val stableIds: Boolean,
    val occupantIds: Boolean,
    val ownNick: String?,
)

internal class RoomStableIdAuthorityRegistry {
    private var attempt: SessionAttemptIdentity? = null
    private var nextIncarnation = 0L
    private val snapshots = mutableMapOf<String, RoomMembershipSnapshot>()
    private val pending = mutableMapOf<String, RoomStableIdLease>()

    @Synchronized
    fun begin(next: SessionAttemptIdentity) {
        attempt = next
        snapshots.clear()
        pending.clear()
    }

    @Synchronized
    fun beginJoin(current: SessionAttemptIdentity, authority: String): RoomStableIdLease? {
        if (attempt != current) return null
        val lease = RoomStableIdLease(current, authority, ++nextIncarnation)
        pending[authority] = lease
        return lease
    }

    @Synchronized
    fun publish(
        lease: RoomStableIdLease,
        stableIds: Boolean,
        occupantIds: Boolean,
        ownNick: String? = null,
    ): Boolean {
        if (attempt != lease.attempt || pending[lease.authority] != lease) return false
        pending.remove(lease.authority)
        snapshots[lease.authority] = RoomMembershipSnapshot(lease, stableIds, occupantIds, ownNick)
        return true
    }

    @Synchronized
    fun snapshot(current: SessionAttemptIdentity, authority: String): RoomMembershipSnapshot? =
        snapshots[authority].takeIf { attempt == current }

    @Synchronized
    fun stableIdSupport(current: SessionAttemptIdentity, authority: String): Boolean? =
        snapshot(current, authority)?.stableIds

    @Synchronized
    fun occupantIdSupport(current: SessionAttemptIdentity, authority: String): Boolean? =
        snapshot(current, authority)?.occupantIds

    @Synchronized
    fun lease(current: SessionAttemptIdentity, authority: String): RoomStableIdLease? =
        snapshot(current, authority)?.takeIf { it.stableIds }?.lease

    @Synchronized
    fun isCurrent(lease: RoomStableIdLease): Boolean =
        snapshot(lease.attempt, lease.authority)?.let { it.lease == lease && it.stableIds } == true

    @Synchronized
    fun revoke(lease: RoomStableIdLease): Boolean {
        if (attempt != lease.attempt) return false
        if (pending[lease.authority] == lease) return pending.remove(lease.authority) != null
        if (snapshots[lease.authority]?.lease != lease) return false
        pending.remove(lease.authority)
        return snapshots.remove(lease.authority) != null
    }

    @Synchronized
    fun retireAll() {
        attempt = null
        snapshots.clear()
        pending.clear()
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
