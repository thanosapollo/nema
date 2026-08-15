package org.thanosapollo.nema.xmpp.smack

import java.net.IDN
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

data class CertificateIdentities(
    val dnsNames: List<String> = emptyList(),
    val xmppAddresses: List<String> = emptyList(),
    val srvNames: List<String> = emptyList(),
)

object XmppCertificateIdentity {
    fun matches(serviceDomain: String, identities: CertificateIdentities): Boolean {
        val domain = normalize(serviceDomain) ?: return false
        if (identities.xmppAddresses.any { normalize(it) == domain }) return true
        if (identities.srvNames.any { normalizeSrv(it) == "_xmpp-client.$domain" }) return true
        if (identities.dnsNames.any { matchesDns(domain, it) }) return true
        return false
    }

    private fun matchesDns(domain: String, presented: String): Boolean {
        if (!presented.startsWith("*.")) return normalize(presented) == domain
        val suffix = normalize(presented.removePrefix("*.")) ?: return false
        return domain.endsWith(".$suffix") &&
            domain.substringBefore(".$suffix").isNotEmpty() &&
            !domain.substringBefore(".$suffix").contains('.')
    }

    private fun normalize(value: String): String? = try {
        IDN.toASCII(value.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun normalizeSrv(value: String): String? {
        val lower = value.removeSuffix(".").lowercase(Locale.US)
        if (!lower.startsWith("_xmpp-client.")) return null
        val domain = normalize(lower.removePrefix("_xmpp-client.")) ?: return null
        return "_xmpp-client.$domain"
    }
}

class XmppDomainCertificateVerifier(
    private val serviceDomain: String,
) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession): Boolean {
        val certificate = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            XmppCertificateIdentity.matches(serviceDomain, certificate.identities())
        } catch (_: Exception) {
            false
        }
    }
}

private fun X509Certificate.identities(): CertificateIdentities {
    val dnsNames = mutableListOf<String>()
    val xmppAddresses = mutableListOf<String>()
    val srvNames = mutableListOf<String>()
    subjectAlternativeNames.orEmpty().forEach { entry ->
        when (entry.getOrNull(0) as? Int) {
            2 -> (entry.getOrNull(1) as? String)?.let(dnsNames::add)
            0 -> (entry.getOrNull(1) as? ByteArray)?.let(::decodeOtherName)?.let { other ->
                when (other.oid) {
                    XMPP_ADDR_OID -> xmppAddresses += other.value
                    SRV_NAME_OID -> srvNames += other.value
                }
            }
        }
    }
    return CertificateIdentities(dnsNames, xmppAddresses, srvNames)
}

private data class OtherName(val oid: String, val value: String)

private fun decodeOtherName(encoded: ByteArray): OtherName? {
    return try {
        val outer = DerReader(encoded)
        val sequence = outer.read(0x30)
        if (!outer.finished()) return null
        val content = DerReader(sequence)
        val oid = decodeOid(content.read(0x06))
        val explicit = DerReader(content.read(0xa0))
        val (tag, value) = explicit.readAny()
        if (!content.finished() || !explicit.finished()) return null
        val decoded = when (tag) {
            0x0c -> value.toString(StandardCharsets.UTF_8)
            0x16, 0x13 -> value.toString(StandardCharsets.US_ASCII)
            0x1e -> value.toString(StandardCharsets.UTF_16BE)
            else -> return null
        }
        OtherName(oid, decoded)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private class DerReader(private val bytes: ByteArray) {
    private var offset = 0

    fun read(expectedTag: Int): ByteArray {
        val (tag, value) = readAny()
        require(tag == expectedTag) { "Unexpected DER tag" }
        return value
    }

    fun readAny(): Pair<Int, ByteArray> {
        require(offset < bytes.size) { "Truncated DER value" }
        val tag = bytes[offset++].toInt() and 0xff
        require(offset < bytes.size) { "Truncated DER length" }
        val firstLength = bytes[offset++].toInt() and 0xff
        val length = if (firstLength and 0x80 == 0) {
            firstLength
        } else {
            val count = firstLength and 0x7f
            require(count in 1..4 && offset + count <= bytes.size) { "Invalid DER length" }
            var result = 0
            repeat(count) { result = (result shl 8) or (bytes[offset++].toInt() and 0xff) }
            result
        }
        require(length >= 0 && offset + length <= bytes.size) { "Truncated DER content" }
        return tag to bytes.copyOfRange(offset, offset + length).also { offset += length }
    }

    fun finished() = offset == bytes.size
}

private fun decodeOid(bytes: ByteArray): String {
    require(bytes.isNotEmpty()) { "Empty DER OID" }
    val first = bytes[0].toInt() and 0xff
    val firstComponent = (first / 40).coerceAtMost(2)
    val components = mutableListOf(firstComponent, first - firstComponent * 40)
    var value = 0L
    for (index in 1 until bytes.size) {
        val byte = bytes[index].toInt() and 0xff
        require(value <= (Long.MAX_VALUE ushr 7)) { "DER OID overflow" }
        value = (value shl 7) or (byte and 0x7f).toLong()
        if (byte and 0x80 == 0) {
            components += value.toInt()
            value = 0
        }
    }
    require(value == 0L) { "Truncated DER OID" }
    return components.joinToString(".")
}

private const val XMPP_ADDR_OID = "1.3.6.1.5.5.7.8.5"
private const val SRV_NAME_OID = "1.3.6.1.5.5.7.8.7"
