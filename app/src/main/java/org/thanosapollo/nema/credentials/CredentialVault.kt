package org.thanosapollo.nema.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.thanosapollo.nema.xmpp.transport.AccountId

sealed interface CredentialAccess {
    data class Available(val value: CharArray) : CredentialAccess
    data object Missing : CredentialAccess
}

data class WrappedCredential(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(iv.isNotEmpty()) { "Credential IV must not be empty" }
        require(ciphertext.isNotEmpty()) { "Credential ciphertext must not be empty" }
    }
}

class CredentialInvalidatedException(cause: Throwable? = null) : RuntimeException(cause)

interface CredentialBlobStore {
    fun read(accountId: AccountId): WrappedCredential?
    fun write(accountId: AccountId, credential: WrappedCredential)
    fun delete(accountId: AccountId)
}

interface CredentialCipher {
    fun encrypt(accountId: AccountId, plaintext: ByteArray): WrappedCredential
    fun decrypt(accountId: AccountId, credential: WrappedCredential): ByteArray
    fun deleteKey(accountId: AccountId)
}

class CredentialVault(
    private val blobs: CredentialBlobStore,
    private val cipher: CredentialCipher,
) {
    fun store(accountId: AccountId, credential: CharArray) {
        val plaintext = encode(credential)
        try {
            blobs.write(accountId, cipher.encrypt(accountId, plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(accountId: AccountId): CredentialAccess {
        val wrapped = try {
            blobs.read(accountId)
        } catch (_: CredentialInvalidatedException) {
            clearInvalid(accountId)
            return CredentialAccess.Missing
        } ?: return CredentialAccess.Missing

        val plaintext = try {
            cipher.decrypt(accountId, wrapped)
        } catch (_: CredentialInvalidatedException) {
            clearInvalid(accountId)
            return CredentialAccess.Missing
        }
        return try {
            CredentialAccess.Available(decode(plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    fun delete(accountId: AccountId) {
        blobs.delete(accountId)
        cipher.deleteKey(accountId)
    }

    private fun clearInvalid(accountId: AccountId) {
        blobs.delete(accountId)
        cipher.deleteKey(accountId)
    }

    private fun encode(value: CharArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    private fun decode(value: ByteArray): CharArray {
        val decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value))
        return CharArray(decoded.remaining()).also(decoded::get)
    }
}

class AndroidKeystoreCredentialCipher : CredentialCipher {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    override fun encrypt(accountId: AccountId, plaintext: ByteArray): WrappedCredential =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(accountId))
            WrappedCredential(cipher.iv.copyOf(), cipher.doFinal(plaintext))
        } catch (error: GeneralSecurityException) {
            throw CredentialInvalidatedException(error)
        }

    override fun decrypt(accountId: AccountId, credential: WrappedCredential): ByteArray =
        try {
            val key = keyStore.getKey(alias(accountId), null) as? SecretKey
                ?: throw CredentialInvalidatedException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, credential.iv))
            cipher.doFinal(credential.ciphertext)
        } catch (error: CredentialInvalidatedException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw CredentialInvalidatedException(error)
        }

    override fun deleteKey(accountId: AccountId) {
        keyStore.deleteEntry(alias(accountId))
    }

    private fun getOrCreateKey(accountId: AccountId): SecretKey {
        (keyStore.getKey(alias(accountId), null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias(accountId),
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun alias(accountId: AccountId) = "nema.credential.${digest(accountId.value)}"

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

class NoBackupCredentialBlobStore(context: Context) : CredentialBlobStore {
    private val directory = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }

    override fun read(accountId: AccountId): WrappedCredential? {
        val file = file(accountId)
        if (!file.exists()) return null
        try {
            DataInputStream(FileInputStream(file)).use { input ->
                require(input.readInt() == MAGIC) { "Credential blob magic is invalid" }
                require(input.readInt() == VERSION) { "Credential blob version is invalid" }
                val iv = readBounded(input, MAX_IV_BYTES)
                val ciphertext = readBounded(input, MAX_CIPHERTEXT_BYTES)
                require(input.read() == -1) { "Credential blob has trailing data" }
                return WrappedCredential(iv, ciphertext)
            }
        } catch (error: Exception) {
            throw CredentialInvalidatedException(error)
        }
    }

    override fun write(accountId: AccountId, credential: WrappedCredential) {
        val destination = file(accountId)
        val temporary = File(directory, "${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                DataOutputStream(stream).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    writeBounded(output, credential.iv, MAX_IV_BYTES)
                    writeBounded(output, credential.ciphertext, MAX_CIPHERTEXT_BYTES)
                    output.flush()
                    stream.fd.sync()
                }
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    override fun delete(accountId: AccountId) {
        file(accountId).delete()
    }

    private fun file(accountId: AccountId) = File(directory, "${digest(accountId.value)}.bin")

    private fun readBounded(input: DataInputStream, limit: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..limit) { "Credential blob field length is invalid" }
        return ByteArray(size).also(input::readFully)
    }

    private fun writeBounded(output: DataOutputStream, value: ByteArray, limit: Int) {
        require(value.size in 1..limit) { "Credential blob field length is invalid" }
        output.writeInt(value.size)
        output.write(value)
    }

    private companion object {
        const val MAGIC = 0x44524d43
        const val VERSION = 1
        const val MAX_IV_BYTES = 32
        const val MAX_CIPHERTEXT_BYTES = 65_536
    }
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString(separator = "") { "%02x".format(it) }
