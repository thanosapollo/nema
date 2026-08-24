package org.thanosapollo.nema.storage

import android.app.Application
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
internal class LiveMucReactionStoreTest : ReactionStoreTestFixture() {
    @Test
    fun exactRoomTargetPreparesReadOnlyCommand(): Unit = runBlocking {
        val clock = CountingFixedClock(42L)
        store = MessageStore(database, clock)
        installFacts(group("selected", sid(ROOM, "room-id")))

        assertEquals(
            ReactionTarget(ACCOUNT, ROOM, "selected", "room-id", MessageKind.GROUPCHAT),
            assertReadOnlyState { store.resolveReactionTarget(ACCOUNT, ROOM, "selected") },
        )
        val command = requireNotNull(assertReadOnlyState {
            store.prepareOutgoingReaction(ACCOUNT, ROOM, "selected", SELF, "👍")
        })
        assertEquals(
            listOf(ACCOUNT, ROOM, "selected", "room-id", MessageKind.GROUPCHAT, listOf("👍")),
            listOf(command.accountId, command.peerJid, command.canonicalLocalMessageId,
                command.wireTargetId, command.messageKind, command.emojis),
        )
    }

    @Test
    fun opaqueRoomSidsResolveAndPrepareButEmptyDurableSidRejects() = runBlocking {
        for ((label, value) in listOf(
            "XML S" to " \t\n\r",
            "U+2003" to "\u2003",
            "leading NUL" to "\u0000x",
        )) {
            resetStore()
            installFacts(group("selected", sid(ROOM, value)))
            assertEquals(label, value, assertReadOnlyState {
                store.resolveReactionTarget(ACCOUNT, ROOM, "selected")?.wireTargetId
            })
            assertEquals(label, value, assertReadOnlyState {
                store.prepareOutgoingReaction(ACCOUNT, ROOM, "selected", SELF, "👍")?.wireTargetId
            })
        }

        resetStore()
        installFacts(group("selected"))
        val empty = aliasEntity("selected", ROOM, "", IdentityAliasStatus.TRUSTED)
        insertAliasFact(empty)
        assertEquals(empty, database.messageDao().identityAlias(ACCOUNT, empty.kind, ROOM, ""))
        assertNull("empty durable resolve", assertReadOnlyState {
            store.resolveReactionTarget(ACCOUNT, ROOM, "selected")
        })
        assertNull("empty durable prepare", assertReadOnlyState {
            store.prepareOutgoingReaction(ACCOUNT, ROOM, "selected", SELF, "👍")
        })
    }

    @Test
    fun roomTargetRejectionMatrixNamesEveryAuthorityBranch() = runBlocking {
        suspend fun rejected(
            label: String,
            account: String = ACCOUNT,
            peer: String = ROOM,
            localId: String = "selected",
        ) = assertNull(label, assertReadOnlyState { store.resolveReactionTarget(account, peer, localId) })
        suspend fun baseline(vararg aliases: TrustedIdentityAlias) {
            resetStore()
            installFacts(group("selected", *aliases))
        }

        baseline()
        rejected("missing SID")
        baseline(sid(ROOM, "one"), sid(ROOM, "two"))
        rejected("duplicate SID")
        baseline()
        insertAliasFact(aliasEntity("selected", ROOM, "quarantined", IdentityAliasStatus.QUARANTINED))
        rejected("quarantined SID")
        baseline(sid(OTHER_PEER, "foreign"))
        rejected("foreign SID")
        for (kind in listOf(IdentityAliasKind.MESSAGE_ID, IdentityAliasKind.ORIGIN_ID, IdentityAliasKind.MAM_RESULT)) {
            baseline(TrustedIdentityAlias(kind, ROOM, kind.name))
            rejected(kind.name)
        }
        baseline(sid(ROOM, "room-id"))
        val dao = database.messageDao()
        val selected = requireNotNull(dao.message(ACCOUNT, "selected"))
        dao.updateMessage(selected.copy(messageKind = MessageKind.NORMAL))
        rejected("NORMAL wrong kind")
        baseline(sid(ROOM, "room-id"))
        rejected("wrong account", OTHER_ACCOUNT)
        rejected("wrong peer", peer = OTHER_PEER)
        rejected("missing selected", account = ACCOUNT, peer = ROOM, localId = "missing")
        for ((label, mutate) in listOf<Pair<String, (MessageEntity) -> MessageEntity>>(
            "replaceId" to { it.copy(replaceId = "old") },
            "correctionTargetMessageId" to { it.copy(correctionTargetMessageId = "old") },
        )) {
            baseline(sid(ROOM, "room-id"))
            val row = requireNotNull(database.messageDao().message(ACCOUNT, "selected"))
            database.messageDao().updateMessage(mutate(row))
            rejected(label)
        }
    }

    private fun group(localId: String, vararg aliases: TrustedIdentityAlias) =
        incoming(localId, *aliases).copy(peerJid = ROOM, senderJid = "$ROOM/alice", messageKind = MessageKind.GROUPCHAT)

    private fun sid(authority: String, value: String) =
        TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, authority, value)

    private fun aliasEntity(localId: String, authority: String, value: String, status: IdentityAliasStatus) =
        TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.STANZA_ID, authority, value, localId, status)
}
