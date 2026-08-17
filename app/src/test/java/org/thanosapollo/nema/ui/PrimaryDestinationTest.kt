package org.thanosapollo.nema.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryDestinationTest {
    @Test
    fun fromSavedRestoresKnownDestinationAndDefaultsUnknown() {
        assertEquals(PrimaryDestination.HOME, PrimaryDestination.fromSaved(null))
        assertEquals(PrimaryDestination.HOME, PrimaryDestination.fromSaved("nope"))
        assertEquals(PrimaryDestination.SETTINGS, PrimaryDestination.fromSaved("SETTINGS"))
        assertEquals(PrimaryDestination.HOME, PrimaryDestination.fromSaved("ACCOUNTS"))
    }

    @Test
    fun choosingHomeClosesOpenConversation() {
        var closed = 0
        assertEquals(
            PrimaryDestination.HOME,
            selectSessionDestination(PrimaryDestination.HOME) { closed += 1 },
        )
        assertEquals(1, closed)
        assertEquals(
            PrimaryDestination.SETTINGS,
            selectSessionDestination(PrimaryDestination.SETTINGS) { closed += 1 },
        )
        assertEquals(1, closed)
    }
}
