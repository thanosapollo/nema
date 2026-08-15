package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.blocking.BlockingCommandManager
import org.jivesoftware.smackx.mam.MamManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager
import org.jivesoftware.smackx.vcardtemp.VCardManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SmackApiAvailabilityTest {
    @Test
    fun `pinned Smack modules expose required Android transport APIs`() {
        assertEquals("AndroidSmackInitializer", AndroidSmackInitializer::class.java.simpleName)
        assertEquals("XMPPTCPConnectionConfiguration", XMPPTCPConnectionConfiguration::class.java.simpleName)
        assertEquals("Roster", Roster::class.java.simpleName)
        assertEquals("DeliveryReceiptManager", DeliveryReceiptManager::class.java.simpleName)
        assertEquals("MamManager", MamManager::class.java.simpleName)
        assertEquals("VCardManager", VCardManager::class.java.simpleName)
        assertEquals("BlockingCommandManager", BlockingCommandManager::class.java.simpleName)
        assertEquals("HttpFileUploadManager", org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager::class.java.simpleName)
        assertEquals("MultiUserChatManager", org.jivesoftware.smackx.muc.MultiUserChatManager::class.java.simpleName)
        assertEquals("BookmarkManager", org.jivesoftware.smackx.bookmarks.BookmarkManager::class.java.simpleName)
    }
}
