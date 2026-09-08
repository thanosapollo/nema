package org.thanosapollo.nema.xmpp.threads

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smackx.disco.packet.DiscoverInfo

/**
 * One authenticated Smack session's experimental directory client. No storage, background work,
 * federation, or message-ID migration. The owner must discard this instance when replacing its session.
 *
 * Callers retain the exact operation UUID and original arguments across uncertain mutation outcomes.
 * Only the server's last operation is replayable; older retries conflict and require explicit readback.
 * This client never retries a mutation automatically, nor treats canModify as authorization to send one.
 */
class SmackThreadDirectoryClient(
    private val connection: XMPPConnection,
    private val send: (IQ) -> org.jivesoftware.smack.StanzaCollector = connection::createStanzaCollectorAndSend,
) {
    private val owner = connection.user ?: throw ThreadDirectoryException.SessionChanged()
    private val authority = connection.xmppServiceDomain
    private val account = owner.asEntityBareJidString()

    init {
        checkSession()
        ThreadDirectoryIqProvider.install()
    }

    /** False only for an absent disco feature or an explicit unsupported-protocol error. */
    suspend fun isSupported(): Boolean = blocking {
        try {
            discover()
            true
        } catch (_: ThreadDirectoryException.Unsupported) {
            false
        }
    }

    /** At most three complete scan attempts; no partially collected directory escapes on failure. */
    suspend fun list(scope: ThreadDirectoryScope): ThreadDirectorySnapshot = blocking {
        checkScope(scope)
        discover()
        repeat(3) { attempt ->
            try {
                return@blocking scan(scope)
            } catch (conflict: ThreadDirectoryException.Conflict) {
                if (attempt == 2) throw conflict
            }
        }
        error("Unreachable scan state")
    }

    suspend fun create(
        scope: ThreadDirectoryScope,
        id: UUID,
        title: String,
        operationId: UUID,
    ): ThreadDirectoryMutationResult {
        require(validDirectoryTitle(title)) { "Invalid thread directory title" }
        return mutate(scope, id, operationId, "create", mapOf("title" to title), 1)
    }

    suspend fun rename(
        scope: ThreadDirectoryScope,
        id: UUID,
        expectedRevision: Long,
        title: String,
        operationId: UUID,
    ): ThreadDirectoryMutationResult {
        require(validDirectoryTitle(title)) { "Invalid thread directory title" }
        require(expectedRevision in 1 until MAX_WIRE_INTEGER)
        return mutate(scope, id, operationId, "rename", mapOf(
            "expected" to expectedRevision.toString(), "title" to title,
        ), expectedRevision + 1)
    }

    suspend fun archive(
        scope: ThreadDirectoryScope,
        id: UUID,
        expectedRevision: Long,
        archived: Boolean,
        operationId: UUID,
    ): ThreadDirectoryMutationResult {
        require(expectedRevision in 1 until MAX_WIRE_INTEGER)
        return mutate(scope, id, operationId, "archive", mapOf(
            "expected" to expectedRevision.toString(), "archived" to archived.toString(),
        ), expectedRevision + 1)
    }

    private fun scan(scope: ThreadDirectoryScope): ThreadDirectorySnapshot {
        var snapshot: String? = null
        var total: Int? = null
        var after: String? = null
        val items = linkedMapOf<UUID, ThreadDirectoryItem>()
        repeat((DIRECTORY_CAPACITY + DIRECTORY_PAGE_SIZE - 1) / DIRECTORY_PAGE_SIZE) {
            val fields = scope.attributes() + buildMap {
                put("op", "list")
                snapshot?.let { put("snapshot", it) }
                after?.let { put("after", it) }
            }
            val response = directory(fields, IQ.Type.get, scope, setOf("complete"), setOf("after"))
            val currentSnapshot = response.fields.getValue("snapshot")
            val currentTotal = wireInteger(response.fields["total"]).toInt()
            malformedUnless(snapshot == null || snapshot == currentSnapshot, "snapshot changed without conflict")
            malformedUnless(total == null || total == currentTotal, "total changed within snapshot")
            snapshot = currentSnapshot
            total = currentTotal
            val page = response.rows.map(::item)
            var previous = after
            for (entry in page) {
                val id = entry.id.toString()
                malformedUnless(previous == null || id > previous, "unordered or repeated cursor/item")
                malformedUnless(items.put(entry.id, entry) == null, "duplicate thread")
                previous = id
            }
            malformedUnless(items.size <= currentTotal, "too many threads")
            val complete = wireBoolean(response.fields["complete"])
            val next = response.fields["after"]
            if (complete) {
                malformedUnless(next == null && items.size == currentTotal, "incomplete final page")
                val boundScope = if (scope is ThreadDirectoryScope.Muc) {
                    scope.copy(incarnation = response.fields.getValue("incarnation"))
                } else scope
                return ThreadDirectorySnapshot(account, authority.toString(), boundScope, currentSnapshot, items.values.toList())
            }
            malformedUnless(page.size == DIRECTORY_PAGE_SIZE && items.size < currentTotal &&
                next != null && next == page.last().id.toString(), "invalid continuation")
            after = next
        }
        throw ThreadDirectoryException.Malformed("page bound exceeded")
    }

    private suspend fun mutate(
        scope: ThreadDirectoryScope,
        id: UUID,
        operationId: UUID,
        operation: String,
        changes: Map<String, String>,
        revision: Long,
    ): ThreadDirectoryMutationResult = blocking {
        checkScope(scope)
        val incarnation = if (scope is ThreadDirectoryScope.Muc) {
            mapOf("incarnation" to requireNotNull(scope.incarnation) { "List the MUC directory before authoring a mutation" })
        } else emptyMap()
        discover()
        val fields = scope.attributes() + incarnation + changes + mapOf(
            "op" to operation, "id" to id.toString(), "operation" to operationId.toString(),
        )
        val response = directory(fields, IQ.Type.set, scope, setOf("operation", "replayed"))
        malformedUnless(response.fields["operation"] == operationId.toString(), "operation mismatch")
        malformedUnless(response.rows.size == 1, "mutation entry count")
        val entry = item(response.rows.single())
        malformedUnless(entry.id == id && entry.revision == revision && entry.canModify, "mutation identity/revision/permission")
        changes["title"]?.let { malformedUnless(entry.title == it, "mutation title") }
        changes["archived"]?.let { malformedUnless(entry.archived.toString() == it, "mutation archive state") }
        if (operation == "create") malformedUnless(!entry.archived, "created archived thread")
        val total = wireInteger(response.fields["total"], 1).toInt()
        ThreadDirectoryMutationResult(account, authority.toString(), scope, response.fields.getValue("snapshot"),
            total, operationId, wireBoolean(response.fields["replayed"]), entry)
    }

    private fun directory(
        fields: Map<String, String>,
        type: IQ.Type,
        scope: ThreadDirectoryScope,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ): ThreadDirectoryIq {
        val raw = exchange(ThreadDirectoryIq(fields).also { it.type = type })
        val response = raw as? ThreadDirectoryIq ?: throw ThreadDirectoryException.Malformed("missing directory payload")
        malformedUnless(response.structurallyValid && response.extensions.isEmpty(), "payload structure")
        val scopeFields = scope.attributes()
        val keys = scopeFields.keys + setOf("snapshot", "total") + required +
            (if (scope is ThreadDirectoryScope.Muc) setOf("incarnation") else emptySet())
        malformedUnless(response.fields.keys.containsAll(keys) && response.fields.keys.all { it in keys || it in optional }, "directory attributes")
        malformedUnless(scopeFields.all { (key, value) -> response.fields[key] == value }, "scope mismatch")
        val snapshot = response.fields.getValue("snapshot")
        malformedUnless(snapshot.matches(Regex("[0-9a-f]{64}:(0|[1-9][0-9]{0,15})")), "snapshot syntax")
        wireInteger(snapshot.substringAfter(':'))
        if (scope is ThreadDirectoryScope.Muc) {
            val incarnation = response.fields.getValue("incarnation")
            malformedUnless(incarnation == snapshot.substringBefore(':'), "incarnation/snapshot mismatch")
            if (scope.incarnation != null && scope.incarnation != incarnation) throw ThreadDirectoryException.Conflict()
        }
        malformedUnless(wireInteger(response.fields["total"]) <= DIRECTORY_CAPACITY, "directory capacity")
        return response
    }

    private fun item(fields: Map<String, String>): ThreadDirectoryItem {
        malformedUnless(fields.keys == setOf("id", "title", "revision", "archived", "can_modify"), "thread attributes")
        val id = fields.getValue("id")
        malformedUnless(directoryUuidPattern.matches(id), "thread ID")
        val title = fields.getValue("title")
        malformedUnless(validDirectoryTitle(title), "thread title")
        return ThreadDirectoryItem(UUID.fromString(id), title, wireInteger(fields["revision"], 1),
            wireBoolean(fields["archived"]), wireBoolean(fields["can_modify"]))
    }

    private fun discover() {
        val info = exchange(DiscoverInfo().also { it.type = IQ.Type.get }) as? DiscoverInfo
            ?: throw ThreadDirectoryException.Malformed("missing disco info")
        malformedUnless(info.node == null, "unexpected disco node")
        if (!info.containsFeature(THREAD_DIRECTORY_NAMESPACE)) throw ThreadDirectoryException.Unsupported()
    }

    private fun exchange(request: IQ): IQ {
        checkSession()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        request.to = authority
        val collector = send(request)
        val response = try {
            collector.nextResult<IQ>() ?: throw ThreadDirectoryException.Transport()
        } finally {
            collector.cancel()
        }
        checkSession()
        malformedUnless(response.stanzaId == request.stanzaId && response.from == authority && response.to == owner,
            "IQ sender/recipient/ID")
        if (response.type == IQ.Type.error) {
            when (val condition = response.error?.condition?.toString()) {
                "feature-not-implemented", "service-unavailable" -> throw ThreadDirectoryException.Unsupported()
                "conflict" -> throw ThreadDirectoryException.Conflict()
                null -> throw ThreadDirectoryException.Malformed("missing stanza error")
                else -> throw ThreadDirectoryException.Rejected(condition)
            }
        }
        malformedUnless(response.type == IQ.Type.result && response.error == null, "IQ result type")
        return response
    }

    private fun checkSession() {
        if (!connection.isAuthenticated || connection.user != owner || connection.xmppServiceDomain != authority) {
            throw ThreadDirectoryException.SessionChanged()
        }
    }

    private fun checkScope(scope: ThreadDirectoryScope) {
        if (scope is ThreadDirectoryScope.Direct) {
            require(account == scope.a || account == scope.b) { "Direct scope must include this account" }
            require(canonicalBare(scope.a).domain == authority) { "Only same-server direct directories are supported" }
        }
    }

    private suspend fun <T> blocking(action: () -> T): T = runInterruptible(Dispatchers.IO) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (failure: ThreadDirectoryException) {
            throw failure
        } catch (invalid: IllegalArgumentException) {
            throw invalid
        } catch (failure: Exception) {
            throw ThreadDirectoryException.Transport(failure)
        }
    }
}
