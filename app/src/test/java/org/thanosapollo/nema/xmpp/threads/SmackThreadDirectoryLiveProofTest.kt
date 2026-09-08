package org.thanosapollo.nema.xmpp.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.json.JSONObject
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.xmpp.smack.SmackAndroid
import org.thanosapollo.nema.xmpp.smack.XmppDomainCertificateVerifier

/** Opt-in real network test. Only the authorized disposable server hook supplies this private fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmackThreadDirectoryLiveProofTest {
    @Test fun authorizedDisposableServerProof() = runBlocking<Unit> {
        val path = System.getenv("NEMA_THREAD_PROOF_CLIENT_CONFIG")
        assumeTrue("No authorized disposable-server fixture supplied", path != null)
        val fixture = JSONObject(File(checkNotNull(path)).readText())
        val host = fixture.getString("host")
        require(fixture.getString("namespace") == THREAD_DIRECTORY_NAMESPACE)
        val address = InetAddress.getByName(fixture.getString("address"))
        require(address.isLoopbackAddress && fixture.getInt("port") in 1025..65535)
        val certificate = File(fixture.getString("ca_certificate")).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("disposable-proof", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        // Smack initializes its SSLContext itself; pass trust through its native trust-manager API.
        val trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val sockets = mutableListOf<XMPPTCPConnection>()
        fun connect(name: String): XMPPTCPConnection {
            val socket = XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(host).setHostAddress(address).setPort(fixture.getInt("port"))
                .setUsernameAndPassword(name, fixture.getJSONObject("accounts").getString(name))
                .setResource(Resourcepart.from("smack-proof-${UUID.randomUUID()}"))
                .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
                .setCustomX509TrustManager(trustManager).setHostnameVerifier(XmppDomainCertificateVerifier(host)).build())
            sockets += socket
            socket.replyTimeout = 8_000
            socket.connect().login()
            assertTrue(socket.isAuthenticated)
            assertTrue(socket.isSecureConnection)
            return socket
        }
        try {
            val bobSocket = connect("bob")
            val eveSocket = connect("eve")
            val bob = SmackThreadDirectoryClient(bobSocket)
            val eve = SmackThreadDirectoryClient(eveSocket)
            assertTrue(bob.isSupported())
            val direct = ThreadDirectoryScope.Direct("bob@$host", "eve@$host")
            assertTrue(bob.list(direct).items.isEmpty())
            val first = UUID.randomUUID()
            val createOperation = UUID.randomUUID()
            val created = bob.create(direct, first, "Smack empty < & 🚀", createOperation)
            assertEquals(1L, created.item.revision)
            assertFalse(created.replayed)
            assertTrue(bob.create(direct, first, "Smack empty < & 🚀", createOperation).replayed)
            repeat(3) { bob.create(direct, UUID.randomUUID(), "Other $it", UUID.randomUUID()) }
            val read = eve.list(direct)
            assertEquals(4, read.items.size)
            assertEquals("Smack empty < & 🚀", read.items.single { it.id == first }.title)
            assertTrue(read.items.none { it.canModify })
            expect<ThreadDirectoryException.Rejected> { eve.rename(direct, first, 1, "Forbidden", UUID.randomUUID()) }
            val renamed = bob.rename(direct, first, 1, "Shared rename", UUID.randomUUID())
            assertEquals(2L, renamed.item.revision)
            expect<ThreadDirectoryException.Conflict> { bob.rename(direct, first, 1, "Stale", UUID.randomUUID()) }
            assertEquals("Shared rename", eve.list(direct).items.single { it.id == first }.title)
            bob.archive(direct, first, 2, true, UUID.randomUUID())
            assertTrue(eve.list(direct).items.single { it.id == first }.archived)
            println("SMACK_PROOF PASS direct empty creation, exact replay, complete pagination, reader permission, rename/CAS/archive")

            val roomJid = JidCreate.entityBareFrom("smack-${UUID.randomUUID()}@${fixture.getString("muc_host")}")
            val room = MultiUserChatManager.getInstanceFor(bobSocket).getMultiUserChat(roomJid)
            room.create(Resourcepart.from("proof-owner"))
            val configuration = room.configurationForm.fillableForm
            configuration.setAnswer("muc#roomconfig_persistentroom", true)
            configuration.setAnswer("muc#roomconfig_membersonly", true)
            room.sendConfigurationForm(configuration)
            room.grantMembership(JidCreate.entityBareFrom("eve@$host"))
            val unboundRoom = ThreadDirectoryScope.Muc(roomJid.toString())
            val emptyRoom = bob.list(unboundRoom)
            assertTrue(emptyRoom.items.isEmpty())
            val boundRoom = emptyRoom.scope as ThreadDirectoryScope.Muc
            assertNotNull(boundRoom.incarnation)
            val roomThread = UUID.randomUUID()
            bob.create(boundRoom, roomThread, "Shared empty room thread", UUID.randomUUID())
            val memberRead = eve.list(unboundRoom)
            assertEquals(boundRoom, memberRead.scope)
            assertEquals(roomThread, memberRead.items.single().id)
            assertFalse(memberRead.items.single().canModify)
            expect<ThreadDirectoryException.Rejected> { eve.rename(boundRoom, roomThread, 1, "Forbidden", UUID.randomUUID()) }
            bob.rename(boundRoom, roomThread, 1, "Room renamed", UUID.randomUUID())
            bob.archive(boundRoom, roomThread, 2, true, UUID.randomUUID())
            assertTrue(eve.list(unboundRoom).items.single().archived)
            bob.archive(boundRoom, roomThread, 3, false, UUID.randomUUID())
            assertFalse(eve.list(unboundRoom).items.single().archived)
            println("SMACK_PROOF PASS native persistent members-only MUC, incarnation-bound create, offline member read, rename/archive/unarchive")

            bobSocket.disconnect()
            val freshBob = SmackThreadDirectoryClient(connect("bob"))
            assertEquals("Shared rename", freshBob.list(direct).items.single { it.id == first }.title)
            assertTrue(freshBob.list(direct).items.single { it.id == first }.canModify)
            val recoveredRoom = freshBob.list(unboundRoom)
            assertEquals(boundRoom, recoveredRoom.scope)
            assertEquals("Room renamed", recoveredRoom.items.single().title)
            println("SMACK_PROOF PASS fresh authenticated Smack resource recovers exact direct and MUC metadata without MAM")
        } finally {
            sockets.asReversed().forEach { if (it.isConnected) it.disconnect() }
        }
    }

    private suspend inline fun <reified T : Throwable> expect(noinline action: suspend () -> Unit) {
        try { action() } catch (failure: Throwable) {
            if (failure is T) return
            throw AssertionError("Unexpected directory failure", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
