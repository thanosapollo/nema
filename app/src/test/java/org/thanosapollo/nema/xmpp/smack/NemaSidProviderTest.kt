package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.sid.StableUniqueStanzaIdManager
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jivesoftware.smackx.sid.element.StanzaIdElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NemaSidProviderTest {
    private lateinit var previousOriginProvider: ExtensionElementProvider<*>
    private lateinit var previousStanzaProvider: ExtensionElementProvider<*>
    private val attempt = SessionAttemptIdentity(
        AccountId.require("account"),
        ConnectionGeneration.require(1),
        ConnectionAttempt.require(1),
        LifecycleEpoch.require(1),
    )
    @Before
    fun initializeSmack() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        previousOriginProvider = requireNotNull(
            ProviderManager.getExtensionProvider(OriginIdElement.ELEMENT, StableUniqueStanzaIdManager.NAMESPACE),
        )
        previousStanzaProvider = requireNotNull(
            ProviderManager.getExtensionProvider(StanzaIdElement.ELEMENT, StableUniqueStanzaIdManager.NAMESPACE),
        )
        installNemaSidProviders()
    }

    @After
    fun restoreProviders() {
        ProviderManager.addExtensionProvider(
            OriginIdElement.ELEMENT,
            StableUniqueStanzaIdManager.NAMESPACE,
            previousOriginProvider,
        )
        ProviderManager.addExtensionProvider(
            StanzaIdElement.ELEMENT,
            StableUniqueStanzaIdManager.NAMESPACE,
            previousStanzaProvider,
        )
        assertSame(
            previousOriginProvider,
            ProviderManager.getExtensionProvider(OriginIdElement.ELEMENT, StableUniqueStanzaIdManager.NAMESPACE),
        )
        assertSame(
            previousStanzaProvider,
            ProviderManager.getExtensionProvider(StanzaIdElement.ELEMENT, StableUniqueStanzaIdManager.NAMESPACE),
        )
    }

    @Test
    fun `valid SID elements retain typed identity evidence`() {
        val message = parse(
            "<origin-id xmlns='urn:xmpp:sid:0' id='origin'/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by='account@example.org'/>",
        )

        assertEquals("origin", message.getExtension(OriginIdElement::class.java).id)
        val stanzaId = message.getExtension(StanzaIdElement::class.java)
        assertEquals("stable", stanzaId.id)
        assertEquals("account@example.org", stanzaId.by)
    }

    @Test
    fun `malformed origin IDs remain inert while message content survives`() {
        listOf(
            "<origin-id xmlns='urn:xmpp:sid:0'/>",
            "<origin-id xmlns='urn:xmpp:sid:0' id=''/>",
            "<origin-id xmlns='urn:xmpp:sid:0' id='origin' by='peer@example.org'/>",
            "<origin-id xmlns='urn:xmpp:sid:0' xmlns:evil='urn:evil' id='origin' evil:id='other'/>",
            "<origin-id xmlns='urn:xmpp:sid:0' id='origin'>text</origin-id>",
            "<origin-id xmlns='urn:xmpp:sid:0' id='origin'> \n </origin-id>",
            "<origin-id xmlns='urn:xmpp:sid:0' id='origin'><![CDATA[ ]]></origin-id>",
            "<origin-id xmlns='urn:xmpp:sid:0' id='origin'><child/></origin-id>",
        ).forEach { extension ->
            val message = parse(extension)

            assertEquals("body", message.body)
            assertFalse((message.getExtension(OriginIdElement::class.java) as NemaOriginIdElement).structurallyValid)
            assertNull(requireNotNull(message.toIncomingEnvelope(attempt, "account@example.org")).originId)
            val reparsed = roundTrip(message)
            assertNull(reparsed.getExtension(OriginIdElement::class.java))
            assertNull(requireNotNull(reparsed.toIncomingEnvelope(attempt, "account@example.org")).originId)
        }
    }

    @Test
    fun `malformed stanza IDs remain inert while message content survives`() {
        listOf(
            "<stanza-id xmlns='urn:xmpp:sid:0' by='account@example.org'/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable'/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='' by='account@example.org'/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by=''/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by='not a jid'/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' xmlns:evil='urn:evil' id='stable' by='account@example.org' evil:id='other'/>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by='account@example.org'>text</stanza-id>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by='account@example.org'> \n </stanza-id>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by='account@example.org'><![CDATA[ ]]></stanza-id>",
            "<stanza-id xmlns='urn:xmpp:sid:0' id='stable' by='account@example.org'><child/></stanza-id>",
        ).forEach { extension ->
            val message = parse(extension)

            assertEquals("body", message.body)
            assertFalse(
                "accepted malformed stanza ID: $extension",
                (message.getExtension(StanzaIdElement::class.java) as NemaStanzaIdElement).structurallyValid,
            )
            assertTrue(
                requireNotNull(
                    message.toIncomingEnvelope(
                        attempt,
                        "account@example.org",
                        trustedStableIdAuthority = true,
                    ),
                ).stanzaIds.isEmpty(),
            )
            val reparsed = roundTrip(message)
            assertNull(reparsed.getExtension(StanzaIdElement::class.java))
            assertTrue(
                requireNotNull(
                    reparsed.toIncomingEnvelope(
                        attempt,
                        "account@example.org",
                        trustedStableIdAuthority = true,
                    ),
                ).stanzaIds.isEmpty(),
            )
        }
    }

    private fun roundTrip(message: Message): Message {
        val xml = message.toXML().toString()
        assertFalse(xml.contains("nema-invalid-sid"))
        return PacketParserUtils.parseStanza(xml)
    }

    private fun parse(vararg extensions: String): Message = PacketParserUtils.parseStanza(
        "<message xmlns='jabber:client' from='peer@example.org/device' type='chat'>" +
            "<body>body</body>" + extensions.joinToString("") + "</message>",
    )
}
