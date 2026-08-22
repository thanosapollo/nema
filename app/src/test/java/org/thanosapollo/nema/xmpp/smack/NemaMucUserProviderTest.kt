package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jivesoftware.smackx.muc.provider.MUCUserProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NemaMucUserProviderTest {
    @Before
    fun initializeProvider() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaMucUserProvider()
    }

    @Test
    fun `provider preserves normal Smack MUC fields and item count`() {
        val message = PacketParserUtils.parseStanza(
            """
            <message xmlns='jabber:client'>
              <x xmlns='http://jabber.org/protocol/muc#user'>
                <invite from='inviter@example.org/device'><reason>join</reason></invite>
                <item affiliation='member' role='participant' jid='member@example.org'/>
                <password>room-secret</password>
                <status code='110'/>
              </x>
            </message>
            """.trimIndent(),
        ) as Message
        val user = message.extensions.filterIsInstance<NemaMucUser>().single()

        assertEquals(1, user.itemCount)
        assertEquals("member@example.org", user.item.jid.toString())
        assertEquals("join", user.invite.reason)
        assertEquals("room-secret", user.password)
        assertTrue(user.status.contains(MUCUser.Status.PRESENCE_TO_SELF_110))
    }

    @Test
    fun `distributed notices retain the Smack license attribution and modification`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun asset(name: String) = context.assets.open("third-party/$name")
            .bufferedReader()
            .use { it.readText() }

        val license = asset("Apache-2.0.txt")
        val notice = asset("Smack-MUCUserProvider-NOTICE.txt")
        assertTrue(license.contains("Apache License"))
        assertTrue(license.contains("Version 2.0, January 2004"))
        assertTrue(notice.contains("Copyright 2003-2007 Jive Software"))
        assertTrue(notice.contains("Gaston Dombiak"))
        assertTrue(notice.contains("modified by Nema"))
    }

    @Test
    fun `installation replaces only the stock provider`() {
        ProviderManager.addExtensionProvider(MUCUser.ELEMENT, MUCUser.NAMESPACE, MUCUserProvider())
        installNemaMucUserProvider()
        installNemaMucUserProvider()
        assertSame(NemaMucUserProvider, currentMucUserProvider())

        val unexpected = object : MUCUserProvider() {}
        ProviderManager.addExtensionProvider(MUCUser.ELEMENT, MUCUser.NAMESPACE, unexpected)
        try {
            assertNotNull(runCatching { installNemaMucUserProvider() }.exceptionOrNull())
        } finally {
            ProviderManager.addExtensionProvider(
                MUCUser.ELEMENT,
                MUCUser.NAMESPACE,
                NemaMucUserProvider,
            )
        }
    }
}
