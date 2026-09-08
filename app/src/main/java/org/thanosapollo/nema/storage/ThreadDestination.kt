package org.thanosapollo.nema.storage

import androidx.room.DatabaseView
import org.thanosapollo.nema.thread.MessageKind

/** Read-only union. Shared metadata never writes or publishes the private alias table. */
@DatabaseView(viewName = "thread_destinations", value = """
    SELECT s.accountId, s.peerJid, s.messageKind, s.threadId, s.title,
      s.authority, s.incarnation, s.revision, s.archived, s.canModify, s.retired, l.title AS localAlias
    FROM shared_threads AS s LEFT JOIN message_thread_titles AS l
      ON l.accountId = s.accountId AND l.peerJid = s.peerJid AND l.messageKind = s.messageKind AND l.threadId = s.threadId
    UNION ALL
    SELECT l.accountId, l.peerJid, l.messageKind, l.threadId, l.title,
      NULL AS authority, NULL AS incarnation, 0 AS revision, 0 AS archived, 0 AS canModify, 0 AS retired, l.title AS localAlias
    FROM message_thread_titles AS l WHERE NOT EXISTS (
      SELECT 1 FROM shared_threads AS s WHERE s.accountId = l.accountId AND s.peerJid = l.peerJid
        AND s.messageKind = l.messageKind AND s.threadId = l.threadId)
""")
internal data class ThreadDestination(
    val accountId: String, val peerJid: String, val messageKind: MessageKind, val threadId: String,
    val title: String, val authority: String?, val incarnation: String?, val revision: Long,
    val archived: Boolean, val canModify: Boolean, val retired: Boolean, val localAlias: String?,
)
