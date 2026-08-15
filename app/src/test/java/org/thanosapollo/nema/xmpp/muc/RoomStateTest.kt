package org.thanosapollo.nema.xmpp.muc

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomStateTest {
    @Test
    fun subtitlePrefersSubjectThenOccupantCount() {
        assertEquals("Council of Oberon", roomSubtitle("  Council of Oberon  ", 4))
        assertEquals("3 occupants", roomSubtitle("   ", 3))
        assertEquals("Groupchat", roomSubtitle(null, 0))
    }

    @Test
    fun occupantUpsertIsNickKeyedAndRemovalIsExactNick() {
        val first = upsertOccupant(emptyList(), RoomOccupant("Puck", role = "participant"))
        val replaced = upsertOccupant(first, RoomOccupant("puck", role = "moderator", affiliation = "admin"))
        assertEquals(listOf(RoomOccupant("puck", role = "moderator", affiliation = "admin")), replaced)
        assertEquals(emptyList<RoomOccupant>(), removeOccupant(replaced, "PUCK"))
    }
}
