package org.thanosapollo.nema.storage

import kotlinx.coroutines.flow.Flow

data class RosterMember(val bareJid: String, val name: String?)

data class CompleteRosterSnapshot(
    val accountId: String,
    val members: List<RosterMember>,
)

class RosterStore(database: NemaDatabase) {
    private val dao = database.messageDao()

    suspend fun reconcile(snapshot: CompleteRosterSnapshot) = dao.reconcileRoster(snapshot)

    fun observe(accountId: String): Flow<List<PeerEntity>> = dao.observeRoster(accountId)
}