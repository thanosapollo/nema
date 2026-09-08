package org.thanosapollo.nema.account

import java.net.InetAddress
import java.net.InetSocketAddress

/** Immutable account-derived routing. Never consult a current-account/global UI flag at IO time. */
enum class AccountTransportPolicy {
    DIRECT, TOR;

    companion object {
        fun forAccount(account: AccountConfiguration): AccountTransportPolicy =
            if (listOfNotNull(
                    account.bareJid.value.substringAfterLast('@'),
                    account.authorizationId?.value?.substringAfterLast('@'),
                    account.serviceDomain.value,
                    account.networkEndpoint?.host,
                ).any(::isOnionHost)) TOR else DIRECT
    }
}

internal fun isOnionHost(host: String): Boolean =
    host.trimEnd('.').endsWith(".onion", ignoreCase = true)

// Trust boundary: the local process listening here is assumed to be Orbot. SOCKS success does
// not authenticate Tor or an onion identity. TLS and service-domain certificate checks stay required.
internal fun orbotAddress(): InetSocketAddress =
    InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 9050)

