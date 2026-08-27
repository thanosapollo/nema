package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jxmpp.jid.impl.JidCreate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class StableIdAuthorityRegistryTest {
    private val first = attempt(1)
    private val second = attempt(2)
    private val account = "account@example.org"
    private val room = "room@conference.example.org"

    @Test
    fun `account capability never confers room authority`() {
        val registry = RoomStableIdAuthorityRegistry()
        registry.begin(first)

        assertEquals(account, trustedStableIdAuthority(direct(), first, account, true, registry))
        assertNull(trustedStableIdAuthority(groupchat(room), first, account, true, registry))

        join(registry, first, room)

        assertEquals(room, trustedStableIdAuthority(groupchat(room), first, account, false, registry))
        assertNull(
            trustedStableIdAuthority(
                groupchat("other@conference.example.org"),
                first,
                account,
                true,
                registry,
            ),
        )
    }

    @Test
    fun `room authority is exact and cleared across attempts`() {
        val registry = RoomStableIdAuthorityRegistry()
        registry.begin(first)
        join(registry, first, room)
        assertEquals(true, registry.stableIdSupport(first, room))
        val pending = requireNotNull(registry.beginJoin(first, room))

        registry.begin(second)

        assertFalse(registry.publish(pending, stableIds = true, occupantIds = true))
        assertNull(registry.stableIdSupport(second, room))
        assertNull(registry.beginJoin(first, room))
        val denied = requireNotNull(registry.beginJoin(second, room))
        assertTrue(registry.publish(denied, stableIds = false, occupantIds = false))
        assertEquals(false, registry.stableIdSupport(second, room))
        assertNull(registry.stableIdSupport(first, room))
    }

    @Test
    fun `room feature facts preserve unknown negative and positive independently`() {
        val registry = RoomStableIdAuthorityRegistry()
        registry.begin(first)
        val stableOnly = requireNotNull(registry.beginJoin(first, room))

        assertNull(registry.stableIdSupport(first, room))
        assertNull(registry.occupantIdSupport(first, room))
        assertTrue(registry.publish(stableOnly, stableIds = true, occupantIds = false))
        assertEquals(true, registry.stableIdSupport(first, room))
        assertEquals(false, registry.occupantIdSupport(first, room))

        val occupantOnly = requireNotNull(registry.beginJoin(first, room))
        assertTrue(registry.publish(occupantOnly, stableIds = false, occupantIds = true))
        assertEquals(false, registry.stableIdSupport(first, room))
        assertEquals(true, registry.occupantIdSupport(first, room))
    }

    @Test
    fun `pending join preserves snapshot and canceled pending cannot publish`() {
        val registry = RoomStableIdAuthorityRegistry()
        registry.begin(first)
        val firstLease = requireNotNull(registry.beginJoin(first, room))
        assertTrue(registry.publish(firstLease, true, false, "nick1"))
        val firstSnapshot = RoomMembershipSnapshot(firstLease, true, false, "nick1")
        val pending = requireNotNull(registry.beginJoin(first, room))

        assertEquals(firstSnapshot, registry.snapshot(first, room))
        assertEquals(true, registry.stableIdSupport(first, room))
        assertEquals(false, registry.occupantIdSupport(first, room))
        assertEquals(firstLease, registry.lease(first, room))
        assertTrue(registry.isCurrent(firstLease))

        assertTrue(registry.revoke(pending))
        assertFalse(registry.publish(pending, false, true, "nick2"))
        assertEquals(firstSnapshot, registry.snapshot(first, room))

        val lostPending = requireNotNull(registry.beginJoin(first, room))
        assertTrue(registry.revoke(firstLease))
        assertFalse(registry.publish(lostPending, false, true, "lost"))
        assertNull(registry.snapshot(first, room))

        val clearedPending = requireNotNull(registry.beginJoin(first, room))
        registry.begin(first)
        assertNull(registry.snapshot(first, room))
        assertFalse(registry.publish(clearedPending, true, true, "nick3"))
    }

    @Test
    fun `publish swaps complete snapshot and stale revoke cannot remove replacement`() {
        val registry = RoomStableIdAuthorityRegistry()
        registry.begin(first)
        val firstLease = requireNotNull(registry.beginJoin(first, room))
        assertTrue(registry.publish(firstLease, true, false, "nick1"))
        val secondLease = requireNotNull(registry.beginJoin(first, room))

        assertTrue(registry.publish(secondLease, false, true, "nick2"))
        val secondSnapshot = RoomMembershipSnapshot(secondLease, false, true, "nick2")
        assertEquals(secondSnapshot, registry.snapshot(first, room))
        assertFalse(registry.publish(secondLease, true, false, "split"))
        assertFalse(registry.revoke(firstLease))
        assertEquals(secondSnapshot, registry.snapshot(first, room))
        assertEquals(false, registry.stableIdSupport(first, room))
        assertEquals(true, registry.occupantIdSupport(first, room))
        assertNull(registry.lease(first, room))
        assertFalse(registry.isCurrent(secondLease))
    }

    @Test
    fun `membership incarnations reject loss races and stale callbacks`() {
        val registry = RoomStableIdAuthorityRegistry()
        val other = "other@conference.example.org"
        registry.begin(first)
        val firstRoomLease = join(registry, first, room)
        join(registry, first, other)
        assertTrue(firstRoomLease.incarnation > 0)
        val mamLease = requireNotNull(registry.lease(first, room))
        var currentRevocations = 0
        val firstListener = RoomStableIdRevocationListener(registry, firstRoomLease) {
            currentRevocations += 1
        }

        firstListener.membershipRevoked()

        assertFalse(registry.isCurrent(mamLease))
        assertNull(registry.stableIdSupport(first, room))
        assertEquals(true, registry.stableIdSupport(first, other))
        assertEquals(account, trustedStableIdAuthority(direct(), first, account, true, registry))
        assertEquals(1, currentRevocations)

        val secondRoomLease = join(registry, first, room)
        assertTrue(secondRoomLease.incarnation > firstRoomLease.incarnation)
        assertFalse(registry.isCurrent(mamLease))
        firstListener.kicked(JidCreate.entityBareFrom("moderator@example.org"), "stale")
        assertTrue(registry.isCurrent(secondRoomLease))
        assertEquals(1, currentRevocations)

        RoomStableIdRevocationListener(registry, secondRoomLease) {}.membershipRevoked()
        val unpublished = requireNotNull(registry.beginJoin(first, room))
        RoomStableIdRevocationListener(registry, unpublished) {}.kicked(
            JidCreate.entityBareFrom("moderator@example.org"),
            "loss before listener replacement",
        )
        assertFalse(registry.publish(unpublished, stableIds = true, occupantIds = true))
        assertNull(registry.stableIdSupport(first, room))

        val bannedBeforePublish = requireNotNull(registry.beginJoin(first, room))
        RoomStableIdRevocationListener(registry, bannedBeforePublish) {}.banned(
            JidCreate.entityBareFrom("admin@example.org"),
            "before publish",
        )
        assertFalse(registry.publish(bannedBeforePublish, stableIds = true, occupantIds = true))
        assertNull(registry.stableIdSupport(first, room))
    }

    private fun join(
        registry: RoomStableIdAuthorityRegistry,
        attempt: SessionAttemptIdentity,
        authority: String,
    ): RoomStableIdLease {
        val lease = requireNotNull(registry.beginJoin(attempt, authority))
        assertTrue(registry.publish(lease, stableIds = true, occupantIds = false))
        return lease
    }

    private fun direct(): Message = StanzaBuilder.buildMessage("direct")
        .from(JidCreate.entityFullFrom("peer@example.org/device"))
        .ofType(Message.Type.chat)
        .setBody("body")
        .build()

    private fun groupchat(roomJid: String): Message = StanzaBuilder.buildMessage("group")
        .from(JidCreate.entityFullFrom("$roomJid/alice"))
        .ofType(Message.Type.groupchat)
        .setBody("body")
        .build()

    private fun attempt(number: Long) = SessionAttemptIdentity(
        AccountId.require("account"),
        ConnectionGeneration.require(number),
        ConnectionAttempt.require(number),
        LifecycleEpoch.require(number),
    )
}
