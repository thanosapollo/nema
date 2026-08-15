package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.sid.element.OriginIdElement
import org.jxmpp.jid.impl.JidCreate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.OutboundIntent
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.smack.toIncomingEnvelope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveMessageAdapterTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "live-message-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(
            AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun adapterNormalizesDirectSenderAndPersistsThreadMetadata() = runBlocking {
        val store = MessageStore(database)
        val adapter = LiveMessageAdapter(store, localIds = { "local-live" })

        adapter.ingest(
            IncomingMessageEnvelope(
                accountId = AccountId.require(ACCOUNT),
                generation = ConnectionGeneration.require(5),
                peer = PEER,
                sender = PEER,
                outbound = false,
                originId = "sender-origin",
                body = "body",
                thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent")),
            ),
        )

        val message = store.messages(ACCOUNT).single()
        assertEquals(PEER, message.peerJid)
        assertEquals(PEER, message.senderJid)
        assertEquals(MessageDirection.INBOUND, message.direction)
        assertEquals("child", message.threadId)
        assertEquals("parent", message.parentThreadId)
        val alias = store.aliases(ACCOUNT).single()
        assertEquals(PEER, alias.authority)
        assertEquals("sender-origin", alias.value)
    }

    @Test
    fun directMessageIdFallbackBecomesReplyableThroughMapperAndTimeline() = runBlocking {
        val message = StanzaBuilder.buildMessage("legacy-message-id")
            .from(JidCreate.entityFullFrom("$PEER/device"))
            .to(JidCreate.entityFullFrom("$SELF/device"))
            .ofType(Message.Type.chat)
            .setBody("legacy body")
            .build()
        val attempt = SessionAttemptIdentity(
            AccountId.require(ACCOUNT),
            ConnectionGeneration.require(5),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
        val store = MessageStore(database)

        LiveMessageAdapter(store, localIds = { "local-legacy" })
            .ingest(requireNotNull(message.toIncomingEnvelope(attempt, SELF)))

        assertEquals("legacy-message-id", ChatRepository(database).observeTimeline(ACCOUNT, PEER).first().single().replyReferenceId)
        assertEquals(IdentityAliasKind.MESSAGE_ID, store.aliases(ACCOUNT).single().kind)
    }

    @Test
    fun authoritativeOwnMessageOriginEchoConfirmsExistingOutboundRow() = runBlocking {
        val store = MessageStore(database)
        val intent = OutboundIntent(
            accountId = ACCOUNT,
            operationId = "operation",
            localMessageId = "local-outbound",
            originId = "stable-origin",
            peerJid = PEER,
            senderJid = SELF,
            messageKind = MessageKind.CHAT,
            threadId = null,
            parentThreadId = null,
            body = "echo body",
        )
        store.compose(intent)
        val claim = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 5))
        store.recordPotentialDelivery(claim)
        val message = StanzaBuilder.buildMessage("server-echo")
            .from(JidCreate.entityFullFrom("$SELF/device"))
            .to(JidCreate.entityFullFrom("$PEER/device"))
            .ofType(Message.Type.chat)
            .setBody(intent.body)
            .addExtension(OriginIdElement(intent.originId))
            .build()
        val attempt = SessionAttemptIdentity(
            AccountId.require(ACCOUNT),
            ConnectionGeneration.require(5),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
        val adapter = LiveMessageAdapter(store, localIds = { "server-copy" })

        adapter.ingest(requireNotNull(message.toIncomingEnvelope(attempt, SELF)))

        assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, intent.operationId)?.status)
        assertEquals(listOf(intent.localMessageId), store.messages(ACCOUNT).map { it.localMessageId })
    }

    @Test
    fun semanticReplyEchoesReconcileForDirectAndGroupchat() = runBlocking {
        suspend fun verify(kind: MessageKind, peer: String, sender: String, ownNick: String?) {
            val suffix = kind.name.lowercase()
            val intent = OutboundIntent(
                accountId = ACCOUNT,
                operationId = "operation-$suffix",
                localMessageId = "local-$suffix",
                originId = "origin-$suffix",
                peerJid = peer,
                senderJid = SELF,
                messageKind = kind,
                threadId = null,
                parentThreadId = null,
                body = "answer",
                replyToId = "target-$suffix",
                replyToJid = sender,
                replyFallbackBody = "original",
            )
            val store = MessageStore(database)
            store.compose(intent)
            store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, intent.operationId, 5)))
            val fallbackText = "> Peer wrote:\n> original\n"
            val reply = StandardExtensionElement.builder("reply", "urn:xmpp:reply:0")
                .addAttribute("id", intent.replyToId!!)
                .addAttribute("to", sender)
                .build()
            val fallback = StandardExtensionElement.builder("fallback", "urn:xmpp:fallback:0")
                .addAttribute("for", "urn:xmpp:reply:0")
                .addElement(
                    StandardExtensionElement.builder("body", "urn:xmpp:fallback:0")
                        .addAttribute("start", "0")
                        .addAttribute("end", fallbackText.length.toString())
                        .build(),
                )
                .build()
            val echo = StanzaBuilder.buildMessage("echo-$suffix")
                .from(JidCreate.entityFullFrom(if (kind == MessageKind.GROUPCHAT) "$peer/$ownNick" else "$SELF/device"))
                .to(JidCreate.from(if (kind == MessageKind.GROUPCHAT) SELF else "$peer/device"))
                .ofType(if (kind == MessageKind.GROUPCHAT) Message.Type.groupchat else Message.Type.chat)
                .setBody(fallbackText + intent.body)
                .addExtension(OriginIdElement(intent.originId))
                .addExtension(reply)
                .addExtension(fallback)
                .build()
            val attempt = SessionAttemptIdentity(
                AccountId.require(ACCOUNT),
                ConnectionGeneration.require(5),
                ConnectionAttempt.require(1),
                LifecycleEpoch.require(1),
            )

            LiveMessageAdapter(store, localIds = { "server-$suffix" }).ingest(
                requireNotNull(echo.toIncomingEnvelope(attempt, SELF, ownRoomNick = ownNick)),
            )

            assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, intent.operationId)?.status)
            assertEquals(listOf(intent.localMessageId), store.messages(ACCOUNT).filter { it.peerJid == peer }.map { it.localMessageId })
            assertTrue(store.conflicts(ACCOUNT).isEmpty())
        }

        verify(MessageKind.CHAT, PEER, PEER, null)
        verify(MessageKind.GROUPCHAT, "room@conference.example.org", "room@conference.example.org/Alice", "ChosenNick")
    }

    @Test
    fun mismatchedOwnMessageOriginEchoCannotConfirmOutboundIntent() = runBlocking {
        val store = MessageStore(database)
        val intent = OutboundIntent(
            accountId = ACCOUNT,
            operationId = "operation",
            localMessageId = "local-outbound",
            originId = "stable-origin",
            peerJid = PEER,
            senderJid = SELF,
            messageKind = MessageKind.CHAT,
            threadId = null,
            parentThreadId = null,
            body = "expected body",
        )
        store.compose(intent)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 5)))
        val message = StanzaBuilder.buildMessage("mismatch")
            .from(JidCreate.entityFullFrom("$SELF/device"))
            .to(JidCreate.entityFullFrom("$PEER/device"))
            .ofType(Message.Type.chat)
            .setBody("different body")
            .addExtension(OriginIdElement(intent.originId))
            .build()
        val attempt = SessionAttemptIdentity(
            AccountId.require(ACCOUNT),
            ConnectionGeneration.require(5),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )

        LiveMessageAdapter(store, localIds = { "mismatch-copy" })
            .ingest(requireNotNull(message.toIncomingEnvelope(attempt, SELF)))

        assertEquals(OutboxStatus.UNCERTAIN, store.outbox(ACCOUNT, intent.operationId)?.status)
    }

    companion object {
        private const val ACCOUNT = "account"
        private const val SELF = "account@example.org"
        private const val PEER = "peer@example.org"
    }
}
