package org.thanosapollo.nema.xmpp.threads

import java.util.UUID
import org.thanosapollo.nema.thread.MessageKind

/** Captured from a complete authenticated read, never rebound when sending an authored command. */
data class DirectoryContext(val authority: String, val scope: ThreadDirectoryScope, val generation: Long? = null)
data class DirectoryAction(
    val context: DirectoryContext,
    val threadId: UUID = UUID.randomUUID(),
    val operationId: UUID = UUID.randomUUID(),
    val revision: Long = 0,
    val title: String? = null,
    val archived: Boolean? = null,
)
data class DirectoryChange(val view: DirectoryView, val confirmed: Boolean = false)

enum class DirectoryMode { LOCAL_ONLY, CHECKING, SHARED, OFFLINE, ERROR, UNCERTAIN }
data class DirectoryView(
    val mode: DirectoryMode = DirectoryMode.LOCAL_ONLY,
    val context: DirectoryContext? = null,
    val detail: String? = null,
    val pendingOperation: String? = null,
) {
    val writable get() = mode == DirectoryMode.SHARED
    val explanation get() = detail ?: when (mode) {
        DirectoryMode.LOCAL_ONLY -> "New names and empty threads are saved on this device only. Cached shared names remain shared. Messages still go to everyone in this conversation."
        DirectoryMode.CHECKING -> "Checking shared thread support…"
        DirectoryMode.SHARED -> "Shared names are visible to participants. Existing local names stay private."
        DirectoryMode.OFFLINE -> "Offline. Cached shared names remain shared; shared changes are unavailable."
        DirectoryMode.ERROR -> "Directory unavailable. Cached names are unchanged. Refresh to try again."
        DirectoryMode.UNCERTAIN -> "A shared change may have reached the server. Retry the saved operation to confirm; do not create it again."
    }
}
fun directoryScope(accountBare: String, peer: String, kind: MessageKind): ThreadDirectoryScope =
    if (kind == MessageKind.GROUPCHAT) ThreadDirectoryScope.Muc(peer)
    else ThreadDirectoryScope.Direct(accountBare, peer)
