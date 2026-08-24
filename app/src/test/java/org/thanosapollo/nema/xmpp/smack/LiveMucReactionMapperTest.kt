package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
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

    private fun reaction(kind: MessageKind, recipient: String) = OutgoingReactionEnvelope(
        FIRST.accountId, FIRST.generation, recipient, "room-sid", listOf("🔥", "👍"), kind,
    )

    private companion object {
        const val ROOM = "room@conference.example.org"
        const val OTHER_ROOM = "other@conference.example.org"
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
