package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class LiveMucReactionProjectionTest : ReactionStoreTestFixture() {
    @Test
    fun soleRoomSidProjectsEveryNonemptyOpaqueValue() = runBlocking {
        installFacts(
            group("ordinary", sid(ROOM, "room-id")),
            group("xml-s", sid(ROOM, " \t\n\r")),
            group("em-space", sid(ROOM, "\u2003")),
            group("leading-nul", sid(ROOM, "\u0000x")),
        )

        assertEquals(
            mapOf(
                "ordinary" to "room-id",
                "xml-s" to " \t\n\r",
                "em-space" to "\u2003",
                "leading-nul" to "\u0000x",
            ),
            timelineTargets(),
        )
    }

    @Test
    fun unauthorizedAliasShapesProjectNull() = runBlocking {
        installFacts(
            group("missing"),
            group("foreign", sid(OTHER_PEER, "foreign-id")),
            group("duplicate", sid(ROOM, "first"), sid(ROOM, "second")),
            group("non-sid", TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ROOM, "message-id")),
            group("quarantine-owner", sid(ROOM, "quarantined-id")),
            group("quarantined", sid(ROOM, "quarantined-id")).copy(body = "conflict"),
        )
        assertEquals(
            IdentityAliasStatus.QUARANTINED,
            database.messageDao().identityAlias(ACCOUNT, IdentityAliasKind.STANZA_ID, ROOM, "quarantined-id")?.status,
        )

        assertEquals(
            setOf("missing", "foreign", "duplicate", "non-sid", "quarantine-owner", "quarantined"),
            timelineTargets().filterValues { it == null }.keys,
        )
    }

    @Test
    fun mixedRoomAndForeignTrustedSidsProjectNull() = runBlocking {
        installFacts(group("mixed", sid(ROOM, "room-id"), sid(OTHER_PEER, "foreign-id")))

        assertNull(timelineTargets().getValue("mixed"))
    }

    @Test
    fun emptyDurableRoomSidProjectsNull() = runBlocking {
        installFacts(group("empty"))
        val empty = aliasEntity("empty", ROOM, "", IdentityAliasStatus.TRUSTED)
        insertAliasFact(empty)

        assertEquals(empty, database.messageDao().identityAlias(ACCOUNT, empty.kind, ROOM, ""))
        assertNull(timelineTargets().getValue("empty"))
    }

    private fun group(localId: String, vararg aliases: TrustedIdentityAlias) =
        incoming(localId, *aliases).copy(
            peerJid = ROOM,
            senderJid = "$ROOM/alice",
            messageKind = MessageKind.GROUPCHAT,
        )

    private fun sid(authority: String, value: String) =
        TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, authority, value)

    private fun aliasEntity(localId: String, authority: String, value: String, status: IdentityAliasStatus) =
        TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.STANZA_ID, authority, value, localId, status)

    private suspend fun timelineTargets() = database.messageDao()
        .observeDirectTimeline(ACCOUNT, ROOM)
        .first()
        .associate { it.localMessageId to it.replyReferenceId }
}
