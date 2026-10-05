package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.XMPPException.StreamErrorException
import org.jivesoftware.smack.packet.StreamError
import org.jivesoftware.smack.packet.StreamError.Condition
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thanosapollo.nema.session.SessionFailureReason

class ConnectionHealthClassificationTest {
    @Test fun onlyTransientStreamErrorsAreRecoverableAndSecurityPolicyErrorsStayTerminal() {
        val transient = setOf(Condition.connection_timeout, Condition.system_shutdown, Condition.reset,
            Condition.conflict, Condition.internal_server_error, Condition.remote_connection_failed, Condition.resource_constraint)
        for (condition in Condition.values()) {
            val expected = when {
                condition == Condition.not_authorized -> SessionFailureReason.AUTHENTICATION
                condition in transient -> SessionFailureReason.NETWORK
                else -> SessionFailureReason.CONFIGURATION
            }
            val failure = StreamErrorException(StreamError(condition, null, emptyMap(), emptyList()))
            assertEquals(condition.toString(), expected, classifySmackFailure(failure))
            assertEquals("wrapped $condition", expected, classifySmackFailure(java.io.IOException(failure)))
        }
    }
}
