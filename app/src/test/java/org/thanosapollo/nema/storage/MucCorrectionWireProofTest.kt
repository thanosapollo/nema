package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.flow.first
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
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.transport.*

/** Retained room claims must not suppress their visible fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class MucCorrectionWireProofTest : ReactionStoreTestFixture() {
    private val attempt = SessionAttemptIdentity(AccountId.require(ACCOUNT), ConnectionGeneration.require(1),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1))
    private val replacement = "<replace xmlns='urn:xmpp:message-correct:0' id='original1'/>"

    @Before fun providers() {
        SmackAndroid.initialize(context)
        installNemaSidProviders()
        installNemaMamResultProvider()
        installNemaOccupantIdProvider()
        installNemaCorrectionProvider()
    }

    private fun parse(xml: String): Message = PacketParserUtils.parseStanza(xml) as Message
    private fun xml(id: String, body: String, extension: String = "") =
        "<message xmlns='jabber:client' from='$ROOM/alice' type='groupchat' id='$id'>" +
            "<body>$body</body>$extension<occupant-id xmlns='urn:xmpp:occupant-id:0' id='actor-a'/>" +
            "<stanza-id xmlns='urn:xmpp:sid:0' by='$ROOM' id='archive-$id'/></message>"

    @Test fun authenticatedRoomPageRetainsClaimsWithoutSuppressingFallback() = runBlocking {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(attempt) }
        val lease = requireNotNull(registry.beginJoin(attempt, ROOM))
        check(registry.publish(lease, true, true, "self", true))
        val facts = requireNotNull(copyRoomConsumerFacts(Any(), registry, attempt, ROOM))
        val carriers = listOf("original1" to xml("original1", "gmy"), "edit1" to xml("edit1", "gym", replacement)).map { (id, inner) ->
            parse("<message xmlns='jabber:client' from='$ROOM'><result xmlns='urn:xmpp:mam:2' queryid='query' id='archive-$id'>" +
                "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>" +
                inner + "</forwarded></result></message>")
        }
        val entries = normalizeMamResults(carriers, carriers.map(MamResultExtension::from), attempt, ROOM, true,
            mappingBareJid = SELF, ownRoomNick = "self", archiveRoom = facts)
        val key = ArchiveCursorKey(ACCOUNT, ROOM, ROOM)
        val page = ArchivePage(key, ArchiveDirection.BOOTSTRAP, null, true, false, true,
            entries.first().resultId, entries.last().resultId,
            entries.map { ArchivedIncomingMessage(it.resultId, it.message!!.toIncomingMessage(it.resultId)) })
        assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page).status)
        val saved = requireNotNull(database.messageDao().message(ACCOUNT, "archive-edit1"))
        assertEquals("original1", saved.mucReplaceId)
        assertEquals("edit1", saved.mucMessageId)
        assertEquals(MucClaimState.VALID, saved.mucClaimState)
        assertEquals(MucOccupantEvidence.ROOM_MAM, saved.mucOccupantEvidence)
        assertNull(saved.replaceId)
        assertNull(saved.correctionTargetMessageId)
        assertNull(saved.mucLiveOrderEpoch)
        val timeline = database.messageDao().observeDirectTimeline(ACCOUNT, ROOM).first()
        assertEquals(2, timeline.size)
        assertTrue(timeline.all { it.correctedBody == null })
        assertEquals("archive-edit1", store.archiveCursor(key)?.newestId)
    }
}
