package org.thanosapollo.nema.xmpp.smack

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.packet.StanzaError
import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.xmpp.transport.TransientArchiveException

class ArchiveNetworkCallTest {
    @Test fun `only known transport interruptions become transient archive failures`() {
        val failures = listOf(
            SmackException.NoResponseException.newWith(5_000L, StanzaFilter { true }, false),
            SmackException.NotConnectedException(), SocketTimeoutException(), SocketException(), EOFException(),
        )
        for (failure in failures) {
            val caught = runCatching { archiveNetworkCall<Nothing> { throw failure } }.exceptionOrNull()
            assertTrue(caught is TransientArchiveException)
            assertSame(failure, caught?.cause)
        }
        assertEquals("page", archiveNetworkCall { "page" })
    }

    @Test fun `IQ errors including missing cursor and timeout are manual not generic network retry`() {
        for (condition in listOf(StanzaError.Condition.item_not_found, StanzaError.Condition.remote_server_timeout,
            StanzaError.Condition.service_unavailable)) {
            val failure = XMPPException.XMPPErrorException(null, StanzaError.getBuilder(condition).setType(StanzaError.Type.CANCEL).build())
            assertSame(failure, runCatching { archiveNetworkCall<Nothing> { throw failure } }.exceptionOrNull())
        }
    }

    @Test fun `cancellation TLS storage-like IO and malformed input propagate unchanged`() {
        for (failure in listOf(CancellationException(), SSLHandshakeException("synthetic"), IOException(), IllegalArgumentException())) {
            assertSame(failure, runCatching { archiveNetworkCall<Nothing> { throw failure } }.exceptionOrNull())
        }
    }
}
