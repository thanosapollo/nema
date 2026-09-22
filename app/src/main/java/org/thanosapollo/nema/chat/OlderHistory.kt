package org.thanosapollo.nema.chat

/** Personal MAM is account-wide; room archives have their own membership authority. */
sealed interface OlderHistoryScope {
    data object Personal : OlderHistoryScope
    data class Room(val jid: String) : OlderHistoryScope
}

enum class OlderHistoryStatus { Available, Pending, Error, Exhausted }

data class OlderHistoryState(
    val occurrence: ChatRouteOccurrence? = null,
    val status: OlderHistoryStatus = OlderHistoryStatus.Available,
)
