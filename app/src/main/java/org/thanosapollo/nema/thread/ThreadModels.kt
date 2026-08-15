package org.thanosapollo.nema.thread

import java.util.UUID

@JvmInline
value class ThreadId private constructor(val value: String) {
    companion object {
        fun parse(value: String?): ThreadId? = value?.takeIf(String::isNotEmpty)?.let(::ThreadId)

        fun require(value: String): ThreadId =
            requireNotNull(parse(value)) { "Thread ID must not be empty" }
    }
}

data class ThreadRef(
    val id: ThreadId,
    val parentId: ThreadId? = null,
) {
    init {
        require(parentId != id) { "Parent thread ID must differ from thread ID" }
    }
}

internal fun ThreadRef?.draftKey(): String {
    if (this == null) return ""
    val parent = parentId?.value.orEmpty()
    return "${parent.length}:$parent${id.value}"
}

enum class MessageKind {
    CHAT,
    GROUPCHAT,
    NORMAL,
    HEADLINE,
}

data class ThreadKey(
    val accountId: String,
    val peer: String,
    val messageKind: MessageKind,
    val threadId: ThreadId,
) {
    init {
        require(accountId.isNotEmpty()) { "Account ID must not be empty" }
        require(peer.isNotEmpty()) { "Peer must not be empty" }
    }
}

fun interface ThreadIdFactory {
    fun create(): ThreadId
}

object UuidThreadIdFactory : ThreadIdFactory {
    override fun create(): ThreadId = ThreadId.require(UUID.randomUUID().toString())
}

class ThreadingPolicy(private val threadIds: ThreadIdFactory = UuidThreadIdFactory) {
    fun newTopic(): ThreadRef = ThreadRef(threadIds.create())

    fun replyTo(thread: ThreadRef): ThreadRef = thread

    fun childOf(thread: ThreadRef): ThreadRef = ThreadRef(
        id = threadIds.create(),
        parentId = thread.id,
    )
}
