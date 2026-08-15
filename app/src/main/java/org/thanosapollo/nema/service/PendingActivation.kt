package org.thanosapollo.nema.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.thanosapollo.nema.xmpp.transport.AccountId

internal class PendingActivationAuthority {
    private var generation = 0L

    @Synchronized
    fun begin(): Token = Token(++generation)

    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean = token.generation == generation

    @Synchronized
    fun emitIfCurrent(token: Token, emit: () -> Unit): Boolean {
        if (token.generation != generation) return false
        emit()
        return true
    }

    internal data class Token(val generation: Long)
}

internal suspend fun prepareActivation(
    authority: PendingActivationAuthority,
    token: PendingActivationAuthority.Token,
    accountId: AccountId,
    credential: CharArray,
    saveAccount: suspend () -> Unit,
    saveCredential: suspend (CharArray) -> Unit,
    deleteCredential: suspend (AccountId) -> Unit,
    emitActivation: () -> Unit,
): Boolean {
    var credentialAttempted = false
    var activationEmitted = false
    try {
        saveAccount()
        if (!authority.isCurrent(token)) return false
        credentialAttempted = true
        saveCredential(credential)
        activationEmitted = authority.emitIfCurrent(token, emitActivation)
        return activationEmitted
    } finally {
        credential.fill('\u0000')
        if (credentialAttempted && !activationEmitted) {
            withContext(NonCancellable) { deleteCredential(accountId) }
        }
    }
}
