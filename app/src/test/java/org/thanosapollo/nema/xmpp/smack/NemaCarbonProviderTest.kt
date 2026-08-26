package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.carbons.provider.CarbonManagerProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun `installation replaces both stock Carbon providers`() = withRestoredProviders {
        carbonDirections.forEach { direction ->
            ProviderManager.addExtensionProvider(
                direction.name,
                CarbonExtension.NAMESPACE,
                CarbonManagerProvider(),
            )
        }
        installNemaCarbonProvider()

        carbonDirections.forEach { direction ->
            assertSame(NemaCarbonProvider, currentProvider(direction))
        }
    }

    @Test
    fun `installation is idempotent and rejects foreign ownership atomically`() = withRestoredProviders {
        carbonDirections.forEach { direction ->
            ProviderManager.addExtensionProvider(
                direction.name,
                CarbonExtension.NAMESPACE,
                CarbonManagerProvider(),
            )
        }
        installNemaCarbonProvider()
        installNemaCarbonProvider()
        carbonDirections.forEach { direction ->
            assertSame(NemaCarbonProvider, currentProvider(direction))
        }

        val retainedStock = CarbonManagerProvider()
        val foreign = object : CarbonManagerProvider() {}
        ProviderManager.addExtensionProvider("sent", CarbonExtension.NAMESPACE, retainedStock)
        ProviderManager.addExtensionProvider("received", CarbonExtension.NAMESPACE, foreign)

        assertThrows(IllegalStateException::class.java) { installNemaCarbonProvider() }
        assertSame(retainedStock, currentProvider(CarbonExtension.Direction.sent))
        assertSame(foreign, currentProvider(CarbonExtension.Direction.received))
    }

    @Test
    fun `installation rejects missing ownership atomically`() = withRestoredProviders {
        val retainedStock = CarbonManagerProvider()
        ProviderManager.addExtensionProvider("sent", CarbonExtension.NAMESPACE, retainedStock)
        ProviderManager.removeExtensionProvider("received", CarbonExtension.NAMESPACE)

        assertThrows(IllegalStateException::class.java) { installNemaCarbonProvider() }
        assertSame(retainedStock, currentProvider(CarbonExtension.Direction.sent))
        assertNull(currentProvider(CarbonExtension.Direction.received))
    }

    @Test
    fun `rejected Carbon settles parser before following sibling`() {
        val parser = PacketParserUtils.getParserFor(
            """
                <root>
                  ${carbonXml("sent", "<message xmlns='jabber:client'/><message xmlns='jabber:client'/>")}
                  ${carbonXml("received", "<message xmlns='jabber:client'><body>next</body></message>")}
                </root>
            """.trimIndent(),
        )
        advanceToCarbon(parser)

        assertThrows(MalformedCarbonException::class.java) { NemaCarbonProvider.parse(parser) }
        assertEquals(XmlPullParser.Event.END_ELEMENT, parser.eventType)
        assertEquals("sent", parser.name)
        advanceToCarbon(parser)
        assertEquals("next", NemaCarbonProvider.parse(parser).forwarded.forwardedStanza.body)
    }

    private fun parse(xml: String): CarbonExtension =
        NemaCarbonProvider.parse(PacketParserUtils.getParserFor(xml))

    private fun parseStock(xml: String): CarbonExtension =
        CarbonManagerProvider().parse(PacketParserUtils.getParserFor(xml))

    private fun advanceToCarbon(parser: XmlPullParser) {
        while (parser.eventType != XmlPullParser.Event.START_ELEMENT || parser.name !in carbonElements) {
            parser.next()
        }
    }

    private fun currentProvider(direction: CarbonExtension.Direction): ExtensionElementProvider<*>? =
        ProviderManager.getExtensionProvider(direction.name, CarbonExtension.NAMESPACE)

    private fun withRestoredProviders(block: () -> Unit) {
        val previous = carbonDirections.associateWith(::currentProvider)
        try {
            block()
        } finally {
            previous.forEach { (direction, provider) ->
                if (provider == null) {
                    ProviderManager.removeExtensionProvider(direction.name, CarbonExtension.NAMESPACE)
                } else {
                    ProviderManager.addExtensionProvider(direction.name, CarbonExtension.NAMESPACE, provider)
                }
            }
        }
    }

    private fun carbonXml(direction: String, content: String): String = """
        <$direction xmlns='urn:xmpp:carbons:2'>
          <forwarded xmlns='urn:xmpp:forward:0'>$content</forwarded>
        </$direction>
    """.trimIndent()

    private companion object {
        val carbonDirections = listOf(CarbonExtension.Direction.sent, CarbonExtension.Direction.received)
        val carbonElements = carbonDirections.map(CarbonExtension.Direction::name).toSet()
    }
}
