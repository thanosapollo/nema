package org.thanosapollo.nema.xmpp.rtt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.thanosapollo.nema.xmpp.transport.IncomingRealTimeText

class RealTimeTextHub {
    private val states = MutableStateFlow<Map<Pair<String, String>, RttState>>(emptyMap())

    fun observe(accountId: String, peer: String): Flow<String?> =
        states.map { it[accountId to peer]?.text }.distinctUntilChanged()

    internal fun clear() {
        states.value = emptyMap()
    }

    fun apply(update: IncomingRealTimeText) {
        val incoming = update.element ?: RttElement(0, RttEvent.CANCEL, emptyList())
        val key = update.accountId.value to update.peer
        states.value = states.value.let { current ->
            val next = applyRtt(current[key], incoming, update.hasBody)
            if (next == null) current - key else current + (key to next)
        }
    }
}
