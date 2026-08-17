package org.thanosapollo.nema.xmpp.chatstates

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.thanosapollo.nema.xmpp.transport.IncomingChatState

class ChatStateHub {
    private val composers = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    fun observe(peer: String): Flow<List<String>> =
        composers.map { it[peer].orEmpty() }.distinctUntilChanged()

    fun apply(state: IncomingChatState) {
        val actor = if (state.groupChat) {
            state.actor.substringAfterLast('/').ifEmpty { return }
        } else {
            state.peer
        }
        val composing = state.activity == ChatActivity.COMPOSING
        composers.value = composers.value.let { current ->
            val next = applyComposer(current[state.peer].orEmpty(), actor, composing)
            if (next.isEmpty()) current - state.peer else current + (state.peer to next)
        }
    }
}
