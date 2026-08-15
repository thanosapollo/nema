package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.mam.element.MamElements
import org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension
import org.jivesoftware.smackx.mam.element.MamQueryIQ
import org.jivesoftware.smackx.mam.filter.MamResultFilter
import org.jivesoftware.smackx.mam.provider.MamResultProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NemaMamResultProviderTest {
    @Before
    fun initializeProvider() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaMamResultProvider()
    }

    @Test
    fun `normal and redacted results retain query id uid delay and nullable content`() {
        val normal = parse(
            resultXml(
                id = "normal-uid",
                content = """
                    <delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:00:00Z'/>
                    <message xmlns='jabber:client' from='peer@example.org/device' type='chat'>
                      <body>body</body>
                    </message>
                """.trimIndent(),
            ),
        )
        val redacted = parse(
            resultXml(
                id = "redacted-uid",
                content = "<delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:01:00Z'/>",
            ),
        )

        assertEquals("query", normal.queryId)
        assertEquals("normal-uid", normal.id)
        assertEquals("body", normal.actualMessage?.body)
        assertNotNull(normal.forwarded.delayInformation)
        assertEquals("query", redacted.queryId)
        assertEquals("redacted-uid", redacted.id)
        assertNull(redacted.actualMessage)
        assertNotNull(redacted.forwarded.delayInformation)
    }

    @Test
    fun `message before delay retains both children without poisoning replay`() {
        val extension = parse(
            resultXml(
                id = "uid",
                content = """
                    <message xmlns='jabber:client' from='peer@example.org/device' type='chat'>
                      <body>body</body>
                    </message>
                    <delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:00:00Z'/>
                """.trimIndent(),
            ),
        )

        assertEquals("body", extension.actualMessage?.body)
        assertNotNull(extension.forwarded.delayInformation)
    }

    @Test
    fun `owned subtype remains compatible with Smack query id filter`() {
        val extension = parse(resultXml(id = "uid", content = ""))
        val carrier = StanzaBuilder.buildMessage().addExtension(extension).build()

        assertTrue(MamResultFilter(MamQueryIQ("query")).accept(carrier))
        assertFalse(MamResultFilter(MamQueryIQ("other")).accept(carrier))
        assertSame(extension, MamResultExtension.from(carrier))
    }

    @Test
    fun `provider rejects missing duplicate or malformed result structure`() {
        val invalid = listOf(
            "<result xmlns='urn:xmpp:mam:2'><forwarded xmlns='urn:xmpp:forward:0'/></result>",
            "<result xmlns='urn:xmpp:mam:2' id=''><forwarded xmlns='urn:xmpp:forward:0'/></result>",
            "<result xmlns='urn:xmpp:mam:2' id='uid'/>",
            """
                <result xmlns='urn:xmpp:mam:2' id='uid'>
                  <forwarded xmlns='urn:xmpp:forward:0'/>
                  <forwarded xmlns='urn:xmpp:forward:0'/>
                </result>
            """.trimIndent(),
            """
                <result xmlns='urn:xmpp:mam:2' id='uid'>
                  <forwarded xmlns='urn:xmpp:forward:0'>
                    <message xmlns='jabber:client'/>
                    <message xmlns='jabber:client'/>
                  </forwarded>
                </result>
            """.trimIndent(),
            """
                <result xmlns='urn:xmpp:mam:2' id='uid'>
                  <forwarded xmlns='urn:xmpp:forward:0'>
                    <delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:00:00Z'/>
                    <delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:01:00Z'/>
                  </forwarded>
                </result>
            """.trimIndent(),
            """
                <result xmlns='urn:xmpp:mam:2' id='uid'>
                  <forwarded xmlns='urn:xmpp:forward:0'>
                    <delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:00:00Z'/>
                    <message xmlns='urn:example:foreign'/>
                  </forwarded>
                </result>
            """.trimIndent(),
            "<result xmlns='urn:xmpp:mam:2' id='uid'><forwarded xmlns='wrong'/></result>",
            "<result xmlns='wrong' id='uid'><forwarded xmlns='urn:xmpp:forward:0'/></result>",
            "<result xmlns='urn:xmpp:mam:2' id='uid'><forwarded xmlns='urn:xmpp:forward:0'>",
        )

        invalid.forEach { xml ->
            assertNotNull("Expected rejection for $xml", runCatching { parse(xml) }.exceptionOrNull())
        }
    }

    @Test
    fun `unknown result and forwarded children are skipped without changing content authority`() {
        val extension = parse(
            """
                <result xmlns='urn:xmpp:mam:2' queryid='query' id='uid'>
                  <ignored xmlns='urn:example:unknown'><nested/></ignored>
                  <forwarded xmlns='urn:xmpp:forward:0'>
                    <unknown xmlns='urn:example:unknown'><nested/></unknown>
                  </forwarded>
                </result>
            """.trimIndent(),
        )

        assertEquals("uid", extension.id)
        assertNull(extension.actualMessage)
    }

    @Test
    fun `semantic rejection consumes result and leaves following result parseable`() {
        val parser = PacketParserUtils.getParserFor(
            """
                <root>
                  <result xmlns='urn:xmpp:mam:2' id='bad'>
                    <forwarded xmlns='urn:xmpp:forward:0'>
                      <message xmlns='jabber:client'/>
                      <message xmlns='jabber:client'/>
                    </forwarded>
                  </result>
                  <result xmlns='urn:xmpp:mam:2' id='good'>
                    <forwarded xmlns='urn:xmpp:forward:0'>
                      <delay xmlns='urn:xmpp:delay' stamp='2026-08-10T10:00:00Z'/>
                    </forwarded>
                  </result>
                </root>
            """.trimIndent(),
        )
        advanceToResult(parser)

        val failure = runCatching { NemaMamResultProvider.parse(parser) }.exceptionOrNull()

        assertTrue(failure is MalformedMamResultException)
        assertEquals(XmlPullParser.Event.END_ELEMENT, parser.eventType)
        assertEquals("result", parser.name)
        advanceToResult(parser)
        assertEquals("good", NemaMamResultProvider.parse(parser).id)
    }

    @Test
    fun `installation replaces only stock provider and rejects unexpected ownership`() {
        ProviderManager.addExtensionProvider(
            MamResultExtension.ELEMENT,
            MamElements.NAMESPACE,
            MamResultProvider(),
        )
        installNemaMamResultProvider()
        installNemaMamResultProvider()
        assertSame(NemaMamResultProvider, currentMamResultProvider())

        val unexpected = object : MamResultProvider() {}
        ProviderManager.addExtensionProvider(
            MamResultExtension.ELEMENT,
            MamElements.NAMESPACE,
            unexpected,
        )
        try {
            assertNotNull(runCatching { installNemaMamResultProvider() }.exceptionOrNull())
        } finally {
            ProviderManager.addExtensionProvider(
                MamResultExtension.ELEMENT,
                MamElements.NAMESPACE,
                NemaMamResultProvider,
            )
        }
    }

    private fun parse(xml: String): NemaMamResultExtension =
        NemaMamResultProvider.parse(PacketParserUtils.getParserFor(xml))

    private fun advanceToResult(parser: XmlPullParser) {
        while (parser.eventType != XmlPullParser.Event.START_ELEMENT || parser.name != "result") {
            parser.next()
        }
    }

    private fun resultXml(id: String, content: String): String = """
        <result xmlns='urn:xmpp:mam:2' queryid='query' id='$id'>
          <forwarded xmlns='urn:xmpp:forward:0'>$content</forwarded>
        </result>
    """.trimIndent()
}
