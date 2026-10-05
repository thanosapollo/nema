package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.mam.element.MamElements
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.toIncomingMessage
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.omemo.ProtectedCarrierKind
import org.thanosapollo.nema.xmpp.omemo.ProtectedRejection
import org.thanosapollo.nema.xmpp.smack.SmackAndroid
import org.thanosapollo.nema.xmpp.smack.installNemaCarbonProvider
import org.thanosapollo.nema.xmpp.smack.installNemaMamResultProvider
import org.thanosapollo.nema.xmpp.smack.installNemaOmemoProviders
import org.thanosapollo.nema.xmpp.smack.normalizeMamResults
import org.thanosapollo.nema.xmpp.smack.toIncomingEnvelope
import org.thanosapollo.nema.xmpp.transport.ACCOUNT_ARCHIVE_SCOPE
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

/** #57 device regression: a live protected fallback card and its account-archive row are one message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectedArchiveReconciliationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var store: MessageStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        SmackAndroid.initialize(context)
        installNemaOmemoProviders()
        installNemaCarbonProvider()
        installNemaMamResultProvider()
        databaseName = "protected-archive-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "chat.example.org", null, null))
        store = MessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun liveProtectedFallbackAndItsArchiveRowStayOneMessageAfterReconnect() = runBlocking {
        archive(ArchiveDirection.BOOTSTRAP, null, "496900" to stanza("plain-before", PLAIN_BODY))
        // The device's injected stanza names recipient device 2468013579, beyond the 31-bit device
        // range, so every carrier rejects it as MALFORMED and retains no ciphertext.
        data class Case(val tag: String, val recipientDevice: Long, val state: String, val liveStanzaId: Boolean)
        val cases = listOf(
            Case("r4eefe16e", REJECTED_DEVICE, "REJECTED", liveStanzaId = true),
            Case("r3ee03d68", REJECTED_DEVICE, "REJECTED", liveStanzaId = false),
            Case("rwellformed", ACCEPTED_DEVICE, "UNSUPPORTED_PAYLOAD", liveStanzaId = true),
        )
        cases.forEachIndexed { index, case ->
            val protectedResult = "49697${index * 2}"
            val plainResult = "49697${index * 2 + 1}"
            val protectedId = "nema-r4-57-${case.tag}"
            val plainId = "plain-${case.tag}"
            val content = protectedContent(case.tag, case.recipientDevice)
            live(stanza(protectedId, content, protectedResult.takeIf { case.liveStanzaId }))
            live(stanza(plainId, "plain ${case.tag}", plainResult.takeIf { case.liveStanzaId }))
            archive(
                ArchiveDirection.AFTER,
                lastArchived,
                protectedResult to stanza(protectedId, content),
                plainResult to stanza(plainId, "plain ${case.tag}"),
            )
            val rows = store.messages(ACCOUNT)
            assertEquals("plain row $case", 1, rows.count { it.body == "plain ${case.tag}" })
            val cards = rows.filter { it.body.startsWith("[${case.tag}]") }
            assertEquals("protected card $case", 1, cards.size)
            assertEquals("$case", case.state, cards.single().protectedState)
            // The one card keeps both observations and its sender message ID stays trusted.
            val evidence = requireNotNull(cards.single().protection()) { "$case" }
            assertEquals("$case", listOf(ProtectedCarrierKind.LIVE, ProtectedCarrierKind.MAM), evidence.carriers.map { it.kind })
            if (case.state == "REJECTED") {
                assertNull("$case", evidence.content)
                assertEquals("$case", ProtectedRejection.MALFORMED, evidence.rejection)
            }
            assertEquals(
                "$case",
                cards.single().localMessageId,
                database.messageDao().trustedAlias(ACCOUNT, IdentityAliasKind.MESSAGE_ID, PEER, protectedId)?.messageId,
            )
        }
    }

    @Test
    fun rejectedEvidenceDoesNotMergeAcrossIdentitiesOrWithAcceptedContent() = runBlocking {
        archive(ArchiveDirection.BOOTSTRAP, null, "496900" to stanza("plain-before", PLAIN_BODY))
        // Identical rejected evidence under different sender IDs remains two messages.
        live(stanza("rejected-a", protectedContent("same", REJECTED_DEVICE), "496901"))
        live(stanza("rejected-b", protectedContent("same", REJECTED_DEVICE), "496902"))
        // Accepted live content and a rejected archive copy under one sender ID stay separate.
        live(stanza("mixed", protectedContent("mixed", ACCEPTED_DEVICE), "496903"))
        // Without any identity, protected rows are excluded from identityless reconciliation.
        live(stanza(null, protectedContent("anonymous", REJECTED_DEVICE)))
        archive(
            ArchiveDirection.AFTER,
            lastArchived,
            "496901" to stanza("rejected-a", protectedContent("same", REJECTED_DEVICE)),
            "496902" to stanza("rejected-b", protectedContent("same", REJECTED_DEVICE)),
            "496903" to stanza("mixed", protectedContent("mixed", REJECTED_DEVICE)),
            "496904" to stanza(null, protectedContent("anonymous", REJECTED_DEVICE)),
        )
        val rows = store.messages(ACCOUNT)
        assertEquals(2, rows.count { it.body.startsWith("[same]") })
        assertEquals(
            listOf("REJECTED", "UNSUPPORTED_PAYLOAD"),
            rows.filter { it.body.startsWith("[mixed]") }.map { it.protectedState }.sorted(),
        )
        assertEquals(2, rows.count { it.body.startsWith("[anonymous]") })
    }

    private var lastArchived: String? = null

    private suspend fun live(message: Message) {
        // Live ingress trusts the account's own stanza-id authority, as the session does when advertised.
        val envelope = requireNotNull(message.toIncomingEnvelope(ATTEMPT, SELF, trustedStableIdAuthority = SELF))
        store.ingest(envelope.toIncomingMessage("live-${UUID.randomUUID()}"))
    }

    private suspend fun archive(direction: ArchiveDirection, boundary: String?, vararg rows: Pair<String, String>) {
        val wrappers = rows.map { (resultId, inner) ->
            PacketParserUtils.parseStanza<Message>(
                "<message xmlns='jabber:client' from='$SELF' to='$SELF/device'>" +
                    "<result xmlns='urn:xmpp:mam:2' queryid='query' id='$resultId'>" +
                    "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-10-05T17:00:00Z'/>" +
                    "$inner</forwarded></result></message>",
            )
        }
        val normalized = normalizeMamResults(wrappers, wrappers.map(MamElements.MamResultExtension::from), ATTEMPT, SELF, false)
        val result = store.applyArchivePage(
            ArchivePage(
                key = ArchiveCursorKey(ACCOUNT, SELF, ACCOUNT_ARCHIVE_SCOPE),
                direction = direction,
                boundaryId = boundary,
                complete = true,
                hasEarlier = false,
                stable = true,
                firstId = rows.first().first,
                lastId = rows.last().first,
                messages = normalized.map {
                    ArchivedIncomingMessage(it.resultId, it.message?.toIncomingMessage("archive-${UUID.randomUUID()}"))
                },
            ),
        )
        assertEquals(result.toString(), ArchivePageStatus.APPLIED, result.status)
        lastArchived = rows.last().first
    }

    private fun protectedContent(tag: String, recipientDevice: Long) =
        "<encrypted xmlns='eu.siacs.conversations.axolotl'><header sid='1357924680'>" +
            "<key rid='$recipientDevice'>MwohBQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</key>" +
            "<iv>AAAAAAAAAAAAAAAA</iv></header><payload>AAAAAAAAAAAAAAAA</payload></encrypted>" +
            "<store xmlns='urn:xmpp:hints'/>" +
            "<encryption xmlns='urn:xmpp:eme:0' name='OMEMO' namespace='eu.siacs.conversations.axolotl'/>" +
            "<body>[$tag] I sent you an OMEMO encrypted message but your client doesn't seem to support that. " +
            "Find more information on https://conversations.im/omemo</body>" +
            "<thread>ef7792ce-450d-4f6e-859a-56469f0b80dd</thread>"

    private fun stanza(id: String?, content: String, stanzaId: String? = null): String {
        val body = if (content.startsWith("<")) content else "<body>$content</body>"
        val sid = stanzaId?.let { "<stanza-id xmlns='urn:xmpp:sid:0' by='$SELF' id='$it'/>" }.orEmpty()
        val idAttribute = id?.let { " id='$it'" }.orEmpty()
        return "<message xmlns='jabber:client' type='chat'$idAttribute from='$PEER/nema-r4-test' to='$SELF/device'>$body$sid</message>"
    }

    private suspend fun live(raw: String) = live(PacketParserUtils.parseStanza<Message>(raw))

    private companion object {
        const val ACCOUNT = "account"
        const val SELF = "thanos@chat.example.org"
        const val PEER = "hermes@chat.example.org"
        const val PLAIN_BODY = "plain before"
        const val REJECTED_DEVICE = 2_468_013_579L
        const val ACCEPTED_DEVICE = 246_801_357L
        val ATTEMPT = SessionAttemptIdentity(
            AccountId.require(ACCOUNT),
            ConnectionGeneration.require(1),
            ConnectionAttempt.require(1),
            LifecycleEpoch.require(1),
        )
    }

}
