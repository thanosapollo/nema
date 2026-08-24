package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class LiveMucReactionCommitTest : ReactionStoreTestFixture() {
    @Test
    fun ordinaryRoomIdCommits() = commitRoomSid("ordinary", "room-id")

    @Test
    fun xmlSOnlyRoomIdCommits() = commitRoomSid("XML S", " \t\n\r")

    @Test
    fun emSpaceRoomIdCommits() = commitRoomSid("U+2003", "\u2003")

    @Test
    fun leadingNulRoomIdCommits() = commitRoomSid("leading NUL", "\u0000x")

    private fun commitRoomSid(label: String, wire: String): Unit = runBlocking {
        resetStore()
        val clock = CountingFixedClock(42L)
        store = MessageStore(database, clock)
        installFacts(group("selected", sid(wire)))
        clock.resetCount()

        val command = requireNotNull(assertReadOnlyState {
            store.prepareOutgoingReaction(ACCOUNT, ROOM, "selected", SELF, "👍")
        })
        assertEquals("$label preparation clock", 0, clock.calls)
        assertTrue(label, store.commitOutgoingReaction(command))
        assertEquals("$label commit clock", 1, clock.calls)
        assertEquals(
            listOf(MessageReactionEntity(
                ACCOUNT, ROOM, SELF, "selected", "selected", wire, "👍", 42L, 1L,
            )),
            database.messageDao().messageReactions(ACCOUNT, ROOM),
        )
    }

    @Test
    fun emptyDurableRoomSidNeverPreparesOrCommits() = runBlocking {
        val clock = CountingFixedClock(42L)
        store = MessageStore(database, clock)
        installFacts(group("selected"))
        insertAliasFact(TrustedIdentityAliasEntity(
            ACCOUNT, IdentityAliasKind.STANZA_ID, ROOM, "", "selected", IdentityAliasStatus.TRUSTED,
        ))
        clock.resetCount()

        assertNull(assertReadOnlyState { store.resolveReactionTarget(ACCOUNT, ROOM, "selected") })
        assertNull(assertReadOnlyState {
            store.prepareOutgoingReaction(ACCOUNT, ROOM, "selected", SELF, "👍")
        })
        assertEquals(0, clock.calls)
        assertTrue(database.messageDao().messageReactions(ACCOUNT, ROOM).isEmpty())
    }

    @Test
    fun attachedUnsupportedOwnerKindsRejectBeforeMutation() = runBlocking {
        for (kind in listOf(MessageKind.NORMAL, MessageKind.HEADLINE)) {
            resetStore()
            installFacts(incoming("owner").copy(messageKind = kind))
            val dao = database.messageDao()
            val owner = requireNotNull(dao.message(ACCOUNT, "owner"))
            val candidate = MessageReactionEntity(
                ACCOUNT, PEER, SELF, "owner", "owner", "wire-id", "👍", 42L, 1L,
            )
            assertEquals("$kind exact owner peer", candidate.peerJid, owner.peerJid)
            assertEquals("$kind exact owner kind", kind, owner.messageKind)
            assertEquals(
                "$kind exact target key",
                reactionTargetKey(candidate.localMessageId, candidate.wireTargetId),
                candidate.targetKey,
            )
            val before = state()

            assertThrows("$kind durable rejection", IllegalStateException::class.java) {
                runBlocking { dao.writeReactionFullSet(candidate) }
            }
            assertEquals("$kind state and total_changes", before, state())
        }
    }

    private fun group(localId: String, vararg aliases: TrustedIdentityAlias) =
        incoming(localId, *aliases).copy(
            peerJid = ROOM,
            senderJid = "$ROOM/alice",
            messageKind = MessageKind.GROUPCHAT,
        )

    private fun sid(value: String) = TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, ROOM, value)
}
