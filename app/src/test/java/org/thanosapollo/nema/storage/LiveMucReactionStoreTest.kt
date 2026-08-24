package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.ReactionActor

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

    @Test
    fun knownRoomTargetAppliesClosedActorsAndOrdersFullSets(): Unit = runBlocking {
        val clock = CountingFixedClock(42L)
        store = MessageStore(database, clock)
        installFacts(group("selected", sid(ROOM, "room-id")))
        clock.resetCount()
        val remote = ReactionActor.MucOccupant(" \topaque ")
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(apply(remote, emojis = listOf("👍", "❤️"), time = 10)))
        val first = MessageReactionEntity(
            ACCOUNT, ROOM, "occupant-id: \topaque ", "selected", "selected", "room-id", "👍\u001f❤️", 10, 1,
        )
        assertEquals(first, database.messageDao().messageReaction(ACCOUNT, ROOM, first.senderBareJid, "selected"))
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(apply(remote, emojis = listOf("❤️", "👍"), time = 10)))
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(apply(remote, emojis = listOf("⚠️"), time = 9)))
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(apply(ReactionActor.MucOwn, emojis = listOf("🙏"), time = 11)))
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(apply(remote, emojis = listOf("😂"), time = 12)))
        assertEquals(setOf(SELF to 1L, first.senderBareJid to 2L),
            database.messageDao().messageReactions(ACCOUNT, ROOM).map { it.senderBareJid to it.revision }.toSet())
        assertEquals("😂", requireNotNull(
            database.messageDao().messageReaction(ACCOUNT, ROOM, first.senderBareJid, "selected")).emojis)
        assertEquals(0, clock.calls)
    }

    @Test
    fun unknownInvalidAndMismatchedApplicationsStayInert(): Unit = runBlocking {
        val clock = CountingFixedClock(42L)
        suspend fun rejected(value: IncomingReactionApply) = assertFalse(assertRejectedWithoutMutation(clock) {
            store.applyIncomingReaction(value) != ReactionApplyOutcome.IGNORED
        })
        store = MessageStore(database, clock)
        rejected(apply(ReactionActor.MucOccupant("opaque"), target = "unknown"))
        installFacts(group("selected", sid(ROOM, "room-id")))
        listOf(
            apply(ReactionActor.Direct("occupant-id:opaque")),
            apply(ReactionActor.MucOwn, kind = MessageKind.CHAT),
            apply(ReactionActor.MucOccupant("opaque"), kind = MessageKind.CHAT),
            apply(ReactionActor.MucOwn, kind = MessageKind.NORMAL),
            apply(ReactionActor.MucOwn, kind = MessageKind.HEADLINE),
            apply(ReactionActor.MucOwn).copy(accountBareJid = "foreign@example.org"),
            apply(ReactionActor.MucOwn).copy(accountId = OTHER_ACCOUNT),
            apply(ReactionActor.MucOwn).copy(peerJid = OTHER_PEER),
            apply(ReactionActor.MucOwn, target = "missing"),
        ).forEach { rejected(it) }
        assertTrue(database.messageDao().messageReactions(ACCOUNT, ROOM).none { it.localMessageId == null })
    }

    @Test
    fun roomTargetAuthorityRejectsIncomingApplication(): Unit = runBlocking {
        val cases = listOf(
            "missing" to emptyArray(),
            "foreign" to arrayOf(sid(OTHER_PEER, "room-id")),
            "mixed" to arrayOf(sid(ROOM, "room-id"), sid(OTHER_PEER, "foreign")),
            "duplicate" to arrayOf(sid(ROOM, "room-id"), sid(ROOM, "other")),
        )
        for ((label, aliases) in cases) {
            resetStore()
            installFacts(group("selected", *aliases))
            assertEquals(label, ReactionApplyOutcome.IGNORED,
                assertReadOnlyState { store.applyIncomingReaction(apply(ReactionActor.MucOwn)) })
        }
        resetStore()
        installFacts(group("selected"))
        insertAliasFact(aliasEntity("selected", ROOM, "room-id", IdentityAliasStatus.QUARANTINED))
        assertEquals("quarantined", ReactionApplyOutcome.IGNORED,
            assertReadOnlyState { store.applyIncomingReaction(apply(ReactionActor.MucOwn)) })
    }

    @Test
    fun equalOccupantIdsRemainScopedByAccountAndRoom(): Unit = runBlocking {
        val otherBare = "other-account@example.org"
        database.accountDao().upsert(AccountEntity(
            OTHER_ACCOUNT, otherBare, OTHER_ACCOUNT, null, "example.org", null, null,
        ))
        installFacts(
            group("selected", sid(ROOM, "one")),
            group("other-room", sid(OTHER_PEER, "two")).copy(peerJid = OTHER_PEER, senderJid = "$OTHER_PEER/alice"),
            group("selected", sid(ROOM, "three")).copy(accountId = OTHER_ACCOUNT),
        )
        val actor = ReactionActor.MucOccupant("same")
        store.applyIncomingReaction(apply(actor, target = "one"))
        store.applyIncomingReaction(apply(actor, target = "two").copy(peerJid = OTHER_PEER))
        store.applyIncomingReaction(apply(actor, target = "three").copy(
            accountId = OTHER_ACCOUNT, accountBareJid = otherBare))
        val dao = database.messageDao()
        assertEquals(listOf(1, 1, 1), listOf(dao.messageReactions(ACCOUNT, ROOM).size,
            dao.messageReactions(ACCOUNT, OTHER_PEER).size, dao.messageReactions(OTHER_ACCOUNT, ROOM).size))
    }

    private fun group(localId: String, vararg aliases: TrustedIdentityAlias) =
        incoming(localId, *aliases).copy(peerJid = ROOM, senderJid = "$ROOM/alice", messageKind = MessageKind.GROUPCHAT)

    private fun sid(authority: String, value: String) =
        TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, authority, value)

    private fun aliasEntity(localId: String, authority: String, value: String, status: IdentityAliasStatus) =
        TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.STANZA_ID, authority, value, localId, status)

    private fun apply(
        actor: ReactionActor,
        target: String = "room-id",
        emojis: List<String> = listOf("👍"),
        time: Long = 1,
        kind: MessageKind = MessageKind.GROUPCHAT,
    ) = IncomingReactionApply(ACCOUNT, SELF, ROOM, ROOM, target, emojis, time, actor = actor, messageKind = kind)
}
