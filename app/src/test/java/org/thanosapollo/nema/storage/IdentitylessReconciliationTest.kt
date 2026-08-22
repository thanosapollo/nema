package org.thanosapollo.nema.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

class IdentitylessReconciliationTest {
    @Test
    fun repairPlanFindsUniqueComponentsAndCountsAmbiguity() {
        val plan = requireNotNull(
            identitylessRepairPlan(
                listOf(
                    live(),
                    mam(),
                    live("ambiguous-live", 100_000),
                    mam("ambiguous-mam-1", 100_000, 8),
                    mam("ambiguous-mam-2", 100_001, 9),
                    live("second-live", 200_000),
                    mam("second-mam", 200_000, 10),
                ),
            ),
        )

        assertEquals(
            listOf(
                IdentitylessReconciliationPair("live", "mam"),
                IdentitylessReconciliationPair("second-live", "second-mam"),
            ),
            plan.pairs,
        )
        assertEquals(1, plan.skippedComponents)
    }

    @Test
    fun repairPlanCountsOneTransitiveAmbiguousComponent() {
        val plan = requireNotNull(
            identitylessRepairPlan(
                listOf(
                    mam(sentAt = 0),
                    live(observedAt = 30_000),
                    mam("mam-2", 60_000, 8),
                    live("live-2", 90_000),
                ),
            ),
        )

        assertEquals(emptyList<IdentitylessReconciliationPair>(), plan.pairs)
        assertEquals(1, plan.skippedComponents)
    }

    @Test
    fun repairPlanFailsClosedOnDuplicateIdsAndIgnoresIneligibleSingletons() {
        assertNull(identitylessRepairPlan(listOf(live(), mam(), mam())))
        assertEquals(
            IdentitylessRepairPlan(emptyList(), 0),
            identitylessRepairPlan(
                listOf(
                    live().copy(hasOutbox = true),
                    mam().copy(hasConflict = true),
                    live("unmatched", 100_000),
                ),
            ),
        )
    }

    @Test
    fun uniquePairRequiresBothPersistedClosureGates() {
        val candidates = listOf(live(), mam())
        assertNull(closedIdentitylessPair("mam", candidates, closure(31_000), 31_001))
        assertNull(closedIdentitylessPair("mam", candidates, closure(31_001), 31_000))
        assertEquals(
            IdentitylessReconciliationPair("live", "mam"),
            closedIdentitylessPair("mam", candidates, closure(31_001), 31_001),
        )
        assertEquals(
            IdentitylessReconciliationPair("live", "mam"),
            closedIdentitylessPair("mam", candidates, closure(null, complete = true), 31_001),
        )
        assertNull(
            closedIdentitylessPair(
                "mam",
                listOf(live(observedAt = 31_001), mam()),
                closure(null, complete = true),
                31_001,
            ),
        )
    }

    @Test
    fun incompleteArchiveFrontierClosesBeyondTheLiveSide() {
        val candidates = listOf(live(observedAt = 30_000), mam(sentAt = 0))
        assertNull(closedIdentitylessPair("mam", candidates, closure(30_001), 30_001))
        assertEquals(
            IdentitylessReconciliationPair("live", "mam"),
            closedIdentitylessPair("mam", candidates, closure(60_001), 30_001),
        )
        assertNull(
            closedIdentitylessPair(
                "mam",
                candidates + mam("future", 60_000, 8),
                closure(60_001),
                30_001,
            ),
        )
    }

    @Test
    fun eligibilityAndComponentCardinalityFailClosed() {
        val live = live()
        val mam = mam()
        val bad = listOf(
            live.copy(message = live.message.copy(sentTimeSource = MessageTimeSource.MAM)),
            live.copy(message = live.message.copy(liveDeliveryObserved = false)),
            live.copy(message = live.message.copy(reconciliationObservedAtMs = null)),
            live.copy(message = live.message.copy(archiveOrdinal = 1)),
            live.copy(aliases = listOf(alias("live", IdentityAliasKind.STANZA_ID, "sid"))),
            live.copy(positions = listOf(position("live", 1))),
            live.copy(hasOutbox = true),
            live.copy(hasConflict = true),
            mam.copy(message = mam.message.copy(sentTimeSource = MessageTimeSource.LOCAL)),
            mam.copy(message = mam.message.copy(liveDeliveryObserved = true)),
            mam.copy(message = mam.message.copy(sentAtEpochMs = -1)),
            mam.copy(message = mam.message.copy(archiveOrdinal = null)),
            mam.copy(aliases = emptyList()),
            mam.copy(aliases = mam.aliases + alias("mam", IdentityAliasKind.STANZA_ID, "sid")),
            mam.copy(aliases = listOf(mam.aliases.single().copy(authority = "wrong"))),
            mam.copy(positions = emptyList()),
            mam.copy(positions = listOf(position("mam", 8))),
            mam.copy(hasOutbox = true),
            mam.copy(hasConflict = true),
        )
        bad.forEach { changed ->
            val candidates = if (changed.message.localMessageId == "live") {
                listOf(changed, mam)
            } else {
                listOf(live, changed)
            }
            assertNull(closedIdentitylessPair("mam", candidates, closure(null, complete = true), 31_001))
        }
        listOf(
            closure(null, complete = true, accountId = "other"),
            closure(null, complete = true, archiveAuthority = "other"),
            closure(null, complete = true, archiveScope = "other"),
        ).forEach { foreignClosure ->
            assertNull(closedIdentitylessPair("mam", listOf(live, mam), foreignClosure, 31_001))
        }

        val live2 = live("live-2", 1_001)
        val mam2 = mam("mam-2", 1_001, 8)
        assertNull(closedIdentitylessPair("mam", listOf(live, live2, mam), closure(null, complete = true), 31_001))
        assertNull(closedIdentitylessPair("mam", listOf(live, mam, mam2), closure(null, complete = true), 31_001))
        assertNull(closedIdentitylessPair("mam", listOf(live, live2, mam, mam2), closure(null, complete = true), 31_001))
    }

    @Test
    fun fingerprintFieldsAreExactWhileExcludedFieldsDoNotBlock() {
        val base = mam()
        val changedMessages = listOf(
            base.message.copy(peerJid = "other@example.org"),
            base.message.copy(senderJid = "other@example.org"),
            base.message.copy(messageKind = MessageKind.GROUPCHAT),
            base.message.copy(threadId = "other-thread"),
            base.message.copy(parentThreadId = "other-parent"),
            base.message.copy(body = "other"),
            base.message.copy(attachmentUrl = null),
            base.message.copy(attachmentName = null),
            base.message.copy(attachmentMime = null),
            base.message.copy(attachmentSize = null),
            base.message.copy(replyToId = null),
            base.message.copy(replyToJid = null),
            base.message.copy(replyFallbackBody = null),
            base.message.copy(markable = false),
            base.message.copy(markerTargetId = null),
            base.message.copy(replaceId = null),
            base.message.copy(correctionTargetMessageId = null),
        )
        changedMessages.forEach { changed ->
            assertNull(
                closedIdentitylessPair(
                    "mam",
                    listOf(live(), base.copy(message = changed)),
                    closure(null, complete = true),
                    31_001,
                ),
            )
        }
        val otherAccountLive = live().let { it.copy(message = it.message.copy(accountId = "other")) }
        assertNull(closedIdentitylessPair("mam", listOf(otherAccountLive, base), closure(null, true), 31_001))
        val nullLive = live().let { it.copy(message = it.message.copy(attachmentName = null)) }
        val emptyMam = base.let { it.copy(message = it.message.copy(attachmentName = "")) }
        assertNull(closedIdentitylessPair("mam", listOf(nullLive, emptyMam), closure(null, true), 31_001))

        val excludedLive = live().let {
            it.copy(message = it.message.copy(localSequence = Long.MAX_VALUE, unreadEligible = false))
        }
        val excludedMam = base.let {
            it.copy(message = it.message.copy(localSequence = 0, unreadEligible = true))
        }
        assertEquals(
            IdentitylessReconciliationPair("live", "mam"),
            closedIdentitylessPair("mam", listOf(excludedLive, excludedMam), closure(null, true), 31_001),
        )
    }

    @Test
    fun metadataSeedAndTransitiveComponentsFailClosed() {
        val live = live()
        val mam = mam()
        val alias = mam.aliases.single()
        val position = mam.positions.single()
        val malformed = listOf(
            mam.copy(message = mam.message.copy(reconciliationObservedAtMs = 1)),
            mam.copy(aliases = listOf(alias.copy(accountId = "other"))),
            mam.copy(aliases = listOf(alias.copy(messageId = "other"))),
            mam.copy(aliases = listOf(alias.copy(kind = IdentityAliasKind.STANZA_ID))),
            mam.copy(aliases = listOf(alias.copy(status = IdentityAliasStatus.QUARANTINED))),
            mam.copy(positions = listOf(position.copy(accountId = "other"))),
            mam.copy(positions = listOf(position.copy(messageId = "other"))),
        )
        malformed.forEach { changed ->
            assertNull(closedIdentitylessPair("mam", listOf(live, changed), closure(null, true), 31_001))
        }
        assertNull(closedIdentitylessPair("missing", listOf(live, mam), closure(null, true), 31_001))
        assertNull(closedIdentitylessPair("live", listOf(live, mam), closure(null, true), 31_001))
        assertNull(closedIdentitylessPair("mam", listOf(live, mam, mam), closure(null, true), 31_001))
        assertNull(
            closedIdentitylessPair(
                "mam",
                listOf(
                    mam(sentAt = 0),
                    live(observedAt = 30_000),
                    mam("mam-2", 60_000, 8),
                    live("live-2", 90_000),
                ),
                closure(null, true),
                30_001,
            ),
        )
    }

    @Test
    fun longBoundariesRemainFailClosedWithoutOverflow() {
        assertNull(
            closedIdentitylessPair(
                "mam",
                listOf(live(observedAt = Long.MAX_VALUE), mam(sentAt = Long.MAX_VALUE - 30_000)),
                closure(null, true),
                Long.MAX_VALUE,
            ),
        )
        assertEquals(
            IdentitylessReconciliationPair("live", "mam"),
            closedIdentitylessPair(
                "mam",
                listOf(live(observedAt = Long.MAX_VALUE - 1), mam(sentAt = Long.MAX_VALUE - 30_001)),
                closure(null, true),
                Long.MAX_VALUE,
            ),
        )
        assertNull(
            closedIdentitylessPair(
                "mam",
                listOf(live(observedAt = 0), mam(sentAt = 0)),
                closure(Long.MIN_VALUE),
                31_001,
            ),
        )
    }

    private fun live(id: String = "live", observedAt: Long = 1_000) =
        IdentitylessReconciliationCandidate(
            message = message(id).copy(
                sentTimeSource = MessageTimeSource.LOCAL,
                reconciliationObservedAtMs = observedAt,
                liveDeliveryObserved = true,
                archiveOrdinal = null,
            ),
            aliases = emptyList(),
            positions = emptyList(),
        )

    private fun mam(id: String = "mam", sentAt: Long = 1_000, ordinal: Long = 7) =
        IdentitylessReconciliationCandidate(
            message = message(id).copy(
                sentAtEpochMs = sentAt,
                sentTimeSource = MessageTimeSource.MAM,
                archiveOrdinal = ordinal,
            ),
            aliases = listOf(alias(id, IdentityAliasKind.MAM_RESULT, "result-$id")),
            positions = listOf(position(id, ordinal)),
        )

    private fun message(id: String) = MessageEntity(
        accountId = "account",
        localMessageId = id,
        peerJid = "peer@example.org",
        senderJid = "peer@example.org",
        direction = MessageDirection.INBOUND,
        messageKind = MessageKind.CHAT,
        threadId = "thread",
        parentThreadId = "parent",
        body = "body",
        localSequence = 1,
        archiveOrdinal = 7,
        sentAtEpochMs = 1_000,
        sentTimeSource = MessageTimeSource.MAM,
        attachmentUrl = "https://example.org/file",
        attachmentName = "file",
        attachmentMime = "text/plain",
        attachmentSize = 4,
        replyToId = "reply",
        replyToJid = "peer@example.org",
        replyFallbackBody = "fallback",
        markable = true,
        markerTargetId = "marker",
        replaceId = "replace",
        correctionTargetMessageId = "target",
        liveDeliveryObserved = false,
    )

    private fun alias(id: String, kind: IdentityAliasKind, value: String) =
        TrustedIdentityAliasEntity(
            accountId = "account",
            kind = kind,
            authority = ArchiveCursorKey("account", "archive@example.org", "ACCOUNT").aliasAuthority(),
            value = value,
            messageId = id,
            status = IdentityAliasStatus.TRUSTED,
        )

    private fun position(id: String, ordinal: Long) = ArchiveMessagePositionEntity(
        accountId = "account",
        archiveAuthority = "archive@example.org",
        archiveScope = "ACCOUNT",
        archiveOrdinal = ordinal,
        messageId = id,
    )

    private fun closure(
        observedThroughMs: Long?,
        complete: Boolean = false,
        accountId: String = "account",
        archiveAuthority: String = "archive@example.org",
        archiveScope: String = "ACCOUNT",
    ) = IdentitylessArchiveClosure(
        ArchiveCursorKey(accountId, archiveAuthority, archiveScope),
        observedThroughMs,
        complete,
    )
}
