package org.thanosapollo.nema.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.xmpp.transport.AccountId

class PendingActivationTest {
    @Test
    fun `stop during account persistence prevents activation`() = runTest {
        val authority = PendingActivationAuthority()
        val token = authority.begin()
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val credential = "stop-secret".toCharArray()
        var credentialWrites = 0
        var activations = 0

        val preparation = async {
            prepareActivation(
                authority = authority,
                token = token,
                accountId = AccountId.require("pending"),
                credential = credential,
                saveAccount = {
                    saveStarted.complete(Unit)
                    releaseSave.await()
                },
                saveCredential = { credentialWrites++ },
                deleteCredential = {},
                emitActivation = { activations++ },
            )
        }
        saveStarted.await()
        authority.invalidate()
        releaseSave.complete(Unit)

        assertFalse(preparation.await())
        assertEquals(0, credentialWrites)
        assertEquals(0, activations)
        assertTrue(credential.all { it == '\u0000' })
    }

    @Test
    fun `sign out during credential persistence deletes active and stale credentials without activation`() = runTest {
        val authority = PendingActivationAuthority()
        val token = authority.begin()
        val active = AccountId.require("active")
        val pending = AccountId.require("pending")
        val storeStarted = CompletableDeferred<Unit>()
        val releaseStore = CompletableDeferred<Unit>()
        val deleted = mutableListOf<AccountId>()
        var activations = 0

        val preparation = async {
            prepareActivation(
                authority = authority,
                token = token,
                accountId = pending,
                credential = "sign-out-secret".toCharArray(),
                saveAccount = {},
                saveCredential = {
                    storeStarted.complete(Unit)
                    releaseStore.await()
                },
                deleteCredential = { deleted += it },
                emitActivation = { activations++ },
            )
        }
        storeStarted.await()
        authority.invalidate()
        removeActiveAccount(
            accountId = active,
            stopSession = {},
            deleteCredential = { deleted += it },
            deleteAccount = {},
        )
        releaseStore.complete(Unit)

        assertFalse(preparation.await())
        assertEquals(listOf(active, pending), deleted)
        assertEquals(0, activations)
    }
}
