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
    fun liveInsertedInboundNotifiesWhenArchiveIsReadyAndChatIsNotOpen() {
        assertTrue(
            shouldNotifyInsertedInbound(
                result = inserted,
                inbound = true,
                groupChat = false,
                archiveReady = true,
                visiblePeer = null,
                peerJid = "alice@example.org",
            ),
        )
    }

    @Test
    fun mergeReplayConflictGroupchatUnreadyOrVisibleChatDoNotNotify() {
        assertFalse(shouldNotifyInsertedInbound(merged, true, false, true, null, "alice@example.org"))
        assertFalse(shouldNotifyInsertedInbound(conflict, true, false, true, null, "alice@example.org"))
        assertFalse(shouldNotifyInsertedInbound(inserted, false, false, true, null, "alice@example.org"))
        assertFalse(shouldNotifyInsertedInbound(inserted, true, true, true, null, "room@conference.example.org"))
        assertFalse(shouldNotifyInsertedInbound(inserted, true, false, false, null, "alice@example.org"))
        assertFalse(
            shouldNotifyInsertedInbound(inserted, true, false, true, "alice@example.org", "alice@example.org"),
        )
    }

    @Test
    fun archivePagesNotifyOnlyAfterCatchUpNotBootstrapOrBefore() {
        assertTrue(
            shouldNotifyInsertedInbound(
                inserted, true, false, true, null, "alice@example.org", ArchiveDirection.AFTER,
            ),
        )
        assertFalse(
            shouldNotifyInsertedInbound(
                inserted, true, false, true, null, "alice@example.org", ArchiveDirection.BOOTSTRAP,
            ),
        )
        assertFalse(
            shouldNotifyInsertedInbound(
                inserted, true, false, true, null, "alice@example.org", ArchiveDirection.BEFORE,
            ),
        )
    }
}
