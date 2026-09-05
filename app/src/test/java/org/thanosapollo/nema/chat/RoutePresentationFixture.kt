package org.thanosapollo.nema.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.AccountId

internal class RoutePresentationFixture : AutoCloseable {
    val gate = RouteQueryGate()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val database = Room.inMemoryDatabaseBuilder(context, NemaDatabase::class.java)
        .setQueryCoroutineContext(gate).allowMainThreadQueries().build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val repository = ChatRepository(database)
    val room = kotlinx.coroutines.flow.MutableStateFlow<org.thanosapollo.nema.xmpp.muc.RoomView?>(null)
    val joins = java.util.concurrent.atomic.AtomicInteger()
    val failLive = CompletableDeferred<Unit>()
    val sendEntered = CompletableDeferred<Unit>()
    val sendRelease = CompletableDeferred<Unit>()
    val presenters = mutableListOf<DirectChatPresenter>()
    val account = "route-account"
    val peer = "a@example.org"
    val other = "b@example.org"

    init {
        runBlocking {
            for (id in listOf(account, "replacement")) {
                database.accountDao().upsert(AccountEntity(id, "$id@example.org", id, null, "example.org", null, null))
            }
            for (index in 0..30) {
                val address = when (index) { 0 -> peer; 1 -> other; else -> "peer-$index@example.org" }
                MessageStore(database).ingest(IncomingMessage(
                    accountId = account, localMessageId = "message-$index", peerJid = address,
                    senderJid = address, direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT,
                    threadId = null, parentThreadId = null, body = "body-$index", archiveOrdinal = null, aliases = emptyList(),
                ))
            }
            repository.saveDraft(DirectConversationKey(account, peer), "stored A", DraftReply("reply", peer, "quoted", "A"))
            repository.saveDraft(DirectConversationKey(account, other), "stored B")
            repository.saveDraft(DirectConversationKey("replacement", peer), "other account draft")
        }
    }

    fun presenter(id: String = account) = DirectChatPresenter(
        AccountConfiguration.create(AccountId.require(id), "$id@example.org", id, null, "example.org", null),
        repository, scope,
        enqueue = { _, _ -> sendEntered.complete(Unit); sendRelease.await(); true },
        joinMuc = { joins.incrementAndGet(); true },
        observeRoom = { room },
        observeRtt = { selected -> flow {
            emit(null)
            if (selected == other) { failLive.await(); error("controlled stream failure") }
        } },
    ).also(presenters::add)

    override fun close() {
        gate.release()
        sendRelease.complete(Unit)
        presenters.forEach { it.close() }
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
        database.close()
    }
}
