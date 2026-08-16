package org.thanosapollo.nema.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.IngestionResult

class IncomingNotifyTest {
    private val inserted = IngestionResult("id", 0, identityConflict = false, inserted = true)
    private val merged = IngestionResult("id", 1, identityConflict = false, inserted = false)
    private val conflict = IngestionResult("id", 0, identityConflict = true, inserted = true)

    @Test
    fun liveInsertedInboundNotifiesEvenBeforeArchiveReadyWhenChatIsNotOpen() {
        assertTrue(
            shouldNotifyInsertedInbound(
                result = inserted,
                inbound = true,
                groupChat = false,
                visiblePeer = null,
                peerJid = "alice@example.org",
            ),
        )
    }

    @Test
    fun mergeReplayConflictGroupchatOrVisibleChatDoNotNotify() {
        assertFalse(shouldNotifyInsertedInbound(merged, true, false, null, "alice@example.org"))
        assertFalse(shouldNotifyInsertedInbound(conflict, true, false, null, "alice@example.org"))
        assertFalse(shouldNotifyInsertedInbound(inserted, false, false, null, "alice@example.org"))
        assertFalse(shouldNotifyInsertedInbound(inserted, true, true, null, "room@conference.example.org"))
        assertFalse(
            shouldNotifyInsertedInbound(inserted, true, false, "alice@example.org", "alice@example.org"),
        )
    }

    @Test
    fun afterCatchUpNotifiesBeforeReadyWhileHistoryPagesStaySilent() {
        assertTrue(
            shouldNotifyInsertedInbound(
                inserted, true, false, null, "alice@example.org", ArchiveDirection.AFTER,
            ),
        )
        assertFalse(
            shouldNotifyInsertedInbound(
                inserted, true, false, "alice@example.org", "alice@example.org", ArchiveDirection.AFTER,
            ),
        )
        assertFalse(
            shouldNotifyInsertedInbound(
                inserted, true, false, null, "alice@example.org", ArchiveDirection.BOOTSTRAP,
            ),
        )
        assertFalse(
            shouldNotifyInsertedInbound(
                inserted, true, false, null, "alice@example.org", ArchiveDirection.BEFORE,
            ),
        )
    }
}
