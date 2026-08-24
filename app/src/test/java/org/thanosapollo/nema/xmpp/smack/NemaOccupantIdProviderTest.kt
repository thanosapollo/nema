package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.lang.reflect.Proxy
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NemaOccupantIdProviderTest {
    @Before fun initializeSmack() =
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())

    @Test fun `canonical message and presence expose exact opaque id`() {
        assertEquals("opaque", parseMessage(valid("opaque")).nemaOccupantId())
        assertEquals("opaque", parsePresence(valid("opaque")).nemaOccupantId())
        assertEquals("e\u0301", parseMessage(valid("e\u0301")).nemaOccupantId())
    }

    @Test fun `unicode bounds count code points not UTF-16 units`() {
        val supplementary = "😀"
        listOf("x", "x".repeat(128), supplementary.repeat(128), "x" + supplementary).forEach {
            assertEquals(it, parseMessage(valid(it)).nemaOccupantId())
        }
        listOf("x".repeat(129), supplementary.repeat(129)).forEach {
            assertNull(parseMessage(valid(it)).nemaOccupantId())
        }
    }

    @Test fun `attributes and all character or child content invalidate without losing siblings`() {
        val invalid = listOf(
            "<occupant-id xmlns='$NS'/>", "<occupant-id xmlns='$NS' id=''/>",
            "<occupant-id xmlns='$NS' id='x' extra='y'/>",
            "<occupant-id xmlns='$NS' xmlns:e='urn:evil' id='x' e:a='y'/>",
            "<occupant-id xmlns='$NS' id='x'>text</occupant-id>",
            "<occupant-id xmlns='$NS' id='x'> \n </occupant-id>",
            "<occupant-id xmlns='$NS' id='x'><![CDATA[data]]></occupant-id>",
            "<occupant-id xmlns='$NS' id='x'><bad><nested/></bad></occupant-id>",
        )
        invalid.forEach { assertNull(it, parseMessage(it).nemaOccupantId()) }
        val message = parseMessage(invalid.last(), "<body>survives</body><x xmlns='urn:next'/>")
        assertEquals("survives", message.body)
        assertNotNull(message.getExtensionElement("x", "urn:next"))
    }

    @Test fun `duplicate matrix counts valid and invalid exact QName occurrences`() {
        val good = NemaOccupantIdElement("good", true)
        val bad = NemaOccupantIdElement("bad", false)
        listOf(good to good, good to bad, bad to good, bad to bad).forEach { (a, b) ->
            assertNull(StanzaBuilder.buildMessage().addExtension(a).addExtension(b).build().nemaOccupantId())
        }
        val lookalike = org.jivesoftware.smack.packet.StandardExtensionElement
            .builder(ELEMENT, "urn:wrong").addAttribute("id", "wrong").build()
        assertEquals("good", StanzaBuilder.buildMessage().addExtension(lookalike).addExtension(good).build().nemaOccupantId())
        assertNull(parseMessage("<occupant-id xmlns='urn:wrong' id='x'/>").nemaOccupantId())
    }

    @Test fun `valid serialization escapes and reparses while invalid emits nothing`() {
        val id = "<&\"'😀"
        val element = NemaOccupantIdElement(id, true)
        val xml = element.toXML().toString()
        assertEquals(id, parseMessage(xml).nemaOccupantId())
        assertTrue(xml.contains("xmlns='$NS'") || xml.contains("xmlns=\"$NS\""))
        assertEquals("", NemaOccupantIdElement("bad", false).toXML().toString())
    }

    @Test fun `fake parser rejects entity references and EOF at every depth`() {
        val entity = NemaOccupantIdProvider.parse(fake(XmlPullParser.Event.ENTITY_REFERENCE to 1, XmlPullParser.Event.END_ELEMENT to 1))
        assertFalse(entity.structurallyValid)
        assertFalse(NemaOccupantIdProvider.parse(fake(XmlPullParser.Event.OTHER to 1, XmlPullParser.Event.END_ELEMENT to 1)).structurallyValid)
        listOf(
            arrayOf(XmlPullParser.Event.END_DOCUMENT to 1),
            arrayOf(XmlPullParser.Event.START_ELEMENT to 2, XmlPullParser.Event.END_DOCUMENT to 2),
        ).forEach { events -> assertThrows(IOException::class.java) { NemaOccupantIdProvider.parse(fake(*events)) } }
    }

    @Test fun `provider registration is restored or removed even when parsing scope throws`() {
        ProviderManager.removeExtensionProvider(ELEMENT, NS)
        assertThrows(IllegalStateException::class.java) { withProvider { error("boom") } }
        assertNull(ProviderManager.getExtensionProvider(ELEMENT, NS))
        val previous = object : ExtensionElementProvider<NemaOccupantIdElement>() {
            override fun parse(p: XmlPullParser, d: Int, e: XmlEnvironment) = NemaOccupantIdElement("old", true)
        }
        ProviderManager.addExtensionProvider(ELEMENT, NS, previous)
        try {
            assertThrows(IllegalStateException::class.java) { withProvider { error("boom") } }
            assertSame(previous, ProviderManager.getExtensionProvider(ELEMENT, NS))
        } finally { ProviderManager.removeExtensionProvider(ELEMENT, NS) }
    }

    @Test fun `production installation owns the exact provider and rejects replacement`() {
        val previous = ProviderManager.getExtensionProvider(ELEMENT, NS)
        try {
            ProviderManager.removeExtensionProvider(ELEMENT, NS)
            installNemaOccupantIdProvider()
            installNemaOccupantIdProvider()

            assertSame(NemaOccupantIdProvider, ProviderManager.getExtensionProvider(ELEMENT, NS))
            val unexpected = object : ExtensionElementProvider<NemaOccupantIdElement>() {
                override fun parse(
                    parser: XmlPullParser,
                    initialDepth: Int,
                    xmlEnvironment: XmlEnvironment,
                ) = NemaOccupantIdElement("unexpected", true)
            }
            ProviderManager.addExtensionProvider(ELEMENT, NS, unexpected)
            assertThrows(IllegalStateException::class.java) {
                installNemaOccupantIdProvider()
            }
        } finally {
            if (previous == null) {
                ProviderManager.removeExtensionProvider(ELEMENT, NS)
            } else {
                ProviderManager.addExtensionProvider(ELEMENT, NS, previous)
            }
        }
        if (previous == null) {
            assertNull(ProviderManager.getExtensionProvider(ELEMENT, NS))
        } else {
            assertSame(previous, ProviderManager.getExtensionProvider(ELEMENT, NS))
        }
    }

    private fun parseMessage(vararg children: String): Message = withProvider {
        PacketParserUtils.parseStanza("<message xmlns='jabber:client'>${children.joinToString("")}</message>")
    }
    private fun parsePresence(vararg children: String): Presence = withProvider {
        PacketParserUtils.parseStanza("<presence xmlns='jabber:client'>${children.joinToString("")}</presence>")
    }
    private fun valid(id: String) = "<occupant-id xmlns='$NS' id='${escape(id)}'/>"
    private fun escape(value: String) = value.replace("&", "&amp;").replace("'", "&apos;").replace("<", "&lt;")

    private fun <T> withProvider(block: () -> T): T {
        val previous = ProviderManager.getExtensionProvider(ELEMENT, NS)
        ProviderManager.addExtensionProvider(ELEMENT, NS, NemaOccupantIdProvider)
        return try { block() } finally {
            if (previous == null) ProviderManager.removeExtensionProvider(ELEMENT, NS)
            else ProviderManager.addExtensionProvider(ELEMENT, NS, previous)
        }
    }

    private fun fake(vararg events: Pair<XmlPullParser.Event, Int>): XmlPullParser {
        var index = -1
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(XmlPullParser::class.java)) { _, method, args ->
            when (method.name) {
                "next" -> events[++index].first
                "getEventType" -> if (index < 0) XmlPullParser.Event.START_ELEMENT else events[index].first
                "getDepth" -> if (index < 0) 1 else events[index].second
                "getName" -> if (index < 0) ELEMENT else "child"
                "getNamespace" -> NS
                "getAttributeCount" -> 1
                "getAttributeName" -> "id"
                "getAttributeNamespace" -> ""
                "getAttributeValue" -> if (args?.size == 1 || args?.get(1) == "id") "id" else null
                else -> null
            }
        } as XmlPullParser
    }
}

private const val ELEMENT = "occupant-id"
private const val NS = "urn:xmpp:occupant-id:0"
