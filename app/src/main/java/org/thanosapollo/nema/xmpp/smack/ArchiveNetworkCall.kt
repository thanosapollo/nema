package org.thanosapollo.nema.xmpp.smack

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.jivesoftware.smack.SmackException
import org.thanosapollo.nema.xmpp.transport.TransientArchiveException

/** Wrap only Smack network calls, never normalization, callbacks or database work. */
internal fun <T> archiveNetworkCall(block: () -> T): T = try {
    block()
} catch (failure: Exception) {
    when (failure) {
        is SmackException.NoResponseException,
        is SmackException.NotConnectedException,
        is SocketTimeoutException,
        is SocketException,
        is EOFException -> throw TransientArchiveException(failure)
        else -> throw failure // Cancellation, IQ errors and unknown failures are not transient.
    }
}
