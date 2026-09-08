package org.thanosapollo.nema.storage

import androidx.room.*
import org.thanosapollo.nema.xmpp.threads.*
import java.util.UUID
import org.thanosapollo.nema.thread.MessageKind

@Entity(tableName = "shared_threads", primaryKeys = ["accountId", "peerJid", "messageKind", "threadId"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)])
data class SharedThreadEntity(
    val accountId: String, val peerJid: String, val messageKind: MessageKind, val threadId: String,
    val authority: String, val incarnation: String, val title: String, val revision: Long,
    val archived: Boolean, val canModify: Boolean, val retired: Boolean = false,
)

/** One durable, exact intent per conversation. A process restart never invents a new retry UUID. */
@Entity(tableName = "thread_directory_intents", primaryKeys = ["accountId", "peerJid", "messageKind"],
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)])
data class DirectoryIntentEntity(
    val accountId: String, val peerJid: String, val messageKind: MessageKind,
    val authority: String, val incarnation: String, val threadId: String, val operationId: String,
    val revision: Long, val title: String?, val archived: Boolean?,
) {
    fun action(bareJid: String) = DirectoryAction(
        DirectoryContext(authority, if (messageKind == MessageKind.GROUPCHAT) ThreadDirectoryScope.Muc(peerJid, incarnation)
            else ThreadDirectoryScope.Direct(bareJid, peerJid)),
        UUID.fromString(threadId), UUID.fromString(operationId), revision, title, archived,
    )
}

@Dao
internal abstract class SharedThreadDao {
    @Query("SELECT * FROM shared_threads WHERE accountId = :account AND peerJid = :peer AND messageKind = :kind")
    abstract suspend fun rows(account: String, peer: String, kind: MessageKind): List<SharedThreadEntity>
    @Upsert abstract suspend fun put(row: SharedThreadEntity)
    @Query("UPDATE shared_threads SET retired = 1, archived = 1, canModify = 0 WHERE accountId = :account AND peerJid = :peer AND messageKind = :kind")
    abstract suspend fun retire(account: String, peer: String, kind: MessageKind)
    @Query("SELECT * FROM thread_directory_intents WHERE accountId = :account AND peerJid = :peer AND messageKind = :kind")
    abstract suspend fun intent(account: String, peer: String, kind: MessageKind): DirectoryIntentEntity?
    @Insert abstract suspend fun putIntent(intent: DirectoryIntentEntity)
    @Query("DELETE FROM thread_directory_intents WHERE accountId = :account AND peerJid = :peer AND messageKind = :kind AND operationId = :operation")
    abstract suspend fun removeIntent(account: String, peer: String, kind: MessageKind, operation: String)
}

internal class SharedThreadStore(private val database: NemaDatabase) {
    private val dao get() = database.sharedThreadDao()
    suspend fun intent(account: String, peer: String, kind: MessageKind) = dao.intent(account, peer, kind)
    suspend fun prepare(account: String, bare: String, peer: String, kind: MessageKind, action: DirectoryAction) = database.withTransaction {
        require(database.accountDao().account(account)?.bareJid == bare)
        validateScope(bare, peer, kind, action.context.authority, action.context.scope)
        require(action.revision in 0 until MAX_WIRE_INTEGER && ((action.title != null) xor (action.archived != null)))
        require(action.title == null || validDirectoryTitle(action.title))
        require(action.revision != 0L || action.title != null)
        val incarnation = (action.context.scope as? ThreadDirectoryScope.Muc)?.incarnation.orEmpty()
        if (action.revision > 0) {
            val row = dao.rows(account, peer, kind).single { it.threadId == action.threadId.toString() }
            require(!row.retired && row.canModify && row.authority == action.context.authority &&
                row.incarnation == incarnation && row.revision == action.revision)
        }
        check(dao.intent(account, peer, kind) == null)
        DirectoryIntentEntity(account, peer, kind, action.context.authority, incarnation,
            action.threadId.toString(), action.operationId.toString(), action.revision, action.title, action.archived)
            .also { dao.putIntent(it) }
    }
    suspend fun reject(intent: DirectoryIntentEntity) = dao.removeIntent(intent.accountId, intent.peerJid, intent.messageKind, intent.operationId)
    suspend fun snapshot(account: String, bare: String, peer: String, kind: MessageKind, result: ThreadDirectorySnapshot) = database.withTransaction {
        require(database.accountDao().account(account)?.bareJid == bare)
        require(result.account == bare)
        validateScope(bare, peer, kind, result.authority, result.scope)
        require(result.items.size <= DIRECTORY_CAPACITY && result.items.map { it.id }.distinct().size == result.items.size)
        val incarnation = (result.scope as? ThreadDirectoryScope.Muc)?.incarnation.orEmpty()
        // Preflight every row before retiring anything. Never rewrite canonical message lineage.
        val previous = dao.rows(account, peer, kind).associateBy { it.threadId }
        result.items.forEach { validateItem(previous[it.id.toString()], result.authority, incarnation, it) }
        dao.retire(account, peer, kind)
        result.items.forEach { publish(account, peer, kind, result.authority, incarnation, it) }
    }
    suspend fun confirmed(intent: DirectoryIntentEntity, bare: String, result: ThreadDirectoryMutationResult) = database.withTransaction {
        require(database.accountDao().account(intent.accountId)?.bareJid == bare)
        val action = intent.action(bare)
        require(result.account == bare && result.authority == intent.authority && result.scope == action.context.scope &&
            result.operationId == action.operationId && result.item.id == action.threadId)
        val previous = dao.rows(intent.accountId, intent.peerJid, intent.messageKind).firstOrNull { it.threadId == result.item.id.toString() }
        validateItem(previous, intent.authority, intent.incarnation, result.item)
        publish(intent.accountId, intent.peerJid, intent.messageKind, intent.authority, intent.incarnation, result.item)
        reject(intent)
    }
    private fun validateItem(previous: SharedThreadEntity?, authority: String, incarnation: String, item: ThreadDirectoryItem) {
        require(validDirectoryTitle(item.title) && item.revision in 1..MAX_WIRE_INTEGER)
        require(previous == null || (previous.authority == authority && previous.incarnation == incarnation && item.revision >= previous.revision))
        require(previous == null || item.revision != previous.revision || (previous.title == item.title && (previous.retired || previous.archived == item.archived)))
    }
    private suspend fun publish(account: String, peer: String, kind: MessageKind, authority: String, incarnation: String, item: ThreadDirectoryItem) {
        val messages = database.messageDao()
        messages.insertPeer(PeerEntity(account, peer, room = kind == MessageKind.GROUPCHAT))
        // INSERT IGNORE preserves pre-existing parent lineage and message identity.
        messages.insertThread(MessageThreadEntity(account, peer, kind, item.id.toString(), null))
        dao.put(SharedThreadEntity(account, peer, kind, item.id.toString(), authority, incarnation,
            item.title, item.revision, item.archived, item.canModify))
    }
    private fun validateScope(bare: String, peer: String, kind: MessageKind, authority: String, scope: ThreadDirectoryScope) {
        require(authority == canonicalBare(bare).domain.toString())
        when (scope) {
            is ThreadDirectoryScope.Direct -> require(kind == MessageKind.CHAT && setOf(scope.a, scope.b) == setOf(bare, peer))
            is ThreadDirectoryScope.Muc -> require(kind == MessageKind.GROUPCHAT && scope.room == peer && scope.incarnation != null)
        }
    }
}
