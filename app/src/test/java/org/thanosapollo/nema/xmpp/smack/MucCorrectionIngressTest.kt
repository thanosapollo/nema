package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.toIncomingMessage
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.xmpp.transport.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MucCorrectionIngressTest {
    private val room = "room@conference.example.org"
    private val self = "self@example.org"
    private val attempt = SessionAttemptIdentity(AccountId.require("a"), ConnectionGeneration.require(1),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1))
    private val occupant = "<occupant-id xmlns='urn:xmpp:occupant-id:0' id='opaque'/>"
    private val replace = "<replace xmlns='urn:xmpp:message-correct:0' id='root'/>"
    private val facts = RoomConsumerFacts(RoomStableIdLease(attempt, room, 1), room, true, "self", true)

    @Before fun providers() {
        installNemaSidProviders()
        installNemaMamResultProvider()
        installNemaOccupantIdProvider()
        installNemaCorrectionProvider()
    }
    private fun parse(xml: String) = PacketParserUtils.parseStanza(xml) as Message
    private fun xml(extra: String = replace, actor: String = occupant, sender: String = "$room/alice") =
        "<message xmlns='jabber:client' from='$sender' type='groupchat' id='edit'><body>fallback</body>" +
            "$extra$actor<stanza-id xmlns='urn:xmpp:sid:0' by='$room' id='uid'/></message>"
    private fun live(message: Message, support: RoomConsumerFacts? = facts, current: SessionAttemptIdentity? = attempt,
        carbon: CarbonCarrier.Direction? = null, epoch: String = "process:attempt") =
        StableIdMessageDecision(attempt, message, true, carbonDirection = carbon).retainLiveMucFacts(
            message.toIncomingEnvelope(attempt, self, room, "self")!!, support, current, epoch)
    private fun archive(inner: String, support: RoomConsumerFacts? = facts, authority: String = room): IncomingMessageEnvelope {
        val carrier = parse("<message xmlns='jabber:client' from='$authority'><result xmlns='urn:xmpp:mam:2' queryid='open' id='uid'>" +
            "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>" +
            inner + "</forwarded></result></message>")
        val filter = org.jivesoftware.smackx.mam.filter.MamResultFilter(org.jivesoftware.smackx.mam.element.MamQueryIQ("open"))
        assertTrue(filter.accept(carrier))
        return normalizeMamResults(listOf(carrier), listOf(MamResultExtension.from(carrier)), attempt, authority, true,
            mappingBareJid = self, archiveRoom = support).single().message!!
    }

    @Test fun rawClaimsAndPayloadClassificationAreConservativeOnBothCarriers() {
        val cases = listOf("" to MucClaimState.NONE, replace to MucClaimState.VALID,
            replace + replace to MucClaimState.INVALID, replace.replace("id='root'", "id=''") to MucClaimState.INVALID,
            replace.replace("id='root'", "") to MucClaimState.INVALID,
            replace.replace("/>", "><child/></replace>") to MucClaimState.INVALID,
            replace.replace("/>", ">text</replace>") to MucClaimState.INVALID,
            replace.replace("/>", " other='x'/>") to MucClaimState.INVALID)
        for ((claim, expected) in cases) for (mapped in listOf(live(parse(xml(claim)))!!, archive(xml(claim)))) {
            assertEquals(expected, mapped.mucFacts!!.claim)
            assertEquals(if (expected == MucClaimState.VALID) "root" else null, mapped.mucFacts.replaceId)
            assertEquals("edit", mapped.messageId)
            assertEquals("opaque", mapped.mucFacts.occupantId)
            assertNull(mapped.replaceId)
        }
        for (unsupported in listOf("<replace xmlns='wrong' id='root'/>", "<reply xmlns='urn:xmpp:reply:0' id='x'/>",
            "<x xmlns='jabber:x:oob'><url>https://example.org/file</url></x>",
            "<encrypted xmlns='urn:xmpp:omemo:2'><payload>abc</payload></encrypted>", "<unknown xmlns='payload'/>",
            "<fallback xmlns='urn:xmpp:fallback:0'/>") ) {
            assertEquals(MucPayloadState.UNSUPPORTED, live(parse(xml(unsupported)))!!.mucFacts!!.payload)
            assertEquals(MucPayloadState.UNSUPPORTED, archive(xml(unsupported)).mucFacts!!.payload)
        }
        assertEquals(MucPayloadState.PLAIN, live(parse(xml()))!!.mucFacts!!.payload)
    }

    @Test fun missingCorrectionIdsNeverEscapeAsSyntheticDirectTargets() {
        for (id in listOf("", " id=''", " id='nema-invalid-correction'")) {
            val message = parse("<message xmlns='jabber:client' from='peer@example.org/device' type='chat'>" +
                "<body>fallback</body><replace xmlns='urn:xmpp:message-correct:0'$id/></message>")
            val expected = if (id.contains("nema-invalid-correction")) "nema-invalid-correction" else null
            assertEquals(expected, message.toIncomingEnvelope(attempt, self)!!.replaceId)
            assertEquals(expected != null, message.toXML().toString().contains("nema-invalid-correction"))
        }
    }

    @Test fun staleUnsupportedMalformedAndForwardedCarriersCannotMintActorAuthority() {
        assertNull(live(parse(xml()), current = attempt.copy(attempt = ConnectionAttempt.require(2))))
        for (support in listOf(null, facts.copy(lease = facts.lease.copy(attempt = attempt.copy(epoch = LifecycleEpoch.require(2))))))
            assertNull(live(parse(xml()), support)!!.mucFacts)
        for (actor in listOf("", occupant + occupant, occupant.replace("/>", "><bad/></occupant-id>"))) {
            for (mapped in listOf(live(parse(xml(actor = actor)))!!, archive(xml(actor = actor)))) {
                assertNull(mapped.mucFacts!!.occupantId)
                assertEquals(MucOccupantEvidence.UNKNOWN, mapped.mucFacts.evidence)
                assertNull(mapped.mucLiveOrderEpoch)
            }
        }
        assertEquals(MucOccupantEvidence.UNKNOWN, live(parse(xml()), facts.copy(occupantIds = false))!!.mucFacts!!.evidence)
        assertEquals(MucOccupantEvidence.UNKNOWN, archive(xml(), facts.copy(occupantIds = false)).mucFacts!!.evidence)
        assertNull(live(parse(xml(sender = room)))!!.mucFacts)
        assertNull(live(parse(xml()), carbon = CarbonCarrier.Direction.SENT)!!.mucFacts)
        assertNull(live(parse(xml("<delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>")))!!.mucFacts)
        assertNull(archive(xml(), null, self).mucFacts)
        assertNull(archive(xml(), null).mucFacts)
        assertThrows(IllegalArgumentException::class.java) {
            requireCurrentArchiveRoom(facts, facts.copy(lease = facts.lease.copy(incarnation = 2)), attempt, room)
        }
        assertEquals("process:attempt", live(parse(xml()))!!.mucLiveOrderEpoch)
    }

    @Test fun liveInsertionEpochNeverLabelsArchiveFirstOrDuplicatesAndSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (archiveFirst in listOf(false, true)) {
            val name = "muc-ingress-${UUID.randomUUID()}.db"
            var db = NemaDatabase.create(context, name)
            try {
                db.accountDao().upsert(AccountEntity("a", self, "self", null, "example.org", null, null))
                var store = MessageStore(db)
                val key = ArchiveCursorKey("a", room, room)
                val archived = archive(xml()).toIncomingMessage("archive")
                suspend fun page() { assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(ArchivePage(key,
                    ArchiveDirection.BOOTSTRAP, null, true, false, true, "uid", "uid",
                    listOf(ArchivedIncomingMessage("uid", archived)))).status) }
                if (archiveFirst) page()
                store.ingest(live(parse(xml()))!!.toIncomingMessage("live"))
                assertEquals("after live", if (archiveFirst) null else "process:attempt", store.messages("a").single().mucLiveOrderEpoch)
                if (!archiveFirst) page()
                assertEquals("after page", if (archiveFirst) null else "process:attempt", store.messages("a").single().mucLiveOrderEpoch)
                store.ingest(live(parse(xml()), epoch = "successor")!!.toIncomingMessage("duplicate"))
                repeat(2) {
                    val row = store.messages("a").single()
                    assertEquals(if (archiveFirst) null else "process:attempt", row.mucLiveOrderEpoch)
                    assertEquals(MucOccupantEvidence.BOTH, row.mucOccupantEvidence)
                    assertEquals("root", row.mucReplaceId)
                    assertNull(row.replaceId)
                    assertNull(row.correctionTargetMessageId)
                    db.close()
                    db = NemaDatabase.create(context, name)
                    store = MessageStore(db)
                }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
