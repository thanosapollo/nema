package org.thanosapollo.nema.xmpp.smack

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jivesoftware.smack.ConnectionConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.account.NetworkEndpoint
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(AndroidJUnit4::class)
class SmackSessionConfigurationAndroidTest {
    @Test
    fun configurationPinsTlsAndXmppIdentityIndependentlyFromEndpoint() {
        val account = AccountConfiguration.create(
            id = AccountId.require("account-1"),
            bareJid = "person@example.org",
            authenticationId = "login-name",
            authorizationId = "person@example.org",
            serviceDomain = "example.org",
            networkEndpoint = NetworkEndpoint.create("gateway.invalid", 5223),
        )

        val configuration = SmackSessionConnectionFactory.configurationFor(account)

        assertEquals("example.org", configuration.xmppServiceDomain.toString())
        assertEquals("gateway.invalid", configuration.host.toString())
        assertEquals(5223, configuration.port.toInt())
        assertEquals("login-name", configuration.username.toString())
        assertEquals("person@example.org", configuration.authzid.toString())
        assertNull(configuration.password)
        assertEquals(ConnectionConfiguration.SecurityMode.required, configuration.securityMode)
        assertTrue(configuration.hostnameVerifier is XmppDomainCertificateVerifier)
    }
}
