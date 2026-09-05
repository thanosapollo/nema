package org.thanosapollo.nema.xmpp.muc

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Ephemeral occupant/subject view. WIP-FOUNDATION: persist only when a durable MUC writer exists.
class RoomStateStore {
    private val views = ConcurrentHashMap<String, MutableStateFlow<RoomView?>>()

    fun observe(accountId: String, roomJid: String): Flow<RoomView?> =
        flowFor(accountId, roomJid).asStateFlow()

    fun current(accountId: String, roomJid: String): RoomView? =
        views[key(accountId, roomJid)]?.value

    fun apply(accountId: String, view: RoomView) {
        flowFor(accountId, view.roomJid).value = view
    }

    fun clearAccount(accountId: String) {
        val prefix = "$accountId\u0000"
        views.filterKeys { it.startsWith(prefix) }.values.forEach { it.value = null }
    }

    internal fun clear() {
        views.values.forEach { it.value = null }
    }

    private fun flowFor(accountId: String, roomJid: String): MutableStateFlow<RoomView?> =
        views.getOrPut(key(accountId, roomJid)) { MutableStateFlow(null) }

    private fun key(accountId: String, roomJid: String): String = "$accountId\u0000$roomJid"
}
