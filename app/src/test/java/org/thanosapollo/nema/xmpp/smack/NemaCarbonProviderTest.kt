package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.carbons.provider.CarbonManagerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NemaCarbonProviderTest {
    @Before
    fun initializeSmack() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `sent Carbon retains direction delay and client message`() {
        val carbon = parse(carbonXml(
            "sent",
            """<delay xmlns='urn:xmpp:delay' stamp='2026-08-24T10:00:00Z'/>
                <message xmlns='jabber:client' from='account@example.org/phone' type='chat'><body>sent body</body></message>""".trimIndent(),
        ))
        assertEquals(CarbonExtension.Direction.sent, carbon.direction)
        assertEquals("sent body", carbon.forwarded.forwardedStanza.body)
        assertNotNull(carbon.forwarded.delayInformation)
    }

    @Test
    fun `received Carbon retains server message`() {
        val carbon = parse(carbonXml("received", "<message xmlns='jabber:server' from='peer@example.org/laptop'/>"))
        assertEquals(CarbonExtension.Direction.received, carbon.direction)
        assertEquals("peer@example.org/laptop", carbon.forwarded.forwardedStanza.from.toString())
    }

    @Test
    fun `duplicate forwarded elements are rejected after stock Smack accepts them`() {
        val xml = """<sent xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'><message xmlns='jabber:client'/></forwarded><forwarded xmlns='urn:xmpp:forward:0'><message xmlns='jabber:client'/></forwarded></sent>"""

        assertNotNull(parseStock(xml).forwarded.forwardedStanza)
        assertThrows(MalformedCarbonException::class.java) { parse(xml) }
    }

    @Test
    fun `inner message in foreign namespace is rejected after stock Smack accepts it`() {
        val xml = carbonXml("received", "<message xmlns='urn:example:foreign'><body>foreign</body></message>")

        assertNotNull(parseStock(xml).forwarded.forwardedStanza)
        assertThrows(MalformedCarbonException::class.java) { parse(xml) }
    }

    private fun parse(xml: String): CarbonExtension =
        NemaCarbonProvider.parse(PacketParserUtils.getParserFor(xml))

    private fun parseStock(xml: String): CarbonExtension =
        CarbonManagerProvider().parse(PacketParserUtils.getParserFor(xml))

    private fun carbonXml(direction: String, content: String): String = """
        <$direction xmlns='urn:xmpp:carbons:2'>
          <forwarded xmlns='urn:xmpp:forward:0'>$content</forwarded>
        </$direction>
    """.trimIndent()
}
