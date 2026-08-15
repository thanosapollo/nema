package org.thanosapollo.nema.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.thanosapollo.nema.xmpp.transport.AccountId

class LoginFormInputTest {
    private val accountId = AccountId.require("account-1")

    @Test
    fun `basic login derives authcid and service domain from bare JID`() {
        val configuration = LoginFormInput(bareJid = "person@example.org").toConfiguration(accountId)

        assertEquals("person@example.org", configuration.bareJid.value)
        assertEquals("person", configuration.authenticationId.value)
        assertNull(configuration.authorizationId)
        assertEquals("example.org", configuration.serviceDomain.value)
        assertNull(configuration.networkEndpoint)
    }

    @Test
    fun `invalid bare JID is rejected without leaking server detail`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LoginFormInput(bareJid = "not-a-jid").toConfiguration(accountId)
        }
        assertEquals("Account JID must be a valid bare JID", error.message)
    }

    @Test
    fun `advanced overrides replace only corresponding defaults`() {
        val configuration = LoginFormInput(
            bareJid = "person@example.org",
            authenticationId = "login-name",
            authorizationId = "person@example.org",
            serviceDomain = "xmpp.example.org",
            networkHost = "gateway.invalid",
            networkPort = "5223",
        ).toConfiguration(accountId)

        assertEquals("login-name", configuration.authenticationId.value)
        assertEquals("person@example.org", configuration.authorizationId?.value)
        assertEquals("xmpp.example.org", configuration.serviceDomain.value)
        assertEquals("gateway.invalid", configuration.networkEndpoint?.host)
        assertEquals(5223, configuration.networkEndpoint?.port)
    }

    @Test
    fun `blank advanced host keeps discovery and default port stays latent`() {
        val configuration = LoginFormInput(
            bareJid = "person@example.org",
            networkPort = "5223",
        ).toConfiguration(accountId)

        assertNull(configuration.networkEndpoint)
    }

    @Test
    fun `host override with blank port uses 5222`() {
        val configuration = LoginFormInput(
            bareJid = "person@example.org",
            networkHost = "gateway.invalid",
            networkPort = "",
        ).toConfiguration(accountId)

        assertEquals(5222, configuration.networkEndpoint?.port)
    }

    @Test
    fun `fromAccount reloads fields without password`() {
        val saved = AccountConfiguration.create(
            id = accountId,
            bareJid = "person@example.org",
            authenticationId = "login-name",
            authorizationId = "person@example.org",
            serviceDomain = "xmpp.example.org",
            networkEndpoint = NetworkEndpoint.create("gateway.invalid", 5223),
        )

        assertEquals(
            LoginFormInput(
                bareJid = "person@example.org",
                authenticationId = "login-name",
                authorizationId = "person@example.org",
                serviceDomain = "xmpp.example.org",
                networkHost = "gateway.invalid",
                networkPort = "5223",
            ),
            LoginFormInput.fromAccount(saved),
        )
    }
}
