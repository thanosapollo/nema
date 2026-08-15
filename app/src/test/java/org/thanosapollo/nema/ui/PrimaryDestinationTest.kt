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
}
