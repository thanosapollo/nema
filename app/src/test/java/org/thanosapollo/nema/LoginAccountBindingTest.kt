package org.thanosapollo.nema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class LoginAccountBindingTest {
    @Test
    fun credentialRequestBindsConfiguredTargetWithoutChangingDurableActiveAccount() {
        val active = account(ACTIVE_ID, "person@example.org")
        val inactive = account(INACTIVE_ID, "other@example.org")

        val target = credentialFormAccount(
            connectionState = ConnectionState.NeedsCredentials(INACTIVE_ID),
            activeAccount = active,
            configuredAccounts = listOf(active, inactive),
        )

        assertEquals(INACTIVE_ID, target?.id)
        assertEquals(ACTIVE_ID, active.id)
        assertEquals(INACTIVE_ID.value, appearanceFormAccountId(target, addingAccount = false))
        assertEquals(ACTIVE_ID.value, appearanceFormAccountId(active, addingAccount = false))
        assertNull(appearanceFormAccountId(target, addingAccount = true))
    }

    @Test
    fun presentationRequiresDurableAndRuntimeAccountOwnershipToAgree() {
        val active = account(ACTIVE_ID, "person@example.org")
        val generation = ConnectionGeneration.require(1)

        assertSame(active, sessionAccountForPresentation(active, ConnectionState.Stopped))
        assertSame(active, sessionAccountForPresentation(active, ConnectionState.Connected(ACTIVE_ID, generation)))
        assertNull(
            sessionAccountForPresentation(active, ConnectionState.Connecting(INACTIVE_ID, generation)),
        )
        assertSame(
            active,
            sessionAccountForPresentation(active, ConnectionState.Switching(ACTIVE_ID, INACTIVE_ID)),
        )
        assertSame(
            active,
            sessionAccountForPresentation(active, ConnectionState.Switching(INACTIVE_ID, ACTIVE_ID)),
        )
        assertNull(
            sessionAccountForPresentation(
                active,
                ConnectionState.Switching(INACTIVE_ID, AccountId.require("other")),
            ),
        )
    }

    private fun account(id: AccountId, bareJid: String) = AccountConfiguration.create(
        id = id,
        bareJid = bareJid,
        authenticationId = "person",
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private companion object {
        val ACTIVE_ID = AccountId.require("active")
        val INACTIVE_ID = AccountId.require("inactive")
    }
}
