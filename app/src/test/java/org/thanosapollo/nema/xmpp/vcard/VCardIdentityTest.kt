package org.thanosapollo.nema.xmpp.vcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.storage.PeerEntity

class VCardIdentityTest {
    @Test
    fun `FN wins over nickname and blank trims to null`() {
        val parsed = parseVCardIdentity(
            RemoteVCardPayload(
                formattedName = "  Alice Example ",
                nickname = "ally",
                photoBytes = null,
                photoMime = null,
                photoSha1 = null,
            ),
        )
        assertEquals("Alice Example", parsed.displayName)
        assertNull(parsed.photoBytes)

        val nickOnly = parseVCardIdentity(
            RemoteVCardPayload(
                formattedName = "   ",
                nickname = " ally ",
                photoBytes = null,
                photoMime = null,
                photoSha1 = null,
            ),
        )
        assertEquals("ally", nickOnly.displayName)
    }

    @Test
    fun `oversized photo dropped while name kept`() {
        val big = ByteArray(MAX_VCARD_PHOTO_BYTES + 1) { 1 }
        val parsed = parseVCardIdentity(
            RemoteVCardPayload(
                formattedName = "Bob",
                nickname = null,
                photoBytes = big,
                photoMime = "image/png",
                photoSha1 = "abc",
            ),
        )
        assertEquals("Bob", parsed.displayName)
        assertNull(parsed.photoBytes)
        assertNull(parsed.photoMime)
    }

    @Test
    fun `display label falls back to jid`() {
        assertEquals("peer@example.org", peerDisplayLabel("peer@example.org", null))
        assertEquals("peer@example.org", peerDisplayLabel("peer@example.org", "  "))
        assertEquals("Pat", peerDisplayLabel("peer@example.org", "Pat"))
    }

    @Test
    fun `fetch gate honors success and failure ttl`() {
        assertTrue(peerVCardNeedsFetch(null, 1_000_000L, successTtlMs = 1_000L, failureTtlMs = 500L))
        assertFalse(
            peerVCardNeedsFetch(
                PeerEntity("a", "p@x", vcardFetchedAtMs = 999_500L),
                1_000_000L,
                successTtlMs = 1_000L,
                failureTtlMs = 500L,
            ),
        )
        assertTrue(
            peerVCardNeedsFetch(
                PeerEntity("a", "p@x", vcardFetchedAtMs = 998_000L),
                1_000_000L,
                successTtlMs = 1_000L,
                failureTtlMs = 500L,
            ),
        )
        assertFalse(
            peerVCardNeedsFetch(
                PeerEntity("a", "p@x", vcardFailureAtMs = 999_700L),
                1_000_000L,
                successTtlMs = 1_000L,
                failureTtlMs = 500L,
            ),
        )
        assertTrue(
            peerVCardNeedsFetch(
                PeerEntity("a", "p@x", vcardFailureAtMs = 999_000L),
                1_000_000L,
                successTtlMs = 1_000L,
                failureTtlMs = 500L,
            ),
        )
        assertFalse(
            peerVCardNeedsFetch(
                PeerEntity("a", "room@conference.example.org", room = true),
                1_000_000L,
                successTtlMs = 1_000L,
                failureTtlMs = 500L,
            ),
        )
    }
}
