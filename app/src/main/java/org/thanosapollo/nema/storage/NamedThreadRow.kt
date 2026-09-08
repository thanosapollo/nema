package org.thanosapollo.nema.storage

import org.thanosapollo.nema.thread.MessageKind

/** Destination projection: shared metadata when authority is set, otherwise a private local title. */
data class NamedThreadRow(
    val messageKind: MessageKind,
    val threadId: String,
    val parentThreadId: String?,
    val title: String,
    val unreadCount: Int,
    val authority: String? = null,
    val incarnation: String? = null,
    val revision: Long = 0,
    val archived: Boolean = false,
    val canModify: Boolean = false,
    val retired: Boolean = false,
    val localAlias: String? = null,
)
