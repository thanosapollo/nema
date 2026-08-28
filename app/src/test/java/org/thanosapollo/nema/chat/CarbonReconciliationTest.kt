package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jxmpp.jid.impl.JidCreate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.ArchivePage
import org.thanosapollo.nema.storage.ArchivedIncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.smack.CarbonCarrier
import org.thanosapollo.nema.xmpp.smack.setThreadRef
import org.thanosapollo.nema.xmpp.smack.toIncomingEnvelope
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarbonReconciliationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var store: MessageStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "carbon-reconciliation-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null))
        store = MessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `Carbon MAM and live identity overlap stays one row`() = runBlocking {
        carbon("sent-carbon-mam", outbound = true)
        mam("sent-carbon-mam", outbound = true)
        carbon("received-carbon-mam")
        mam("received-carbon-mam")
        mam("mam-carbon")
        carbon("mam-carbon")
        carbon("self-overlap", outbound = true, peer = SELF)
        mam("self-overlap", outbound = true, peer = SELF)
        carbon("duplicate-carbon")
        carbon("duplicate-carbon")
        live("original-carbon")
        carbon("original-carbon")

        assertEquals(6, store.messages(ACCOUNT).size)
        assertEquals(1, store.messages(ACCOUNT).count { it.peerJid == SELF })
        assertEquals(
            setOf(MessageDirection.INBOUND, MessageDirection.OUTBOUND),
            store.messages(ACCOUNT).map { it.direction }.toSet(),
        )
    }

    @Test
    fun `own Carbon without private confirms exact identity but is not global sync`() = runBlocking {
        val first = compose("first")
        val second = compose("second")
        val thread = ThreadRef(ThreadId.require(requireNotNull(
            store.messages(ACCOUNT).single { it.body == "first" }.threadId,
        )))

        ingest(message("first", outbound = true, thread = thread), CarbonCarrier.Direction.SENT)

        assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, first)?.status)
        assertEquals(OutboxStatus.UNCERTAIN, store.outbox(ACCOUNT, second)?.status)
        assertEquals(2, store.messages(ACCOUNT).size)
    }

    @Test
    fun `bodyless Carbon is inert and Carbon receipt request never auto replies`() = runBlocking {
        val bodyless = stanza("bodyless", outbound = false, body = null)
        assertNull(bodyless.toIncomingEnvelope(ATTEMPT, SELF, carbonDirection = CarbonCarrier.Direction.RECEIVED))

        val requesting = stanza("request", outbound = false, receiptRequested = true)
        val carbon = requireNotNull(
            requesting.toIncomingEnvelope(ATTEMPT, SELF, carbonDirection = CarbonCarrier.Direction.RECEIVED),
        )
        assertFalse(carbon.receiptRequested)
        LiveMessageAdapter(store).ingest(carbon)
        assertEquals(1, store.messages(ACCOUNT).size)
    }

    private suspend fun carbon(id: String, outbound: Boolean = false, peer: String = PEER) =
        ingest(message(id, outbound, peer), if (outbound) CarbonCarrier.Direction.SENT else CarbonCarrier.Direction.RECEIVED)

    private suspend fun live(id: String) = ingest(message(id, outbound = false), null)

    private suspend fun ingest(message: Message, direction: CarbonCarrier.Direction?) {
        LiveMessageAdapter(store, localIds = { "local-${UUID.randomUUID()}" }).ingest(
            requireNotNull(
                message.toIncomingEnvelope(
                    ATTEMPT,
                    SELF,
                    suppliedSentAtEpochMs = direction?.let { 2L },
                    suppliedSentTimeSource = direction?.let { MessageTimeSource.CARBON },
                    carbonDirection = direction,
                ),
            ),
        )
    }

    private suspend fun mam(id: String, outbound: Boolean = false, peer: String = PEER) {
        val envelope = requireNotNull(
            message(id, outbound, peer).toIncomingEnvelope(
                ATTEMPT,
                SELF,
                suppliedSentAtEpochMs = 1,
                suppliedSentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.applyArchivePage(
            ArchivePage(
                ArchiveCursorKey(ACCOUNT, SELF, "scope-$id"),
                ArchiveDirection.BOOTSTRAP,
                boundaryId = null,
                complete = true,
                hasEarlier = false,
                stable = true,
                firstId = "mam-$id",
                lastId = "mam-$id",
                messages = listOf(ArchivedIncomingMessage("mam-$id", envelope.toIncomingMessage("archive-$id"))),
            ),
        )
    }

    private suspend fun compose(id: String): String {
        val operation = "operation-$id"
        store.composeDirectDraft(ACCOUNT, operation, "local-$id", "origin-$id", PEER, SELF, id)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, operation, 5)))
        return operation
    }

    private fun message(id: String, outbound: Boolean, peer: String = PEER, thread: ThreadRef? = null) =
        stanza(id, outbound, peer, originId = "origin-$id", thread = thread)

    private fun stanza(
        id: String,
        outbound: Boolean,
        peer: String = PEER,
        body: String? = id,
        originId: String? = null,
        receiptRequested: Boolean = false,
        thread: ThreadRef? = null,
    ): Message {
        val builder = StanzaBuilder.buildMessage("wire-$id")
            .from(JidCreate.entityFullFrom(if (outbound) "$SELF/other" else "$peer/device"))
            .to(JidCreate.entityFullFrom(if (outbound) "$peer/device" else "$SELF/device"))
            .ofType(Message.Type.chat)
        body?.let(builder::setBody)
        originId?.let { builder.addExtension(OriginIdElement(it)) }
        thread?.let(builder::setThreadRef)
        if (receiptRequested) builder.addExtension(DeliveryReceiptRequest())
        return builder.build()
    }

    private companion object {
        const val ACCOUNT = "account"
        const val SELF = "account@example.org"
        const val PEER = "peer@example.org"
        val ATTEMPT = SessionAttemptIdentity(
            AccountId.require(ACCOUNT),
            ConnectionGeneration.require(5),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
    }
}
