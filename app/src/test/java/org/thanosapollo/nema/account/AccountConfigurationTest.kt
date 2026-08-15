package org.thanosapollo.nema.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.thanosapollo.nema.xmpp.transport.AccountId

class AccountConfigurationTest {
    @Test
    fun `configuration keeps identity service domain and network endpoint distinct`() {
        val configuration = AccountConfiguration.create(
            id = AccountId.require("account-1"),
            bareJid = "person@example.org",
            authenticationId = "login-name",
            authorizationId = "person@example.org",
            serviceDomain = "example.org",
            networkEndpoint = NetworkEndpoint.create("xmpp-gateway.invalid", 5223),
        )

        assertEquals("person@example.org", configuration.bareJid.value)
        assertEquals("login-name", configuration.authenticationId.value)
        assertEquals("person@example.org", configuration.authorizationId?.value)
        assertEquals("example.org", configuration.serviceDomain.value)
        assertEquals("xmpp-gateway.invalid", configuration.networkEndpoint?.host)
        assertEquals(5223, configuration.networkEndpoint?.port)
    }

    @Test
    fun `optional authorization identity and endpoint remain absent`() {
        val configuration = AccountConfiguration.create(
            id = AccountId.require("account-1"),
            bareJid = "person@example.org",
            authenticationId = "person",
            authorizationId = null,
            serviceDomain = "example.org",
            networkEndpoint = null,
        )

        assertNull(configuration.authorizationId)
        assertNull(configuration.networkEndpoint)
    }

    @Test
    fun `configuration rejects ambiguous or malformed identities`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountConfiguration.create(
                id = AccountId.require("account-1"),
                bareJid = "person@example.org/device",
                authenticationId = "person",
                authorizationId = null,
                serviceDomain = "example.org",
                networkEndpoint = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountConfiguration.create(
                id = AccountId.require("account-1"),
                bareJid = "person@example.org",
                authenticationId = "",
                authorizationId = null,
                serviceDomain = "person@example.org",
                networkEndpoint = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkEndpoint.create("gateway.invalid", 0)
        }
    }
}
