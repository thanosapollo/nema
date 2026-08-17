package org.thanosapollo.nema.xmpp.rtt

const val RTT_NAMESPACE = "urn:xmpp:rtt:0"

enum class RttEvent { NEW, RESET, EDIT, INIT, CANCEL }

sealed class RttAction {
    data class Insert(val position: Int?, val text: String) : RttAction()
    data class Erase(val position: Int?, val count: Int?) : RttAction()
}

data class RttElement(
    val seq: Int,
    val event: RttEvent,
    val actions: List<RttAction>,
)

data class RttState(
    val seq: Int,
    val text: String,
    val outOfSync: Boolean = false,
)

fun rttEventNamed(value: String?): RttEvent? = when (value) {
    null, "edit" -> RttEvent.EDIT
    "new" -> RttEvent.NEW
    "reset" -> RttEvent.RESET
    "init" -> RttEvent.INIT
    "cancel" -> RttEvent.CANCEL
    else -> null
}

fun applyRttActions(text: String, actions: List<RttAction>): String {
    var current = text
    for (action in actions) {
        current = when (action) {
            is RttAction.Insert -> insertRtt(current, action)
            is RttAction.Erase -> eraseRtt(current, action)
        }
    }
    return current
}

fun applyRtt(
    current: RttState?,
    incoming: RttElement,
    hasBody: Boolean = false,
): RttState? {
    if (hasBody || incoming.event == RttEvent.CANCEL) return null
    if (incoming.event == RttEvent.INIT) return current
    if (incoming.event == RttEvent.NEW || incoming.event == RttEvent.RESET) {
        return RttState(incoming.seq, applyRttActions("", incoming.actions))
    }
    if (current == null || current.outOfSync || incoming.seq != current.seq + 1) {
        return current?.copy(outOfSync = true)
    }
    return RttState(incoming.seq, applyRttActions(current.text, incoming.actions))
}

fun liveTypingLabel(
    composers: List<String>,
    directName: String? = null,
    rttText: String? = null,
): String? = when {
    rttText == null -> org.thanosapollo.nema.xmpp.chatstates.typingLabel(composers, directName)
    rttText.isEmpty() -> "[typing...]"
    else -> "[typing...] $rttText"
}

private fun insertRtt(text: String, action: RttAction.Insert): String {
    if (action.text.isEmpty()) return text
    val position = clip(action.position ?: text.length, text.length)
    return text.substring(0, position) + action.text + text.substring(position)
}

private fun eraseRtt(text: String, action: RttAction.Erase): String {
    val position = clip(action.position ?: text.length, text.length)
    val count = (action.count ?: 1).coerceAtLeast(0).coerceAtMost(position)
    return text.removeRange(position - count, position)
}

private fun clip(value: Int, max: Int): Int = value.coerceAtLeast(0).coerceAtMost(max)
