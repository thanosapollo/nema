package org.thanosapollo.nema.storage

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.thanosapollo.nema.account.AccountBareJid
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.account.NetworkEndpoint
import org.thanosapollo.nema.xmpp.transport.AccountId

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val bareJid: String,
    val authenticationId: String,
    val authorizationId: String?,
    val serviceDomain: String,
    val networkHost: String?,
    val networkPort: Int?,
)

@Entity(
    tableName = "active_account",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["accountId"], unique = true)],
)
data class ActiveAccountEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val accountId: String,
) {
    init {
        require(singletonId == SINGLETON_ID) { "Only one active-account row is allowed" }
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
abstract class AccountDao {
    @Upsert
    abstract suspend fun upsert(account: AccountEntity)

    @Transaction
    open suspend fun saveBound(account: AccountEntity) {
        val existing = account(account.id)
        require(existing == null || existing.bareJid == account.bareJid) {
            "Account identity cannot be changed"
        }
        upsert(account)
    }

    @Upsert
    protected abstract suspend fun setActive(activeAccount: ActiveAccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    abstract suspend fun account(id: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY id")
    abstract suspend fun allAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY id")
    abstract fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE bareJid = :bareJid ORDER BY id LIMIT 1")
    abstract suspend fun accountByBareJid(bareJid: String): AccountEntity?

    @Query("DELETE FROM trusted_identity_aliases WHERE accountId = :accountId")
    protected abstract suspend fun deleteIdentityAliases(accountId: String): Int

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    protected abstract suspend fun deleteMessages(accountId: String): Int

    @Query("DELETE FROM direct_thread_sessions WHERE accountId = :accountId")
    protected abstract suspend fun deleteDirectThreadSessions(accountId: String): Int

    @Query("UPDATE message_threads SET parentThreadId = NULL WHERE accountId = :accountId")
    protected abstract suspend fun detachChildThreads(accountId: String): Int

    @Query("DELETE FROM message_threads WHERE accountId = :accountId")
    protected abstract suspend fun deleteThreads(accountId: String): Int

    @Query("DELETE FROM accounts WHERE id = :accountId")
    protected abstract suspend fun deleteAccount(accountId: String): Int

    @Query(
        """
        SELECT accounts.* FROM accounts
        INNER JOIN active_account ON active_account.accountId = accounts.id
        WHERE active_account.singletonId = 1
        """,
    )
    abstract fun observeActiveAccount(): Flow<AccountEntity?>

    @Query("DELETE FROM chat_navigation WHERE accountId = :accountId")
    protected abstract suspend fun clearNavigation(accountId: String): Int

    @Transaction
    open suspend fun activate(accountId: String) {
        requireNotNull(account(accountId)) { "Cannot activate an unknown account" }
        setActive(ActiveAccountEntity(accountId = accountId))
    }

    @Transaction
    open suspend fun switchActive(accountId: String) {
        requireNotNull(account(accountId)) { "Cannot activate an unknown account" }
        clearNavigation(accountId)
        setActive(ActiveAccountEntity(accountId = accountId))
    }

    @Transaction
    open suspend fun remove(accountId: String) {
        requireNotNull(account(accountId)) { "Cannot remove an unknown account" }
        deleteIdentityAliases(accountId)
        deleteMessages(accountId)
        deleteDirectThreadSessions(accountId)
        detachChildThreads(accountId)
        deleteThreads(accountId)
        check(deleteAccount(accountId) == 1) { "Account changed during removal" }
    }
}

@Database(
    entities = [
        AccountEntity::class,
        ActiveAccountEntity::class,
        PeerEntity::class,
        MessageThreadEntity::class,
        DirectThreadSessionEntity::class,
        MessageThreadTitleEntity::class,
        MessageEntity::class,
        ArchiveMessagePositionEntity::class,
        TrustedIdentityAliasEntity::class,
        IdentityConflictEntity::class,
        OutboxEntity::class,
        ArchiveCursorEntity::class,
        MessageDraftEntity::class,
        AccountMessageSequenceEntity::class,
        ChatNavigationEntity::class,
        MessageReactionEntity::class,
    ],
    version = 20,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
    exportSchema = true,
)
@TypeConverters(MessageConverters::class)
abstract class NemaDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    internal abstract fun messageDao(): MessageDao

    companion object {
        fun create(
            context: Context,
            databaseName: String = "nema.db",
        ): NemaDatabase = Room.databaseBuilder(
            context.applicationContext,
            NemaDatabase::class.java,
            databaseName,
        )
            .addMigrations(
                MessageSchema.MIGRATION_8_9,
                MessageSchema.MIGRATION_9_10,
                MessageSchema.MIGRATION_10_11,
                MessageSchema.MIGRATION_11_12,
                MessageSchema.MIGRATION_12_13,
                MessageSchema.MIGRATION_13_14,
                MessageSchema.MIGRATION_14_15,
                MessageSchema.MIGRATION_15_16,
                MessageSchema.MIGRATION_16_17,
                MessageSchema.MIGRATION_17_18,
                MessageSchema.MIGRATION_18_19,
                MessageSchema.MIGRATION_19_20,
            )
            .addCallback(MessageSchema.REOPEN_CALLBACK)
            .build()
    }
}

class AccountRepository(private val dao: AccountDao) {
    val configuredAccounts: Flow<List<AccountConfiguration>> = dao.observeAccounts().map { accounts ->
        accounts.map(AccountEntity::toConfiguration)
    }
    val activeAccount: Flow<AccountConfiguration?> = dao.observeActiveAccount().map {
        it?.toConfiguration()
    }

    suspend fun save(configuration: AccountConfiguration) {
        dao.saveBound(configuration.toEntity())
    }

    suspend fun activate(accountId: AccountId) {
        dao.activate(accountId.value)
    }

    suspend fun switchActive(accountId: AccountId) {
        dao.switchActive(accountId.value)
    }

    suspend fun remove(accountId: AccountId) {
        dao.remove(accountId.value)
    }

    suspend fun account(accountId: AccountId): AccountConfiguration? =
        dao.account(accountId.value)?.toConfiguration()

    suspend fun accountByBareJid(bareJid: AccountBareJid): AccountConfiguration? =
        dao.accountByBareJid(bareJid.value)?.toConfiguration()
}

private fun AccountConfiguration.toEntity() = AccountEntity(
    id = id.value,
    bareJid = bareJid.value,
    authenticationId = authenticationId.value,
    authorizationId = authorizationId?.value,
    serviceDomain = serviceDomain.value,
    networkHost = networkEndpoint?.host,
    networkPort = networkEndpoint?.port,
)

private fun AccountEntity.toConfiguration() = AccountConfiguration.create(
    id = AccountId.require(id),
    bareJid = bareJid,
    authenticationId = authenticationId,
    authorizationId = authorizationId,
    serviceDomain = serviceDomain,
    networkEndpoint = if (networkHost == null && networkPort == null) {
        null
    } else {
        NetworkEndpoint.create(
            requireNotNull(networkHost) { "Network host and port must be stored together" },
            requireNotNull(networkPort) { "Network host and port must be stored together" },
        )
    },
)
