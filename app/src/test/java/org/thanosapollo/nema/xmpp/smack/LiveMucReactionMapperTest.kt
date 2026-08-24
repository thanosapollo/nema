package org.thanosapollo.nema.xmpp.smack

import java.util.Date
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension
import org.jivesoftware.smackx.muc.MUCAffiliation
import org.jivesoftware.smackx.muc.packet.MUCItem
import org.jxmpp.jid.impl.JidCreate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.ReactionActor
import org.thanosapollo.nema.xmpp.transport.OutgoingReactionEnvelope

class LiveMucReactionMapperTest {
    @Test
    fun `reaction XML preserves exact target set recipient and kind`() {
        val chat = reaction(MessageKind.CHAT, "peer@example.org").toSmackReaction()
        val group = reaction(MessageKind.GROUPCHAT, ROOM).toSmackReaction()

        assertEquals(Message.Type.chat, chat.type)
        assertEquals(Message.Type.groupchat, group.type)
        assertEquals("peer@example.org", chat.to.toString())
        assertEquals(ROOM, group.to.toString())
        val payload = "<reactions xmlns='urn:xmpp:reactions:0' id='room-sid'>" +
            "<reaction>🔥</reaction><reaction>👍</reaction></reactions>" +
            "<store xmlns='urn:xmpp:hints'></store></message>"
        listOf(chat to "peer@example.org", group to ROOM).forEach { (message, recipient) ->
            val type = if (message.type == Message.Type.chat) "chat" else "groupchat"
            val xml = message.toXML().toString()
            assertEquals("<message xmlns='jabber:client' to='$recipient' type='$type'>$payload", xml)
            assertEquals(2, "<reaction>".toRegex().findAll(xml).count())
        }
    }

    @Test
    fun `group reaction authority pins exact current stable room lease`() {
        val registry = RoomStableIdAuthorityRegistry()
        registry.begin(FIRST)
        val current = requireNotNull(registry.beginJoin(FIRST, ROOM))
        assertTrue(registry.publish(current, stableIds = true, occupantIds = false))
        val group = reaction(MessageKind.GROUPCHAT, ROOM)

        assertTrue(reactionSendAuthorized(group, FIRST, current, registry))
        assertFalse(reactionSendAuthorized(group.copy(recipient = OTHER_ROOM), FIRST, current, registry))
        assertFalse(reactionSendAuthorized(
            group.copy(accountId = AccountId.require("other")), FIRST, current, registry,
        ))
        assertFalse(reactionSendAuthorized(group, SECOND, current, registry))

        val unsupported = requireNotNull(registry.beginJoin(FIRST, ROOM))
        assertTrue(registry.publish(unsupported, stableIds = false, occupantIds = true))
        assertFalse(reactionSendAuthorized(group, FIRST, current, registry))
        assertFalse(reactionSendAuthorized(group, FIRST, unsupported, registry))

        val replacement = requireNotNull(registry.beginJoin(FIRST, ROOM))
        assertTrue(registry.publish(replacement, stableIds = true, occupantIds = false))
        assertFalse(reactionSendAuthorized(group, FIRST, current, registry))
        assertTrue(reactionSendAuthorized(group, FIRST, replacement, registry))
    }

    @Test
    fun `live room actor uses occupant identity and exact scope`() {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(FIRST) }
        val roomLease = join(registry, ROOM)
        val otherLease = join(registry, OTHER_ROOM)
        fun mapped(room: String, nick: String, id: String, lease: RoomStableIdLease) = requireNotNull(
            group(room, nick, NemaOccupantIdElement(id, true)).toIncomingReaction(
                FIRST, SELF, lease, registry, ownRoomNick = "self",
            ),
        )

        val alice = mapped(ROOM, "alice", "opaque-a", roomLease)
        val sameNick = mapped(ROOM, "alice", "opaque-b", roomLease)
        val renamed = mapped(ROOM, "renamed", "opaque-a", roomLease)
        val otherRoom = mapped(OTHER_ROOM, "alice", "opaque-a", otherLease)

        assertNotEquals(alice.actor, sameNick.actor)
        assertEquals(alice.actor, renamed.actor)
        assertNotEquals(
            Triple(alice.accountId, alice.peer, alice.actor),
            Triple(otherRoom.accountId, otherRoom.peer, otherRoom.actor),
        )
        val own = mapped(ROOM, "self", "own-opaque", roomLease)
        assertEquals(listOf(ReactionActor.MucOwn, SELF), listOf(own.actor, own.accountBareJid))
        assertEquals(Triple(FIRST.accountId, ROOM, alice.actor), Triple(alice.accountId, alice.peer, alice.actor))
    }

    @Test
    fun `live room actor rejects every missing authority branch`() {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(FIRST) }
        val lease = join(registry, ROOM)
        fun map(
            message: Message = group(ROOM, "alice", NemaOccupantIdElement("opaque", true)),
            current: SessionAttemptIdentity = FIRST,
            roomLease: RoomStableIdLease = lease,
            ownNick: String? = "self",
            live: Boolean = true,
        ) = message.toIncomingReaction(current, SELF, roomLease, registry, ownNick, live)

        val inner = group(ROOM, "alice", NemaOccupantIdElement("opaque", true))
        val raw = TrustedIncomingStanza(inner)
        requireNotNull(map(inner, live = raw.isRawLive(mamCarrier = false)))
        val carbonCarrier = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(SELF))
            .addExtension(CarbonExtension(
                CarbonExtension.Direction.received, Forwarded(inner, null),
            ))
            .build()
        val carbon = requireNotNull(carbonCarrier.toTrustedCarbonMessage(SELF))
        assertTrue(carbon.forwarded)
        assertNull(map(carbon.message, live = carbon.isRawLive(mamCarrier = false)))
        val delaylessForwarded = carbon.copy(sentAtEpochMs = null, sentTimeSource = null)
        assertNull(map(delaylessForwarded.message, live = delaylessForwarded.isRawLive(false)))
        val mamCarrier = StanzaBuilder.buildMessage()
            .from(JidCreate.entityBareFrom(SELF))
            .addExtension(NemaMamResultExtension("query", "result", inner, DelayInformation(Date(1))))
            .build()
        assertNull(map(inner, live = raw.isRawLive(
            mamCarrier.getExtension(MamResultExtension::class.java) != null,
        )))

        listOf(
            group(ROOM, "alice"),
            group(ROOM, "alice", NemaOccupantIdElement("", false)),
            group(ROOM, "alice", NemaOccupantIdElement("opaque", false)),
            group(ROOM, "alice", NemaOccupantIdElement("one", true), NemaOccupantIdElement("two", true)),
            group(OTHER_ROOM, "alice", NemaOccupantIdElement("opaque", true)),
            group(ROOM, "", NemaOccupantIdElement("opaque", true)),
        ).forEach { assertNull(map(it)) }
        assertNull(map(current = SECOND))
        assertNull(map(current = attempt(2)))
        assertNull(map(current = FIRST.copy(accountId = AccountId.require("other"))))
        assertNull(map(message = group(ROOM, "alice", NemaOccupantIdElement("opaque", true), delayed = true)))
        assertNull(map(live = false))
        assertNull(map(ownNick = null))

        val stale = lease
        join(registry, ROOM)
        assertNull(map(roomLease = stale))
        val stableOnly = join(registry, ROOM, stable = true, occupant = false)
        assertNull(map(roomLease = stableOnly))
        val occupantOnly = join(registry, ROOM, stable = false, occupant = true)
        assertNull(map(roomLease = occupantOnly))
    }

    private fun join(
        registry: RoomStableIdAuthorityRegistry,
        room: String,
        stable: Boolean = true,
        occupant: Boolean = true,
    ) = requireNotNull(registry.beginJoin(FIRST, room)).also {
        assertTrue(registry.publish(it, stable, occupant))
    }

    private fun group(
        room: String,
        nick: String,
        vararg occupants: NemaOccupantIdElement,
        delayed: Boolean = false,
    ): Message {
        val builder = StanzaBuilder.buildMessage()
            .from(if (nick.isEmpty()) JidCreate.entityBareFrom(room) else JidCreate.entityFullFrom("$room/$nick"))
            .to(JidCreate.entityFullFrom("$SELF/nema"))
            .ofType(Message.Type.groupchat)
            .addExtension(reactions())
            .addExtension(NemaMucUser().apply {
                recordItem(MUCItem(MUCAffiliation.member, JidCreate.entityBareFrom("real@example.org"), null))
            })
        occupants.forEach(builder::addExtension)
        if (delayed) builder.addExtension(DelayInformation(Date(1)))
        return builder.build()
    }

    private fun reactions() = org.jivesoftware.smack.packet.StandardExtensionElement
        .builder("reactions", "urn:xmpp:reactions:0")
        .addAttribute("id", "room-sid")
        .addElement("reaction", "👍")
        .build()

    private fun reaction(kind: MessageKind, recipient: String) = OutgoingReactionEnvelope(
        FIRST.accountId, FIRST.generation, recipient, "room-sid", listOf("🔥", "👍"), kind,
    )

    private companion object {
        const val ROOM = "room@conference.example.org"
        const val OTHER_ROOM = "other@conference.example.org"
        const val SELF = "account@example.org"
        val FIRST = attempt(1)
        val SECOND = SessionAttemptIdentity(
            FIRST.accountId, FIRST.generation, ConnectionAttempt.require(2), FIRST.epoch,
        )

        fun attempt(value: Long) = SessionAttemptIdentity(
            AccountId.require("account"), ConnectionGeneration.require(value),
            ConnectionAttempt.require(value), LifecycleEpoch.require(value),
        )
    }
}
