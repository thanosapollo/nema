package org.thanosapollo.nema.xmpp.rtt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.thanosapollo.nema.xmpp.transport.IncomingRealTimeText

class RealTimeTextHub {
    private val states = MutableStateFlow<Map<String, RttState>>(emptyMap())

    fun observe(peer: String): Flow<String?> =
        states.map { it[peer]?.text }.distinctUntilChanged()

    fun apply(update: IncomingRealTimeText) {
        val incoming = update.element ?: RttElement(0, RttEvent.CANCEL, emptyList())
        states.value = states.value.let { current ->
            val next = applyRtt(current[update.peer], incoming, update.hasBody)
            if (next == null) current - update.peer else current + (update.peer to next)
        }
    }
}
