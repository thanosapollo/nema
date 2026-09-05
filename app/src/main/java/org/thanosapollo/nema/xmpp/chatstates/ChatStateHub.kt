package org.thanosapollo.nema.xmpp.chatstates

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.thanosapollo.nema.xmpp.transport.IncomingChatState

class ChatStateHub {
    private val composers = MutableStateFlow<Map<Pair<String, String>, List<String>>>(emptyMap())

    fun observe(accountId: String, peer: String): Flow<List<String>> =
        composers.map { it[accountId to peer].orEmpty() }.distinctUntilChanged()

    internal fun clear() {
        composers.value = emptyMap()
    }

    fun apply(state: IncomingChatState) {
        val actor = if (state.groupChat) {
            state.actor.substringAfterLast('/').ifEmpty { return }
        } else {
            state.peer
        }
        val composing = state.activity == ChatActivity.COMPOSING
        val key = state.accountId.value to state.peer
        composers.value = composers.value.let { current ->
            val next = applyComposer(current[key].orEmpty(), actor, composing)
            if (next.isEmpty()) current - key else current + (key to next)
        }
    }
}
