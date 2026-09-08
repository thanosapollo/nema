package org.thanosapollo.nema.account

import java.util.Locale
import org.bouncycastle.crypto.digests.SHA3Digest

/** Exact v3 identity only: no subdomains, trailing dot, padding or delegated service. */
internal fun canonicalOnionIdentity(host: String): String? {
    val canonical = host.lowercase(Locale.ROOT)
    if (!Regex("[a-z2-7]{56}\\.onion").matches(canonical)) return null
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    val decoded = ByteArray(35)
    var bits = 0
    var accumulator = 0
    var offset = 0
    for (character in canonical.take(56)) {
        accumulator = (accumulator shl 5) or alphabet.indexOf(character)
        bits += 5
        if (bits >= 8) {
            bits -= 8
            decoded[offset++] = (accumulator ushr bits).toByte()
        }
    }
    if (decoded[34] != 3.toByte()) return null
    val input = ".onion checksum".toByteArray(Charsets.US_ASCII) + decoded.copyOfRange(0, 32) + byteArrayOf(3)
    val checksum = ByteArray(32)
    SHA3Digest(256).apply { update(input, 0, input.size); doFinal(checksum, 0) }
    return canonical.takeIf { decoded[32] == checksum[0] && decoded[33] == checksum[1] }
}
