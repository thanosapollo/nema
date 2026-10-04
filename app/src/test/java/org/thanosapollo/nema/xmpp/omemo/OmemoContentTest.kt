package org.thanosapollo.nema.xmpp.omemo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thanosapollo.nema.xmpp.XmppElement

class OmemoContentTest {
    @Test
    fun keepsProtocolSpecificCiphertextWithoutClaimingDecryption() {
        val legacy = accepted(encrypted(OmemoProtocol.LEGACY))
        assertEquals(OmemoProtocol.LEGACY, legacy.protocol)
        assertEquals(1, legacy.formatVersion)
        assertEquals("AQID", legacy.legacyIv)
        assertEquals(null, legacy.keys.single().recipientBareJid)
        val modern = accepted(encrypted(OmemoProtocol.MODERN))
        assertEquals(OmemoProtocol.MODERN, modern.protocol)
        assertEquals(null, modern.legacyIv)
        assertEquals("peer@example.org", modern.keys.single().recipientBareJid)
        assertEquals("BAUG", modern.payload)
        assertEquals("AQID", modern.keys.single().ciphertext)
        val reordered = encrypted(OmemoProtocol.MODERN).let { it.copy(children = it.children.reversed()) }
        assertEquals(modern, accepted(reordered))
    }

    @Test
    fun headerOnlyIsStillProtectedAndFallbacksDoNotReplaceEvidence() {
        for (protocol in OmemoProtocol.entries) {
            val content = encrypted(protocol).let { it.copy(children = it.children.take(1)) }
            assertEquals(null, accepted(content).payload)
            val ordinary = listOf(
                XmppElement("body", "jabber:client", text = "Fallback"),
                XmppElement("x", "jabber:x:oob"),
                XmppElement("reply", "urn:xmpp:reply:0"),
                XmppElement("received", "urn:xmpp:receipts"),
                XmppElement("reactions", "urn:xmpp:reactions:0"),
            )
            assertEquals(OmemoContentCodec.inspect(listOf(content)), OmemoContentCodec.inspect(ordinary + content))
            assertEquals(OmemoInspection.Absent, OmemoContentCodec.inspect(ordinary))
        }
    }

    @Test
    fun rejectsDuplicateAndMixedProtocols() {
        val legacy = encrypted(OmemoProtocol.LEGACY)
        val modern = encrypted(OmemoProtocol.MODERN)
        assertEquals(OmemoInspection.Rejected, OmemoContentCodec.inspect(listOf(legacy, legacy)))
        assertEquals(OmemoInspection.Rejected, OmemoContentCodec.inspect(listOf(legacy, modern)))
        reject(modern.copy(children = modern.children + modern.children.last()))
        reject(modern.mapHeader { it.copy(children = it.children + it.children.first()) })
        reject(modern.mapKey { it.copy(attributes = it.attributes + ("extra" to "x")) })
    }

    @Test
    fun matchesExactNamespacesAndRejectsWrongDescendants() {
        val modern = encrypted(OmemoProtocol.MODERN)
        for (namespace in listOf("urn:xmpp:omemo:1", "urn:xmpp:omemo:20", "urn:xmpp:omemo:2:foreign")) {
            assertEquals(OmemoInspection.Absent, OmemoContentCodec.inspect(listOf(modern.copy(namespace = namespace))))
        }
        reject(modern.mapKey { it.copy(namespace = OmemoProtocol.LEGACY.namespace) })
        reject(modern.mapHeader { it.copy(namespace = "") })
        reject(modern.copy(attributes = mapOf("unexpected" to "value")))
    }

    @Test
    fun validatesDeviceIdBoundsForBothProtocols() {
        for (protocol in OmemoProtocol.entries) {
            val envelope = encrypted(protocol)
            for (id in listOf("1", "2147483647")) {
                assertEquals(id.toLong(), accepted(envelope.mapHeader {
                    it.copy(attributes = mapOf("sid" to id))
                }).senderDevice)
                assertEquals(id.toLong(), accepted(envelope.mapKey {
                    it.copy(attributes = mapOf("rid" to id))
                }).keys.single().device)
            }
            for (id in listOf("", "-1", "0", "2147483648", "4294967295", "9223372036854775808", "1.0", "abc", "+1", " 1")) {
                reject(envelope.mapHeader { it.copy(attributes = mapOf("sid" to id)) })
                reject(envelope.mapKey { it.copy(attributes = mapOf("rid" to id)) })
            }
            reject(envelope.mapHeader { it.copy(attributes = emptyMap()) })
            reject(envelope.mapKey { it.copy(attributes = emptyMap()) })
        }
    }

    @Test
    fun validatesRecipientAndFlags() {
        val modern = encrypted(OmemoProtocol.MODERN)
        reject(modern.mapKey { it.copy(attributes = it.attributes + ("kex" to "yes")) })
        for (jid in listOf("", "example.org", "peer@example.org/resource", "@")) {
            reject(modern.mapHeader { header ->
                header.copy(children = listOf(header.children.single().copy(attributes = mapOf("jid" to jid))))
            })
        }
        val exchange = accepted(modern.mapKey { it.copy(attributes = it.attributes + ("kex" to "1")) })
        assertEquals(true, exchange.keys.single().keyExchange)
    }

    @Test
    fun preservesBinarySemanticsAndRejectsMalformedOrTruncatedData() {
        val modern = encrypted(OmemoProtocol.MODERN)
        assertEquals("AQID", accepted(modern.mapKey { it.copy(text = " A\nQI\tD\r ") }).keys.single().ciphertext)
        for (text in listOf("", "A", "AQI", "AQI=bad", "!!!!", "AB==", "AQID\u00a0")) {
            reject(modern.mapKey { it.copy(text = text) })
        }
        reject(modern.mapKey { it.copy(children = listOf(XmppElement("nested", it.namespace))) })
        reject(modern.mapHeader { it.copy(text = "mixed content") })
        reject(modern.copy(children = modern.children.take(1) + modern.children.last().copy(text = "")))
    }

    @Test
    fun preservesPresentEmptyLegacyPayloadDistinctFromKeyTransport() {
        // AES-GCM encrypting an empty body yields zero ciphertext bytes; the tag is carried with the key.
        val legacy = encrypted(OmemoProtocol.LEGACY)
        for (text in listOf(null, "", " \t\r\n")) {
            val envelope = legacy.copy(children = legacy.children.take(1) + legacy.children.last().copy(text = text))
            assertEquals("", accepted(envelope).payload)
        }
        assertEquals(null, accepted(legacy.copy(children = legacy.children.take(1))).payload)
    }

    @Test
    fun rejectsEmptyRequiredBinaryFields() {
        for (text in listOf(null, "", " \t\r\n")) {
            for (protocol in OmemoProtocol.entries) {
                reject(encrypted(protocol).mapKey { it.copy(text = text) })
            }
            reject(encrypted(OmemoProtocol.LEGACY).mapHeader { header ->
                header.copy(children = header.children.take(1) + header.children.last().copy(text = text))
            })
            val modern = encrypted(OmemoProtocol.MODERN)
            reject(modern.copy(children = modern.children.take(1) + modern.children.last().copy(text = text)))
        }
    }

    @Test
    fun boundsSizeDepthAndCardinalityBeforeReturningEvidence() {
        val modern = encrypted(OmemoProtocol.MODERN)
        reject(modern.mapKey { it.copy(text = "A".repeat(1_048_580)) })
        reject(modern.mapHeader { header ->
            val group = header.children.single()
            header.copy(children = listOf(group.copy(children = (1..513).map { id ->
                group.children.single().copy(attributes = mapOf("rid" to id.toString()))
            })))
        })
        reject(modern.mapKey { key -> key.copy(children = listOf(key)) })
        assertEquals(OmemoInspection.Rejected, OmemoContentCodec.inspect(List(1025) { modern }))
    }

    @Test
    fun legacyRequiresIvAndKeepsPrekeySemanticsSeparate() {
        val legacy = encrypted(OmemoProtocol.LEGACY)
        reject(legacy.mapHeader { it.copy(children = it.children.take(1)) })
        reject(legacy.mapHeader { it.copy(children = it.children + it.children.last()) })
        val prekey = legacy.mapHeader { header ->
            header.copy(children = listOf(header.children.first().copy(
                attributes = mapOf("rid" to "2147483647", "prekey" to "true"),
            )) + header.children.drop(1))
        }
        assertEquals(true, accepted(prekey).keys.single().keyExchange)
        assertEquals(2147483647L, accepted(prekey).keys.single().device)
        reject(legacy.mapHeader { header ->
            header.copy(children = listOf(header.children.first().copy(
                attributes = mapOf("rid" to "2", "kex" to "true"),
            )) + header.children.drop(1))
        })
    }

    @Test
    fun sameDeviceNumberAcrossModernRecipientsIsNotADuplicate() {
        val modern = encrypted(OmemoProtocol.MODERN).mapHeader { header ->
            header.copy(children = header.children + header.children.single().copy(attributes = mapOf("jid" to "self@example.org")))
        }
        assertEquals(2, accepted(modern).keys.size)
        reject(encrypted(OmemoProtocol.MODERN).mapHeader { header ->
            val group = header.children.single()
            header.copy(children = listOf(group.copy(children = group.children + group.children)))
        })
    }

    private fun encrypted(protocol: OmemoProtocol): XmppElement {
        val key = XmppElement("key", protocol.namespace, mapOf("rid" to "2"), "AQID")
        val headerChildren = if (protocol == OmemoProtocol.LEGACY) {
            listOf(key, XmppElement("iv", protocol.namespace, text = "AQID"))
        } else {
            listOf(XmppElement("keys", protocol.namespace, mapOf("jid" to "peer@example.org"), children = listOf(key)))
        }
        return XmppElement("encrypted", protocol.namespace, children = listOf(
            XmppElement("header", protocol.namespace, mapOf("sid" to "1"), children = headerChildren),
            XmppElement("payload", protocol.namespace, text = "BAUG"),
        ))
    }

    private fun accepted(element: XmppElement): OmemoContent =
        (OmemoContentCodec.inspect(listOf(element)) as OmemoInspection.Unsupported).content

    private fun reject(element: XmppElement) =
        assertEquals(OmemoInspection.Rejected, OmemoContentCodec.inspect(listOf(element)))

    private fun XmppElement.mapHeader(change: (XmppElement) -> XmppElement): XmppElement =
        copy(children = listOf(change(children.first())) + children.drop(1))

    private fun XmppElement.mapKey(change: (XmppElement) -> XmppElement): XmppElement = mapHeader { header ->
        if (namespace == OmemoProtocol.LEGACY.namespace) {
            header.copy(children = listOf(change(header.children.first())) + header.children.drop(1))
        } else {
            val group = header.children.single()
            header.copy(children = listOf(group.copy(children = listOf(change(group.children.single())))))
        }
    }
}
