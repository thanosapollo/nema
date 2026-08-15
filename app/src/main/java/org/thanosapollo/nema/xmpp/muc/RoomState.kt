package org.thanosapollo.nema.xmpp.muc

data class RoomOccupant(
    val nick: String,
    val role: String? = null,
    val affiliation: String? = null,
)

data class RoomView(
    val roomJid: String,
    val subject: String? = null,
    val occupants: List<RoomOccupant> = emptyList(),
    val ownNick: String? = null,
) {
    val occupantCount: Int get() = occupants.size
}

fun roomSubtitle(subject: String?, occupantCount: Int): String {
    val topic = subject?.trim().orEmpty()
    if (topic.isNotEmpty()) return topic
    if (occupantCount > 0) return "$occupantCount occupants"
    return "Groupchat"
}

fun upsertOccupant(occupants: List<RoomOccupant>, occupant: RoomOccupant): List<RoomOccupant> {
    val nick = occupant.nick.trim()
    if (nick.isEmpty()) return occupants
    return occupants.filterNot { it.nick.equals(nick, ignoreCase = true) } + occupant.copy(nick = nick)
}

fun removeOccupant(occupants: List<RoomOccupant>, nick: String): List<RoomOccupant> {
    val target = nick.trim()
    if (target.isEmpty()) return occupants
    return occupants.filterNot { it.nick.equals(target, ignoreCase = true) }
}
