package org.thanosapollo.nema.xmpp.chatstates

class OutboundChatStateHub {
    private val last = mutableMapOf<String, Pair<ChatActivity, Long>>()

    @Synchronized
    fun onDraft(peer: String, composingNow: Boolean, nowMs: Long): ChatActivity? {
        val next = nextOutboundChatState(last[peer]?.first, composingNow)
        remember(peer, next, nowMs, composingNow)
        return next
    }

    @Synchronized
    fun onSent(peer: String, nowMs: Long) {
        last[peer] = ChatActivity.ACTIVE to nowMs
    }

    @Synchronized
    fun duePauses(nowMs: Long): List<Pair<String, ChatActivity>> =
        last.mapNotNull { (peer, tracked) ->
            val next = nextOutboundChatState(
                tracked.first,
                composingNow = tracked.first == ChatActivity.COMPOSING,
                pauseDue = nowMs - tracked.second >= OUTBOUND_COMPOSING_PAUSE_MS,
            )
            if (next == ChatActivity.PAUSED) {
                last[peer] = next to nowMs
                peer to next
            } else {
                null
            }
        }

    private fun remember(peer: String, next: ChatActivity?, nowMs: Long, composingNow: Boolean) {
        when {
            next != null -> last[peer] = next to nowMs
            composingNow && last[peer]?.first == ChatActivity.COMPOSING ->
                last[peer] = ChatActivity.COMPOSING to nowMs
            !composingNow -> last.remove(peer)
        }
    }
}
