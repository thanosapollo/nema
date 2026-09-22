package org.thanosapollo.nema.chat

/** Owns chronological observation IDs once; layout requests may safely share this immutable list. */
internal class TimelineReadSnapshot(messages: List<TimelineMessage>) : AbstractList<String>() {
    private val ids = messages.map { it.id }
    override val size: Int get() = ids.size
    override fun get(index: Int): String = ids[index]
}
