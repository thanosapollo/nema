package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarbonAuthenticityTest {
    @Before
    fun initializeSmack() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaCarbonProvider()
    }
    @Test
    fun `raw Carbon candidate matrix has one authority oracle`() {
        val cases = listOf(
            carbon("sent") to CarbonCarrier.Direction.SENT,
            carbon("received", to = boundFull) to CarbonCarrier.Direction.RECEIVED,
            carbon("received") to null,
            carbon("received", to = "account@example.org/stale") to null,
            carbon("sent", extra = carbonChild("sent")) to null,
            carbon("sent", extra = carbonChild("received")) to null,
            carbon("sent", namespace = "urn:wrong") to CarrierKind.DIRECT,
            outer("<enabled xmlns='$carbonNamespace'/>") to CarrierKind.DIRECT,
            carbon("sent", extra = "<enabled xmlns='$carbonNamespace'/>") to CarbonCarrier.Direction.SENT,
        )

        cases.forEach { (xml, expected) ->
            val carrier = parse(xml).classifyCarrier(ownBare, boundFull)
            when (expected) {
                CarrierKind.DIRECT -> assertTrue(xml, carrier is MessageCarrier.Direct)
                null -> assertTrue(xml, carrier is MessageCarrier.Inert)
                else -> assertEquals(expected, (carrier as MessageCarrier.Carbon).direction)
            }
        }
    }

    @Test
    fun `sent target is advisory while outer sender must be exact own bare`() {
        listOf(null, ownBare, "elsewhere@example.org/device").forEach { to ->
            assertTrue(parse(carbon("sent", to)).classifyCarrier(ownBare, boundFull) is MessageCarrier.Carbon)
        }
        assertTrue(parse(carbon("sent", from = "$ownBare/device")).classifyCarrier(ownBare, boundFull) is MessageCarrier.Inert)
    }

    @Test
    fun `reserved Carbon error retains only direct error authority`() {
        val direct = parse(error("peer@example.org/device", boundFull)).classifyCarrier(ownBare, boundFull)
        val pseudo = parse(error(ownBare, boundFull)).classifyCarrier(ownBare, boundFull)

        assertTrue(direct is MessageCarrier.Direct)
        assertEquals("operation", direct.classifyOutgoingFailure(ownBare).failure?.operationId)
        assertTrue(pseudo is MessageCarrier.Direct)
        assertNull(pseudo.classifyOutgoingFailure(ownBare).failure)
    }

    private fun parse(xml: String): Message = PacketParserUtils.parseStanza(xml)

    private fun carbon(
        direction: String,
        to: String? = null,
        from: String = ownBare,
        namespace: String = carbonNamespace,
        extra: String = "",
    ) = outer(carbonChild(direction, namespace) + extra, from, to)

    private fun carbonChild(direction: String, namespace: String = carbonNamespace) =
        "<$direction xmlns='$namespace'><forwarded xmlns='urn:xmpp:forward:0'>" +
            "<message xmlns='jabber:client' from='$ownBare/device' to='peer@example.org/device' type='chat'/>" +
            "</forwarded></$direction>"

    private fun outer(children: String, from: String = ownBare, to: String? = null) =
        "<message xmlns='jabber:client' from='$from'${to?.let { " to='$it'" } ?: ""}>$children</message>"

    private fun error(from: String, to: String) = "<message xmlns='jabber:client' id='operation' from='$from' to='$to' type='error'>" +
        "<error xmlns='$carbonNamespace' type='cancel'><remote-server-timeout xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></message>"

    private enum class CarrierKind { DIRECT }

    private companion object {
        const val ownBare = "account@example.org"
        const val boundFull = "$ownBare/test"
        const val carbonNamespace = "urn:xmpp:carbons:2"
    }
}
