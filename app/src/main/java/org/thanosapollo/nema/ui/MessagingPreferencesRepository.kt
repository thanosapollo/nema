package org.thanosapollo.nema.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class MessagingPreferencesRepository(private val preferences: SharedPreferences) {
    private val revision = MutableStateFlow(0L)

    fun readReceipts(accountId: String): Flow<Boolean> = revision
        .map { preferences.getBoolean(readReceiptsKey(accountId), false) }
        .distinctUntilChanged()

    fun setReadReceipts(accountId: String, enabled: Boolean) {
        preferences.edit().putBoolean(readReceiptsKey(accountId), enabled).apply()
        revision.update(Long::inc)
    }

    private fun readReceiptsKey(accountId: String): String {
        require(accountId.isNotEmpty()) { "Account ID must not be empty" }
        return "read-receipts:$accountId"
    }

    companion object {
        fun create(context: Context) = MessagingPreferencesRepository(
            context.applicationContext.getSharedPreferences("messaging", Context.MODE_PRIVATE),
        )
    }
}
