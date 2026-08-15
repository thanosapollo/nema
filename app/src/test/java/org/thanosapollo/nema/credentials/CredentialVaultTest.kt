package org.thanosapollo.nema.credentials

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.xmpp.transport.AccountId

class CredentialVaultTest {
    private val accountId = AccountId.require("account-1")

    @Test
    fun `credential round trip exposes plaintext only at explicit access boundary`() {
        val blobs = FakeBlobStore()
        val cipher = FakeCipher()
        val vault = CredentialVault(blobs, cipher)
        val password = "correct horse".toCharArray()

        vault.store(accountId, password)
        password.fill('x')
        val access = vault.load(accountId) as CredentialAccess.Available

        assertArrayEquals("correct horse".toCharArray(), access.value)
        assertFalse(blobs.value!!.ciphertext.contentEquals("correct horse".encodeToByteArray()))
    }

    @Test
    fun `invalidated key clears unusable blob and requires credentials`() {
        val blobs = FakeBlobStore(WrappedCredential(byteArrayOf(1), byteArrayOf(2)))
        val cipher = FakeCipher(invalidated = true)
        val vault = CredentialVault(blobs, cipher)

        assertEquals(CredentialAccess.Missing, vault.load(accountId))
        assertTrue(blobs.deleted)
        assertTrue(cipher.keyDeleted)
    }

    @Test
    fun `sign out deletes wrapped credential and key`() {
        val blobs = FakeBlobStore(WrappedCredential(byteArrayOf(1), byteArrayOf(2)))
        val cipher = FakeCipher()
        val vault = CredentialVault(blobs, cipher)

        vault.delete(accountId)

        assertTrue(blobs.deleted)
        assertTrue(cipher.keyDeleted)
    }

    private class FakeBlobStore(
        var value: WrappedCredential? = null,
    ) : CredentialBlobStore {
        var deleted = false

        override fun read(accountId: AccountId): WrappedCredential? = value

        override fun write(accountId: AccountId, credential: WrappedCredential) {
            value = credential
        }

        override fun delete(accountId: AccountId) {
            value = null
            deleted = true
        }
    }

    private class FakeCipher(
        private val invalidated: Boolean = false,
    ) : CredentialCipher {
        var keyDeleted = false

        override fun encrypt(accountId: AccountId, plaintext: ByteArray): WrappedCredential =
            WrappedCredential(byteArrayOf(9), plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray())

        override fun decrypt(accountId: AccountId, credential: WrappedCredential): ByteArray {
            if (invalidated) throw CredentialInvalidatedException()
            return credential.ciphertext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        }

        override fun deleteKey(accountId: AccountId) {
            keyDeleted = true
        }
    }
}
