package org.thanosapollo.nema.storage

import kotlinx.coroutines.flow.Flow

class PeerIdentityStore(private val dao: MessageDao) {
    fun observePeers(accountId: String): Flow<List<PeerEntity>> = dao.observePeers(accountId)

    suspend fun peer(accountId: String, peerJid: String): PeerEntity? = dao.peer(accountId, peerJid)

    suspend fun saveSuccess(peer: PeerEntity) {
        dao.savePeerRemoteIdentity(peer)
    }

    suspend fun saveLocalNickname(accountId: String, peerJid: String, nickname: String) {
        dao.savePeerNickname(accountId, peerJid, nickname.trim().ifEmpty { null })
    }

    suspend fun saveDisplayName(accountId: String, peerJid: String, displayName: String) {
        dao.savePeerDisplayName(accountId, peerJid, displayName)
    }

    suspend fun saveRoom(accountId: String, peerJid: String, room: Boolean) {
        dao.savePeerRoom(accountId, peerJid, room)
    }

    suspend fun saveFailure(
        accountId: String,
        peerJid: String,
        failureAtMs: Long,
    ) {
        dao.savePeerVCardFailure(accountId, peerJid, failureAtMs)
    }
}
