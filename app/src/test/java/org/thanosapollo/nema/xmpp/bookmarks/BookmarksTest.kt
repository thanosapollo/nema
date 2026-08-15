package org.thanosapollo.nema.xmpp.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarksTest {
    @Test
    fun pepItemIdsAreRoomJidsAndPrivateStorageConferencesMerge() {
        val pep = roomsFromBookmark2Items(
            listOf(
                Bookmark2Item("coven@conference.shakespeare.example", "<conference xmlns='urn:xmpp:bookmarks:1' autojoin='true'/>"),
                Bookmark2Item("not-a-jid", "<conference xmlns='urn:xmpp:bookmarks:1'/>"),
                Bookmark2Item("room@conference.example.org/nick", null),
            ),
        )
        val privateStorage = roomsFromStorageBookmarks(
            """
            <storage xmlns='storage:bookmarks'>
              <conference jid='legacy@conference.example.org' autojoin='true' name='Legacy'/>
              <conference jid='coven@conference.shakespeare.example' autojoin='false'/>
              <url name='ignored' url='https://example.org'/>
            </storage>
            """.trimIndent(),
        )

        assertEquals(listOf("coven@conference.shakespeare.example", "room@conference.example.org"), pep)
        assertEquals(listOf("legacy@conference.example.org", "coven@conference.shakespeare.example"), privateStorage)
        assertEquals(
            listOf(
                "coven@conference.shakespeare.example",
                "room@conference.example.org",
                "legacy@conference.example.org",
            ),
            mergeBookmarkedRooms(pep, privateStorage),
        )
    }

    @Test
    fun pepConferenceKeepsNickPasswordNameAndAutojoin() {
        val bookmark = parseBookmark2Conference(
            "coven@chat.shakespeare.example",
            """
            <conference xmlns='urn:xmpp:bookmarks:1' name='Council of Oberon' autojoin='true'>
              <nick>Puck</nick>
              <password>secret</password>
            </conference>
            """.trimIndent(),
        )
        assertEquals(
            RoomBookmark(
                roomJid = "coven@chat.shakespeare.example",
                name = "Council of Oberon",
                nick = "Puck",
                password = "secret",
                autojoin = true,
            ),
            bookmark,
        )
    }

    @Test
    fun pepAutojoinAcceptsOneAndRejectsMissingOrFalse() {
        assertEquals(
            true,
            parseBookmark2Conference(
                "room@conference.example.org",
                "<conference xmlns='urn:xmpp:bookmarks:1' autojoin='1'/>",
            )?.autojoin,
        )
        assertEquals(
            false,
            parseBookmark2Conference(
                "room@conference.example.org",
                "<conference xmlns='urn:xmpp:bookmarks:1' autojoin='false'/>",
            )?.autojoin,
        )
        assertEquals(
            false,
            parseBookmark2Conference(
                "room@conference.example.org",
                "<conference xmlns='urn:xmpp:bookmarks:1'/>",
            )?.autojoin,
        )
        assertNull(parseBookmark2Conference("not-a-jid", "<conference xmlns='urn:xmpp:bookmarks:1'/>"))
    }

    @Test
    fun storageBookmarksKeepConferencePayloadAndIgnoreUrls() {
        val bookmarks = parseStorageBookmarks(
            """
            <storage xmlns='storage:bookmarks'>
              <conference jid='council@conference.underhill.example' autojoin='true' name='Council'>
                <nick>Puck</nick>
              </conference>
              <conference jid='quiet@conference.example.org' autojoin='0'/>
              <url name='ignored' url='https://example.org'/>
            </storage>
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                RoomBookmark(
                    roomJid = "council@conference.underhill.example",
                    name = "Council",
                    nick = "Puck",
                    password = null,
                    autojoin = true,
                ),
                RoomBookmark(
                    roomJid = "quiet@conference.example.org",
                    name = null,
                    nick = null,
                    password = null,
                    autojoin = false,
                ),
            ),
            bookmarks,
        )
    }

    @Test
    fun mergePrefersPepPayloadAndAutojoinOnlyTrueRooms() {
        val pep = listOf(
            RoomBookmark("coven@chat.shakespeare.example", name = "Coven", nick = "JC", autojoin = true),
            RoomBookmark("quiet@chat.shakespeare.example", autojoin = false),
        )
        val legacy = listOf(
            RoomBookmark("coven@chat.shakespeare.example", nick = "Legacy", password = "old", autojoin = false),
            RoomBookmark("legacy@conference.example.org", nick = "Puck", autojoin = true),
        )
        val merged = mergeRoomBookmarks(pep, legacy)
        assertEquals("JC", merged.single { it.roomJid == "coven@chat.shakespeare.example" }.nick)
        assertNull(merged.single { it.roomJid == "coven@chat.shakespeare.example" }.password)
        assertEquals(
            listOf("coven@chat.shakespeare.example", "legacy@conference.example.org"),
            autojoinRooms(merged).map(RoomBookmark::roomJid),
        )
    }

    @Test
    fun preferredRoomNickFallsBackToAccountLocalpart() {
        assertEquals("Puck", preferredRoomNick("Puck", "juliet@capulet.example"))
        assertEquals("juliet", preferredRoomNick(null, "juliet@capulet.example"))
        assertEquals("juliet", preferredRoomNick("  ", "juliet@capulet.example"))
        assertEquals("nema", preferredRoomNick(null, ""))
    }

    @Test
    fun joinBookmarkKeepsExistingNameAndPassword() {
        val existing = RoomBookmark(
            roomJid = "coven@chat.shakespeare.example",
            name = "Council of Oberon",
            nick = "Legacy",
            password = "secret",
            autojoin = false,
        )
        val published = joinRoomBookmark(
            roomJid = "coven@chat.shakespeare.example",
            nick = "Puck",
            password = null,
            existing = existing,
        )
        assertEquals(
            RoomBookmark(
                roomJid = "coven@chat.shakespeare.example",
                name = "Council of Oberon",
                nick = "Puck",
                password = "secret",
                autojoin = true,
            ),
            published,
        )
        assertEquals(
            RoomBookmark(
                roomJid = "quiet@conference.example.org",
                name = null,
                nick = null,
                password = null,
                autojoin = true,
            ),
            joinRoomBookmark("quiet@conference.example.org", null, null, existing = null),
        )
    }

    @Test
    fun bookmark2XmlRoundTripsNickPasswordNameAndAutojoin() {
        val original = RoomBookmark(
            roomJid = "coven@chat.shakespeare.example",
            name = "Council of Oberon",
            nick = "Puck",
            password = "se<cret",
            autojoin = true,
        )
        assertEquals(original, parseBookmark2Conference(original.roomJid, bookmark2Xml(original)))
    }
}
