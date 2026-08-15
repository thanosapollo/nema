package org.thanosapollo.nema.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class LoginScreenPolicyTest {
    private val account = AccountId.require("account-1")
    private val generation = ConnectionGeneration.require(1)

    @Test
    fun `blank first run hides stopped chrome`() {
        assertFalse(showLoginSessionChrome(hasSavedAccount = false, connectionState = ConnectionState.Stopped))
    }

    @Test
    fun `saved account shows stopped chrome for edit controls`() {
        assertTrue(showLoginSessionChrome(hasSavedAccount = true, connectionState = ConnectionState.Stopped))
    }

    @Test
    fun `live session shows chrome without waiting for account row`() {
        assertTrue(
            showLoginSessionChrome(
                hasSavedAccount = false,
                connectionState = ConnectionState.Connecting(account, generation),
            ),
        )
    }
}
