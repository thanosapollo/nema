package org.thanosapollo.nema.storage

import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.thread.MessageKind

class MucCorrectionFactsTest {
    private val root = MessageEntity("a", "root", "room@example.org", "room@example.org/nick",
        MessageDirection.INBOUND, MessageKind.GROUPCHAT, null, null, "text", 1, null,
        mucMessageId = "wire-root", mucClaimState = MucClaimState.NONE,
        mucOccupantId = "actor", mucOccupantEvidence = MucOccupantEvidence.LIVE_ROOM,
        mucPayloadState = MucPayloadState.PLAIN)
    private val edit = root.copy(localMessageId = "edit", localSequence = 2, mucMessageId = "wire-edit",
        mucReplaceId = "wire-root", mucClaimState = MucClaimState.VALID)

    @Test
    fun bothSidesRequirePositivePlaintextAndRootIsPositivelyNotACorrection() {
        assertTrue(mucCorrectionFactsPermit(root, edit))
        val unsupported: List<(MessageEntity) -> MessageEntity> = listOf(
            { it.copy(body = " ") }, { it.copy(messageKind = MessageKind.CHAT) },
            { it.copy(mucPayloadState = MucPayloadState.UNKNOWN) },
            { it.copy(mucPayloadState = MucPayloadState.UNSUPPORTED) },
            { it.copy(attachmentUrl = "url") }, { it.copy(attachmentName = "name") },
            { it.copy(attachmentMime = "mime") }, { it.copy(attachmentSize = 1) },
            { it.copy(replyToId = "reply") }, { it.copy(replyToJid = "sender") },
            { it.copy(replyFallbackBody = "quote") },
        )
        for (change in unsupported) {
            assertFalse(mucCorrectionFactsPermit(change(root), edit))
            assertFalse(mucCorrectionFactsPermit(root, change(edit)))
        }
        for (state in MucClaimState.entries) {
            assertEquals(state == MucClaimState.NONE, mucCorrectionFactsPermit(root.copy(mucClaimState = state), edit))
            assertEquals(state == MucClaimState.VALID, mucCorrectionFactsPermit(root, edit.copy(mucClaimState = state)))
        }
        for (invalid in listOf(root.copy(mucReplaceId = "earlier"), root.copy(replaceId = "earlier"),
            root.copy(correctionTargetMessageId = "earlier"), root.copy(mucMessageId = null),
            root.copy(mucMessageId = "other"))) assertFalse(mucCorrectionFactsPermit(invalid, edit))
    }

    @Test
    fun exactRoomActorAndLineageAreNecessaryNotNickOrOccupantAlone() {
        for (state in MucOccupantEvidence.entries) {
            assertEquals(state in setOf(MucOccupantEvidence.LIVE_ROOM, MucOccupantEvidence.ROOM_MAM, MucOccupantEvidence.BOTH),
                mucCorrectionFactsPermit(root, edit.copy(mucOccupantEvidence = state)))
        }
        for (invalid in listOf(edit.copy(accountId = "b"), edit.copy(peerJid = "other@example.org"),
            edit.copy(senderJid = "room@example.org/renamed"), edit.copy(mucOccupantId = "other"),
            edit.copy(mucOccupantId = null), edit.copy(mucOccupantId = ""),
            edit.copy(direction = MessageDirection.OUTBOUND), edit.copy(threadId = "thread"),
            edit.copy(parentThreadId = "parent"), edit.copy(localMessageId = "root"),
            edit.copy(mucReplaceId = null), edit.copy(mucReplaceId = ""))) {
            assertFalse(mucCorrectionFactsPermit(root, invalid))
        }
        assertFalse(mucCorrectionFactsPermit(root.copy(senderJid = root.peerJid), edit.copy(senderJid = root.peerJid)))
    }

    @Test
    fun permutationsUseUniversalDominatorNotSortOrTransitiveEdges() {
        val a = edit.copy(localMessageId = "a", localSequence = 2, mucLiveOrderEpoch = "attempt")
        val b = a.copy(localMessageId = "b", localSequence = 3)
        val c = a.copy(localMessageId = "c", localSequence = 4)
        fun positions(vararg rows: MessageEntity) = rows.mapIndexed { index, row ->
            row.localMessageId to ArchiveMessagePositionEntity("a", root.peerJid, root.peerJid, index.toLong(), row.localMessageId)
        }.toMap()
        for (order in listOf(listOf(a, b, c), listOf(a, c, b), listOf(b, a, c),
            listOf(b, c, a), listOf(c, a, b), listOf(c, b, a))) {
            assertEquals(c, selectMucCorrection(order, emptyMap()))
            assertNull(selectMucCorrection(order, positions(c, a))) // live A<B<C but archive C<A
            assertEquals(b, selectMucCorrection(order, positions(c, a, b)))
            assertNull(selectMucCorrection(order.map { it.copy(mucLiveOrderEpoch = null) }, emptyMap()))
        }
        assertEquals(a, selectMucCorrection(listOf(a), emptyMap()))
        assertNull(selectMucCorrection(emptyList(), emptyMap()))
        assertFalse(mucProvenLater(c.copy(mucLiveOrderEpoch = "new"), a, emptyMap()))
        assertFalse(mucProvenLater(c.copy(mucLiveOrderEpoch = null, archiveOrdinal = 99), a, emptyMap()))
        val wrongScope = positions(a, c).mapValues { it.value.copy(archiveScope = "other") }
        assertFalse(mucProvenLater(c.copy(mucLiveOrderEpoch = null), a, wrongScope))
        assertFalse(mucProvenLater(c, a, positions(a, c).mapValues { it.value.copy(archiveOrdinal = 1) }))
    }
}
