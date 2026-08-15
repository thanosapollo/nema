package org.thanosapollo.nema.account

import org.jxmpp.jid.impl.JidCreate
import org.thanosapollo.nema.xmpp.transport.AccountId

@JvmInline
value class AccountBareJid private constructor(val value: String) {
    companion object {
        fun require(value: String): AccountBareJid = try {
            val jid = JidCreate.from(value)
            require(jid.isEntityBareJid) { "Account JID must be bare" }
            AccountBareJid(jid.asEntityBareJidOrThrow().toString())
        } catch (error: Exception) {
            throw IllegalArgumentException("Account JID must be a valid bare JID", error)
        }
    }
}

@JvmInline
value class AuthenticationId private constructor(val value: String) {
    companion object {
        fun require(value: String): AuthenticationId {
            require(value.isNotBlank() && value == value.trim()) {
                "Authentication identity must not be blank"
            }
            return AuthenticationId(value)
        }
    }
}

@JvmInline
value class AuthorizationId private constructor(val value: String) {
    companion object {
        fun require(value: String): AuthorizationId = try {
            val jid = JidCreate.from(value)
            require(jid.isEntityBareJid) { "Authorization identity must be bare" }
            AuthorizationId(jid.asEntityBareJidOrThrow().toString())
        } catch (error: Exception) {
            throw IllegalArgumentException("Authorization identity must be a valid bare JID", error)
        }
    }
}

@JvmInline
value class XmppServiceDomain private constructor(val value: String) {
    companion object {
        fun require(value: String): XmppServiceDomain = try {
            val jid = JidCreate.from(value)
            require(jid.isDomainBareJid) { "Service domain must be a domain" }
            XmppServiceDomain(jid.asDomainBareJid().toString())
        } catch (error: Exception) {
            throw IllegalArgumentException("Service domain must be a valid domain", error)
        }
    }
}

@ConsistentCopyVisibility
data class NetworkEndpoint private constructor(
    val host: String,
    val port: Int,
) {
    companion object {
        fun create(host: String, port: Int): NetworkEndpoint {
            require(host.isNotBlank() && host == host.trim() && host.none(Char::isWhitespace)) {
                "Network host must not be blank"
            }
            require(port in 1..65535) { "Network port must be valid" }
            return NetworkEndpoint(host, port)
        }
    }
}

data class AccountConfiguration(
    val id: AccountId,
    val bareJid: AccountBareJid,
    val authenticationId: AuthenticationId,
    val authorizationId: AuthorizationId?,
    val serviceDomain: XmppServiceDomain,
    val networkEndpoint: NetworkEndpoint?,
) {
    companion object {
        fun create(
            id: AccountId,
            bareJid: String,
            authenticationId: String,
            authorizationId: String?,
            serviceDomain: String,
            networkEndpoint: NetworkEndpoint?,
        ) = AccountConfiguration(
            id = id,
            bareJid = AccountBareJid.require(bareJid),
            authenticationId = AuthenticationId.require(authenticationId),
            authorizationId = authorizationId?.let(AuthorizationId::require),
            serviceDomain = XmppServiceDomain.require(serviceDomain),
            networkEndpoint = networkEndpoint,
        )
    }
}

/** Basic login fields plus optional Advanced overrides. Blank overrides keep JID-derived defaults. */
data class LoginFormInput(
    val bareJid: String,
    val authenticationId: String = "",
    val authorizationId: String = "",
    val serviceDomain: String = "",
    val networkHost: String = "",
    val networkPort: String = DEFAULT_NETWORK_PORT,
) {
    fun toConfiguration(id: AccountId): AccountConfiguration {
        val jid = AccountBareJid.require(bareJid)
        val entity = JidCreate.entityBareFrom(jid.value)
        val localpart = entity.localpart.toString()
        val domain = entity.domain.toString()
        val endpoint = if (networkHost.isBlank()) {
            null
        } else {
            val port = networkPort.ifBlank { DEFAULT_NETWORK_PORT }.toIntOrNull()
                ?: throw IllegalArgumentException("Network port must be valid")
            NetworkEndpoint.create(networkHost, port)
        }
        return AccountConfiguration(
            id = id,
            bareJid = jid,
            authenticationId = AuthenticationId.require(authenticationId.ifBlank { localpart }),
            authorizationId = authorizationId.ifBlank { null }?.let(AuthorizationId::require),
            serviceDomain = XmppServiceDomain.require(serviceDomain.ifBlank { domain }),
            networkEndpoint = endpoint,
        )
    }

    companion object {
        const val DEFAULT_NETWORK_PORT = "5222"

        fun fromAccount(configuration: AccountConfiguration) = LoginFormInput(
            bareJid = configuration.bareJid.value,
            authenticationId = configuration.authenticationId.value,
            authorizationId = configuration.authorizationId?.value.orEmpty(),
            serviceDomain = configuration.serviceDomain.value,
            networkHost = configuration.networkEndpoint?.host.orEmpty(),
            networkPort = configuration.networkEndpoint?.port?.toString() ?: DEFAULT_NETWORK_PORT,
        )
    }
}
