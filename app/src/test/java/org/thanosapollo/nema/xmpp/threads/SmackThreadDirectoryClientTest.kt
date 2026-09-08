package org.thanosapollo.nema.xmpp.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smackx.disco.packet.DiscoverInfo
import org.jxmpp.jid.impl.JidCreate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.xmpp.smack.SmackAndroid

/** Scripted XML fixtures exercise the actual Smack IQ provider, serialization, filters and collector. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmackThreadDirectoryClientTest {
    private val direct = ThreadDirectoryScope.Direct(SELF, PEER)
    private val muc = ThreadDirectoryScope.Muc("room@conference.example.invalid")
    private lateinit var socket: DirectorySocket
    private lateinit var client: SmackThreadDirectoryClient

    @Before fun initialize() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        socket = DirectorySocket()
        client = SmackThreadDirectoryClient(socket)
    }

    @Test fun `authenticated empty is distinct from unsupported and forbidden`() = runBlocking<Unit> {
        socket.replies.add { result(it, page(direct, emptyList(), total = 0)) }
        val empty = client.list(direct)
        assertEquals(SELF, empty.account)
        assertEquals(HOST, empty.authority)
        assertEquals(direct, empty.scope)
        assertTrue(empty.items.isEmpty())
        socket.feature = false
        assertFalse(client.isSupported())
        expect<ThreadDirectoryException.Unsupported> { client.list(direct) }
        assertEquals(1, socket.requests.size)
        socket.feature = true
        socket.replies.add { error(it, "forbidden") }
        assertEquals("forbidden", expect<ThreadDirectoryException.Rejected> { client.list(muc) }.condition)
    }

    @Test fun `complete ordered snapshot retains archive and reader permission across pages`() = runBlocking<Unit> {
        socket.replies.add { result(it, page(direct, (1..3).map { n -> row(n) }, total = 4, complete = false, after = id(3))) }
        socket.replies.add { result(it, page(direct, listOf(row(4, archived = true, canModify = false)), total = 4)) }
        val directory = client.list(direct)
        assertEquals((1..4).map { UUID.fromString(id(it)) }, directory.items.map { it.id })
        assertTrue(directory.items.last().archived)
        assertFalse(directory.items.last().canModify)
        val second = parsedRequest(1)
        assertEquals(id(3), second.fields["after"])
        assertEquals(SNAPSHOT, second.fields["snapshot"])
        assertEquals(HOST, second.to.toString())
        assertEquals(4, directory.items.size)
    }

    @Test fun `MUC scope is exactly echoed and never selected by returned metadata`() = runBlocking<Unit> {
        socket.replies.add { result(it, page(muc, listOf(row(1)), total = 1)) }
        assertEquals(muc.copy(incarnation = HASH), client.list(muc).scope)
        assertEquals("room@conference.example.invalid", parsedRequest(0).fields["room"])
        socket.replies.add { result(it, page(ThreadDirectoryScope.Muc("other@conference.example.invalid"), emptyList(), 0)) }
        expect<ThreadDirectoryException.Malformed> { client.list(muc) }
    }

    @Test fun `MUC mutations require observed incarnation and never rebase queued work`() = runBlocking<Unit> {
        val thread = UUID.fromString(id(1))
        val operation = UUID.fromString(id(90))
        expect<IllegalArgumentException> { client.create(muc, thread, "Name", operation) }
        assertTrue(socket.requests.isEmpty())
        socket.replies.add { result(it, page(muc, emptyList(), 0)) }
        val authoredScope = client.list(muc).scope as ThreadDirectoryScope.Muc
        assertEquals(HASH, authoredScope.incarnation)
        socket.replies.add { error(it, "conflict") }
        expect<ThreadDirectoryException.Conflict> { client.create(authoredScope, thread, "Name", operation) }
        assertEquals(HASH, parsedRequest(1).fields["incarnation"])
        assertEquals(2, socket.requests.size)
        val recreated = muc.copy(incarnation = "b".repeat(64))
        socket.replies.add { result(it, mutation(recreated, row(1, title = "Name"), operation, false)
            .replace(SNAPSHOT, "b".repeat(64) + ":1")) }
        expect<ThreadDirectoryException.Conflict> { client.create(authoredScope, thread, "Name", operation) }
        assertEquals(HASH, parsedRequest(2).fields["incarnation"])
        socket.replies.add { result(it, page(muc, emptyList(), 0).replace(" incarnation='$HASH'", "")) }
        expect<ThreadDirectoryException.Malformed> { client.list(muc) }
        socket.replies.add { result(it, page(muc, emptyList(), 0).replace("incarnation='$HASH'", "incarnation='${"b".repeat(64)}'")) }
        expect<ThreadDirectoryException.Malformed> { client.list(muc) }
    }

    @Test fun `snapshot conflict discards staged rows and rescans from first page`() = runBlocking<Unit> {
        socket.replies.add { result(it, page(direct, (1..3).map { n -> row(n) }, 4, false, id(3))) }
        socket.replies.add { error(it, "conflict") }
        socket.replies.add { result(it, page(direct, listOf(row(8)), 1, snapshot = HASH + ":9")) }
        assertEquals(listOf(UUID.fromString(id(8))), client.list(direct).items.map { it.id })
        assertNull(parsedRequest(2).fields["snapshot"])
        assertNull(parsedRequest(2).fields["after"])
    }

    @Test fun `rescan conflicts have finite attempts and do not convert to empty`() = runBlocking<Unit> {
        repeat(3) { socket.replies.add { error(it, "conflict") } }
        expect<ThreadDirectoryException.Conflict> { client.list(direct) }
        assertEquals(3, socket.requests.size)
    }

    @Test fun `partial scan transport failure does not return staged results`() = runBlocking<Unit> {
        socket.replies.add { result(it, page(direct, (1..3).map { n -> row(n) }, 4, false, id(3))) }
        socket.replies.add { null }
        socket.replyTimeout = 30
        expect<ThreadDirectoryException.Transport> { client.list(direct) }
        assertEquals(2, socket.requests.size)
    }

    @Test fun `count completion cursor and capacity evidence are strict`() = runBlocking<Unit> {
        val bad = listOf(
            page(direct, emptyList(), 1),
            page(direct, listOf(row(1)), 0),
            page(direct, listOf(row(1)), 2, false, id(1)),
            page(direct, (1..3).map { row(it) }, 4, false, id(2)),
            page(direct, listOf(row(1)), 1, after = id(1)),
            page(direct, emptyList(), 129),
            page(direct, emptyList(), 0).replace("total='0'", "total='00'"),
            page(direct, emptyList(), 0).replace("complete='true'", "complete='1'"),
            page(direct, (1..4).map { row(it) }, 4),
        )
        for (payload in bad) {
            socket.replies.add { result(it, payload) }
            expect<ThreadDirectoryException.Malformed> { client.list(direct) }
        }
    }

    @Test fun `duplicate unordered changed snapshot and changing total pages fail closed`() = runBlocking<Unit> {
        val secondPages = listOf(
            page(direct, listOf(row(3)), 4),
            page(direct, listOf(row(4)), 4, snapshot = HASH + ":6"),
            page(direct, listOf(row(4)), 5),
        )
        for (second in secondPages) {
            socket.replies.add { result(it, page(direct, (1..3).map { n -> row(n) }, 4, false, id(3))) }
            socket.replies.add { result(it, second) }
            expect<ThreadDirectoryException.Malformed> { client.list(direct) }
        }
        for (rows in listOf(listOf(row(1), row(1)), listOf(row(2), row(1)))) {
            socket.replies.add { result(it, page(direct, rows, 2)) }
            expect<ThreadDirectoryException.Malformed> { client.list(direct) }
        }
    }

    @Test fun `typed entries reject invalid IDs titles revisions permission and foreign attributes`() = runBlocking<Unit> {
        val good = row(1)
        val bad = listOf(
            good.replace(id(1), "legacy-message-thread"),
            good.replace(id(1), "AAAAAAAA-0000-0000-0000-000000000001"),
            good.replace("revision='1'", "revision='0'"),
            good.replace("revision='1'", "revision='9007199254740992'"),
            good.replace("can_modify='true'", "can_modify='1'"),
            good.replace("can_modify='true'", "creator='reader@example.invalid'"),
            good.replace("title='Name 1'", "title='&#10;bad'"),
            good.replace("title='Name 1'", "title='${"a".repeat(129)}'"),
            good.replace("title='Name 1'", "title='&#160;'"),
            good.replace("/>", "><foreign/></thread>"),
            good.replace("<thread", "<thread xmlns='urn:wrong'"),
        )
        for (row in bad) {
            socket.replies.add { result(it, page(direct, listOf(row), 1)) }
            expect<ThreadDirectoryException.Malformed> { client.list(direct) }
        }
    }

    @Test fun `malformed scope snapshots payload and IQ envelope are rejected`() = runBlocking<Unit> {
        val payload = page(direct, emptyList(), 0)
        val bad = listOf(
            payload.replace("a='$SELF'", "a='other@example.invalid'"),
            payload.replace("kind='direct'", "kind='direct' room='room@conference.example.invalid'"),
            payload.replace(SNAPSHOT, "invalid"),
            payload.replace(SNAPSHOT, HASH + ":9007199254740992"),
            payload.replace("urn:nema:threads:0", "urn:wrong"),
            payload.replace("</directory>", "<unknown/></directory>"),
            payload + payload,
            payload + "<foreign xmlns='urn:foreign'/>",
        )
        for (body in bad) {
            socket.replies.add { result(it, body) }
            expect<ThreadDirectoryException.Malformed> { client.list(direct) }
        }
        socket.replies.add { result(it, payload).replace("to='$SELF/test'", "to='$PEER/other'") }
        expect<ThreadDirectoryException.Malformed> { client.list(direct) }
        socket.replies.add { result(it, payload).replace("from='$HOST'", "from='$SELF'") }
        socket.replyTimeout = 30
        // Smack's IQReplyFilter drops this forged sender before the client can inspect it.
        expect<ThreadDirectoryException.Transport> { client.list(direct) }
        socket.replies.add { result(it, "") }
        expect<ThreadDirectoryException.Malformed> { client.list(direct) }
    }

    @Test fun `create rename archive serialize CAS and exact operation and decode replay`() = runBlocking<Unit> {
        val operation = UUID.fromString(id(90))
        val title = "Design < & ' \" 🚀"
        socket.replies.add { result(it, mutation(direct, row(1, title = title), operation, replayed = false)) }
        val created = client.create(direct, UUID.fromString(id(1)), title, operation)
        assertEquals(title, created.item.title)
        val create = parsedRequest(0)
        assertEquals(IQ.Type.set, create.type)
        assertEquals(title, create.fields["title"])
        assertEquals(operation.toString(), create.fields["operation"])
        assertNull(create.fields["expected"])
        assertTrue(socket.requests[0].contains("&amp;"))
        socket.replies.add { result(it, mutation(direct, row(1, title = "Renamed", revision = 2), operation, replayed = true)) }
        val renamed = client.rename(direct, UUID.fromString(id(1)), 1, "Renamed", operation)
        assertTrue(renamed.replayed)
        assertEquals("1", parsedRequest(1).fields["expected"])
        socket.replies.add { result(it, mutation(muc, row(1, revision = 3, archived = true), operation, replayed = false)) }
        val archived = client.archive(muc.copy(incarnation = HASH), UUID.fromString(id(1)), 2, true, operation)
        assertTrue(archived.item.archived)
        assertEquals("true", parsedRequest(2).fields["archived"])
        assertEquals("2", parsedRequest(2).fields["expected"])
        assertEquals(HASH, parsedRequest(2).fields["incarnation"])
    }

    @Test fun `mutation conflicts are not retried and uncertain retries preserve exact UUID`() = runBlocking<Unit> {
        val operation = UUID.fromString(id(90))
        socket.replies.add { error(it, "conflict") }
        expect<ThreadDirectoryException.Conflict> { client.rename(direct, UUID.fromString(id(1)), 1, "Name", operation) }
        assertEquals(1, socket.requests.size)
        socket.replyTimeout = 30
        socket.replies.add { null }
        expect<ThreadDirectoryException.Transport> { client.create(direct, UUID.fromString(id(1)), "Name", operation) }
        socket.replies.add { result(it, mutation(direct, row(1, title = "Name"), operation, true)) }
        assertTrue(client.create(direct, UUID.fromString(id(1)), "Name", operation).replayed)
        assertEquals(parsedRequest(1).fields, parsedRequest(2).fields)
    }

    @Test fun `mutation results must match caller ID operation revision and effect`() = runBlocking<Unit> {
        val operation = UUID.fromString(id(90))
        val good = mutation(direct, row(1, title = "Name"), operation, false)
        for (bad in listOf(
            good.replace("operation='$operation'", "operation='${id(91)}'"),
            good.replace("id='${id(1)}'", "id='${id(2)}'"),
            good.replace("revision='1'", "revision='2'"),
            good.replace("can_modify='true'", "can_modify='false'"),
            good.replace("title='Name'", "title='Other'"),
            good.replace("archived='false'", "archived='true'"),
            good.replace("replayed='false'", "replayed='yes'"),
            good.replace("total='1'", "total='0'"),
        )) {
            socket.replies.add { result(it, bad) }
            expect<ThreadDirectoryException.Malformed> { client.create(direct, UUID.fromString(id(1)), "Name", operation) }
        }
    }

    @Test fun `untrusted scope and invalid local input are rejected before wire access`() = runBlocking<Unit> {
        expect<IllegalArgumentException> { client.list(ThreadDirectoryScope.Direct("other@example.invalid", PEER)) }
        expect<IllegalArgumentException> { client.create(direct, UUID.randomUUID(), " ", UUID.randomUUID()) }
        expect<IllegalArgumentException> { client.rename(direct, UUID.randomUUID(), 0, "Name", UUID.randomUUID()) }
        expect<IllegalArgumentException> { ThreadDirectoryScope.Direct(SELF, "peer@remote.invalid") }
        expect<IllegalArgumentException> { ThreadDirectoryScope.Muc("room@conference.example.invalid/resource") }
        assertTrue(socket.requests.isEmpty())
    }

    @Test fun `session replacement cannot return data owned by another account`() = runBlocking<Unit> {
        socket.replies.add {
            socket.replaceUser("other@example.invalid/test")
            result(it, page(direct, emptyList(), 0))
        }
        expect<ThreadDirectoryException.SessionChanged> { client.list(direct) }
    }

    @Test fun `cancellation interrupts collector wait and prevents scan retries`() = runBlocking<Unit> {
        socket.replyTimeout = 60_000
        socket.replies.add { null }
        val work = launch(Dispatchers.Default) { client.list(direct) }
        assertTrue(socket.directorySent.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { work.cancelAndJoin() }
        assertTrue(work.isCancelled)
        assertEquals(1, socket.requests.size)
    }

    @Test fun `title validation matches Lua codepoint and whitespace rules`() {
        assertTrue(validDirectoryTitle("🚀".repeat(128)))
        assertFalse(validDirectoryTitle("🚀".repeat(129)))
        assertFalse(validDirectoryTitle("\ud800"))
        assertFalse(validDirectoryTitle("A\u0085B"))
        assertFalse(validDirectoryTitle("A\u2028B"))
        assertFalse(validDirectoryTitle("\u2003"))
        assertFalse(validDirectoryTitle(" space"))
        assertTrue(validDirectoryTitle("\u2003word\u2003"))
    }

    @Test fun `Smack stock parser limitation discards foreign preceding IQ payload`() {
        val request = ThreadDirectoryIq(direct.attributes()).also { it.stanzaId = "fixture" }
        val parsed = PacketParserUtils.parseStanza<IQ>(result(request,
            "<foreign xmlns='urn:foreign'/>" + page(direct, emptyList(), 0)))
        // Native-parser limitation evidence, NOT a claim of strict raw IQ cardinality.
        assertTrue(parsed is ThreadDirectoryIq)
        assertTrue((parsed as ThreadDirectoryIq).structurallyValid)
        assertTrue(parsed.extensions.isEmpty())
    }

    @Test fun `foreign prefix cannot contribute rows or select the admitted directory scope`() = runBlocking<Unit> {
        val foreignScope = ThreadDirectoryScope.Direct(SELF, "other@example.invalid")
        val prefix = "<foreign xmlns='urn:foreign'>" + page(foreignScope, listOf(row(9)), 1) + "</foreign>"
        socket.replies.add { result(it, prefix + page(direct, listOf(row(1)), 1)) }
        val selected = client.list(direct)
        assertEquals(direct, selected.scope)
        assertEquals(listOf(UUID.fromString(id(1))), selected.items.map { it.id })
        assertEquals("Name 1", selected.items.single().title)
        socket.replies.add { result(it, prefix + page(direct, emptyList(), 0)) }
        assertTrue(client.list(direct).items.isEmpty())
    }

    @Test fun `foreign prefix cannot rescue a selected directory with wrong scope`() = runBlocking<Unit> {
        val prefix = "<foreign xmlns='urn:foreign'>" + page(direct, listOf(row(1)), 1) + "</foreign>"
        val wrongScope = ThreadDirectoryScope.Direct(SELF, "other@example.invalid")
        socket.replies.add { result(it, prefix + page(wrongScope, emptyList(), 0)) }
        expect<ThreadDirectoryException.Malformed> { client.list(direct) }
    }

    @Test fun `top level stanza error before or after directory never becomes success`() = runBlocking<Unit> {
        val payload = page(direct, emptyList(), 0)
        val stanzaError = "<error type='cancel'><forbidden xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error>"
        for (body in listOf(stanzaError + payload, payload + stanzaError)) {
            socket.replies.add { result(it, body) }
            expect<ThreadDirectoryException.Malformed> { client.list(direct) }
            socket.replies.add { result(it, body).replace("type='result'", "type='error'") }
            assertEquals("forbidden", expect<ThreadDirectoryException.Rejected> { client.list(direct) }.condition)
        }
    }

    private fun parsedRequest(index: Int): ThreadDirectoryIq = PacketParserUtils.parseStanza(socket.requests[index])

    private suspend inline fun <reified T : Throwable> expect(noinline action: suspend () -> Unit): T {
        try { action() } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got $failure", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private class DirectorySocket : XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
        .setXmppDomain(JidCreate.domainBareFrom(HOST)).setUsernameAndPassword("self", null).build()) {
        val replies = ArrayDeque<(IQ) -> String?>()
        val requests = mutableListOf<String>()
        val directorySent = CountDownLatch(1)
        var feature = true
        init {
            connected = true
            authenticated = true
            user = JidCreate.entityFullFrom("$SELF/test")
            replyTimeout = 3_000
        }
        fun replaceUser(jid: String) { user = JidCreate.entityFullFrom(jid) }
        override fun throwNotConnectedExceptionIfAppropriate() = Unit
        override fun sendStanzaInternal(packet: Stanza) {
            val xml = when (packet) {
                is DiscoverInfo -> result(packet, "<query xmlns='http://jabber.org/protocol/disco#info'>" +
                    (if (feature) "<feature var='$THREAD_DIRECTORY_NAMESPACE'/>" else "") + "</query>")
                is ThreadDirectoryIq -> {
                    requests += packet.toXML().toString()
                    directorySent.countDown()
                    replies.removeFirst().invoke(packet)
                }
                else -> null
            }
            xml?.let { processStanza(PacketParserUtils.parseStanza(it)) }
        }
    }

    companion object {
        private const val HOST = "example.invalid"
        private const val SELF = "self@example.invalid"
        private const val PEER = "peer@example.invalid"
        private val HASH = "a".repeat(64)
        private val SNAPSHOT = HASH + ":5"
        private fun id(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
        private fun attr(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace("'", "&apos;").replace("\"", "&quot;")
        private fun row(n: Int, title: String = "Name $n", revision: Long = 1, archived: Boolean = false, canModify: Boolean = true) =
            "<thread id='${id(n)}' title='${attr(title)}' revision='$revision' archived='$archived' can_modify='$canModify'/>"
        private fun scope(scope: ThreadDirectoryScope) = scope.attributes().entries.joinToString(" ") { "${it.key}='${attr(it.value)}'" } +
            (if (scope is ThreadDirectoryScope.Muc) " incarnation='${scope.incarnation ?: HASH}'" else "")
        private fun page(scope: ThreadDirectoryScope, rows: List<String>, total: Int, complete: Boolean = true,
                         after: String? = null, snapshot: String = SNAPSHOT) =
            "<directory xmlns='$THREAD_DIRECTORY_NAMESPACE' ${scope(scope)} snapshot='$snapshot' total='$total' complete='$complete'" +
                (after?.let { " after='$it'" } ?: "") + ">${rows.joinToString("")}</directory>"
        private fun mutation(scope: ThreadDirectoryScope, row: String, operation: UUID, replayed: Boolean) =
            "<directory xmlns='$THREAD_DIRECTORY_NAMESPACE' ${scope(scope)} snapshot='$SNAPSHOT' total='1' operation='$operation' replayed='$replayed'>$row</directory>"
        private fun result(request: IQ, payload: String) =
            "<iq xmlns='jabber:client' type='result' from='$HOST' to='$SELF/test' id='${request.stanzaId}'>$payload</iq>"
        private fun error(request: IQ, condition: String) =
            "<iq xmlns='jabber:client' type='error' from='$HOST' to='$SELF/test' id='${request.stanzaId}'>" +
                "<error type='cancel'><$condition xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></iq>"
    }
}
