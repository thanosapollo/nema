package org.thanosapollo.nema.credentials

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(AndroidJUnit4::class)
class CredentialInvalidationAndroidTest {
    @Test
    fun deletedKeystoreKeyClearsWrappedCredentialAndRequiresCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val accountId = AccountId.require(UUID.randomUUID().toString())
        val blobs = NoBackupCredentialBlobStore(context)
        val cipher = AndroidKeystoreCredentialCipher()
        val vault = CredentialVault(blobs, cipher)
        val credential = charArrayOf('t', 'e', 's', 't')

        try {
            vault.store(accountId, credential)
            cipher.deleteKey(accountId)

            assertEquals(CredentialAccess.Missing, vault.load(accountId))
            assertNull(blobs.read(accountId))
        } finally {
            credential.fill('\u0000')
            vault.delete(accountId)
        }
    }
}
