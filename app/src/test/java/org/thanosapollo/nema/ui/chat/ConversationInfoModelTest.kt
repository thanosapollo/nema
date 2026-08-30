package org.thanosapollo.nema.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationInfoModelTest {
    @Test
    fun venueProjectsOnlyFactsForItsInformationBranch() {
        assertEquals(
            ConversationInfo.DirectContact(
                address = "alice@example.org",
                localNickname = "Alice",
                remoteProfileName = "Alice Remote",
            ),
            ConversationVenue.Direct.toConversationInfo(
                address = "alice@example.org",
                localNickname = "Alice",
                remoteProfileName = "Alice Remote",
            ),
        )
        assertEquals(
            ConversationInfo.Room(
                address = "room@conference.example.org",
                subject = "Open hardware",
                occupantCount = 3,
            ),
            ConversationVenue.Room(
                subject = "Open hardware",
                occupantCount = 3,
            ).toConversationInfo(
                address = "room@conference.example.org",
                localNickname = "Must not leak",
                remoteProfileName = "Must not leak",
            ),
        )
    }
}
