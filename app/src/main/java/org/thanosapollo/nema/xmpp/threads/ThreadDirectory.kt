package org.thanosapollo.nema.xmpp.threads

import java.util.UUID
import org.jxmpp.jid.impl.JidCreate

/** Experimental server-owned metadata; this does not constrain legacy XEP-0201 message IDs. */
sealed interface ThreadDirectoryScope {
    data class Direct(val a: String, val b: String) : ThreadDirectoryScope {
        init {
            val first = canonicalBare(a)
            val second = canonicalBare(b)
            require(first.domain == second.domain && first != second)
        }
    }

    /**
     * Use an unbound room for initial lookup. Mutations require the incarnation returned by list;
     * retain it when authoring/retrying an action, never replace it with a later lookup's token.
     * The server alone decides whether this is an authorized local persistent MUC.
     */
    data class Muc(val room: String, val incarnation: String? = null) : ThreadDirectoryScope {
        init {
            canonicalBare(room)
            require(incarnation == null || incarnation.matches(Regex("[0-9a-f]{64}")))
        }
    }
}

data class ThreadDirectoryItem(
    val id: UUID,
    val title: String,
    val revision: Long,
    val archived: Boolean,
    val canModify: Boolean,
)

/** Returned only after every page of one snapshot has been validated. Includes archived/empty threads. */
data class ThreadDirectorySnapshot(
    val account: String,
    val authority: String,
    val scope: ThreadDirectoryScope,
    val snapshot: String,
    val items: List<ThreadDirectoryItem>,
)

data class ThreadDirectoryMutationResult(
    val account: String,
    val authority: String,
    val scope: ThreadDirectoryScope,
    val snapshot: String,
    val total: Int,
    val operationId: UUID,
    val replayed: Boolean,
    val item: ThreadDirectoryItem,
)

sealed class ThreadDirectoryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unsupported : ThreadDirectoryException("Server does not support the experimental thread directory")
    class Malformed(detail: String) : ThreadDirectoryException("Invalid thread directory response: $detail")
    class Conflict : ThreadDirectoryException("Thread directory changed; read back and reconcile")
    class Rejected(val condition: String) : ThreadDirectoryException("Thread directory rejected: $condition")
    class SessionChanged : ThreadDirectoryException("Thread directory session is no longer authenticated as its owner")
    /** Mutations may have reached the authority. Retry only the exact operation UUID and original payload. */
    class Transport(cause: Throwable? = null) : ThreadDirectoryException("Thread directory response unavailable", cause)
}

internal const val THREAD_DIRECTORY_NAMESPACE = "urn:nema:threads:0"
internal const val DIRECTORY_PAGE_SIZE = 3
internal const val DIRECTORY_CAPACITY = 128
internal const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L
internal val directoryUuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun canonicalBare(value: String) = JidCreate.entityBareFrom(value).also {
    require(it.toString() == value) { "Expected a canonical entity bare JID" }
}

internal fun ThreadDirectoryScope.attributes(): Map<String, String> = when (this) {
    is ThreadDirectoryScope.Direct -> mapOf("kind" to "direct", "a" to a, "b" to b)
    is ThreadDirectoryScope.Muc -> mapOf("kind" to "muc", "room" to room)
}

/** Matches the experimental authority's UTF-8/code-point, control and whitespace limits. */
internal fun validDirectoryTitle(value: String): Boolean {
    if (value.toByteArray(Charsets.UTF_8).size > 512 || value.codePointCount(0, value.length) > 128 ||
        value.startsWith(' ') || value.endsWith(' ')) return false
    var nonspace = false
    var offset = 0
    while (offset < value.length) {
        val point = value.codePointAt(offset)
        if (point < 32 || point in 127..159 || point in 0xd800..0xdfff || point == 0x2028 || point == 0x2029) return false
        val space = point == 32 || point == 0xa0 || point == 0x1680 || point in 0x2000..0x200a ||
            point == 0x202f || point == 0x205f || point == 0x3000
        if (!space) nonspace = true
        offset += Character.charCount(point)
    }
    return nonspace
}

internal fun malformedUnless(condition: Boolean, detail: String) {
    if (!condition) throw ThreadDirectoryException.Malformed(detail)
}

internal fun wireInteger(value: String?, minimum: Long = 0): Long {
    malformedUnless(value != null && value.matches(Regex("0|[1-9][0-9]{0,15}")), "integer")
    val number = value?.toLongOrNull()
    malformedUnless(number != null && number in minimum..MAX_WIRE_INTEGER, "integer range")
    return checkNotNull(number)
}

internal fun wireBoolean(value: String?): Boolean = when (value) {
    "true" -> true
    "false" -> false
    else -> throw ThreadDirectoryException.Malformed("boolean")
}
