package org.thanosapollo.nema.xmpp.smack

import org.junit.Assert.*
import org.junit.Test
import org.jivesoftware.smack.ConnectionConfiguration
import org.thanosapollo.nema.account.*
import org.thanosapollo.nema.xmpp.transport.AccountId

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [26, 34], application = android.app.Application::class)
class OnionIdentityTest {
    @Test fun strictV3ChecksumVersionAndCanonicalIdentity() {
        assertEquals(VALID_ONION, canonicalOnionIdentity(VALID_ONION.uppercase(java.util.Locale.ROOT)))
        assertNull(canonicalOnionIdentity("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaifeic.onion")) // valid checksum, version 2
        assertNotNull(canonicalOnionIdentity("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaam2dqd.onion"))
        for (host in listOf("a".repeat(56) + ".onion", VALID_ONION.replaceFirst("d", "e"),
            VALID_ONION.dropLast(7) + "e.onion", "sub.$VALID_ONION", "$VALID_ONION.",
            VALID_ONION.replaceFirst("d", "0"), VALID_ONION + " ", VALID_ONION.removeSuffix(".onion"))) {
            assertNull(host, canonicalOnionIdentity(host))
        }
    }

    @Test fun mismatchedCapturedEndpointPortAndAccountCannotAuthorizeConfiguration() {
        val id = AccountId.require("fixture")
        val config = AccountConfiguration.create(id, "fixture@$VALID_ONION", "fixture", null, VALID_ONION, null)
        val owner = org.thanosapollo.nema.session.SessionIdentity(id,
            org.thanosapollo.nema.xmpp.transport.ConnectionGeneration.require(1))
        for ((host, port, accountId) in listOf(Triple(VALID_ONION, 5223, id),
            Triple("clearnet.invalid", 5222, id), Triple(VALID_ONION, 5222, AccountId.require("other")))) {
            TorSocketFactory(NetworkEndpoint.create(host, port), owner = owner.copy(accountId = accountId),
                serviceDomain = VALID_ONION).use {
                assertTrue(runCatching { SmackSessionConnectionFactory.configurationFor(config, it) }.isFailure)
            }
        }
    }

    @Test fun onlySameValidOnionServiceAndEndpointMayNegotiateWithoutTls() {
        val onion = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
        fun config(host: String? = null) = AccountConfiguration.create(AccountId.require("fixture"),
            "fixture@$onion", "fixture", null, onion, host?.let { NetworkEndpoint.create(it, 5222) })
        assertEquals(ConnectionConfiguration.SecurityMode.ifpossible,
            SmackSessionConnectionFactory.configurationFor(config()).securityMode)
        for (host in listOf("clearnet.invalid", "a$onion", "$onion.", "sub.$onion")) {
            assertEquals(ConnectionConfiguration.SecurityMode.required,
                SmackSessionConnectionFactory.configurationFor(config(host)).securityMode)
        }
    }
}
