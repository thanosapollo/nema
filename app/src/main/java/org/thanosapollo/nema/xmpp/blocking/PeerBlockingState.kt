package org.thanosapollo.nema.xmpp.blocking

data class PeerBlockingState(
    val supported: Boolean,
    val blockedAddresses: List<String> = emptyList(),
) {
    init {
        require(blockedAddresses.all(String::isNotBlank)) { "Blocked addresses must not be blank" }
        require(blockedAddresses.distinct().size == blockedAddresses.size) {
            "Blocked addresses must be unique"
        }
    }

    val blocked: Boolean
        get() = blockedAddresses.isNotEmpty()
}

sealed interface PeerBlockingMutationResult {
    data class Confirmed(val state: PeerBlockingState) : PeerBlockingMutationResult
    data object Rejected : PeerBlockingMutationResult
    data object NotAttempted : PeerBlockingMutationResult
    data object Uncertain : PeerBlockingMutationResult
}
