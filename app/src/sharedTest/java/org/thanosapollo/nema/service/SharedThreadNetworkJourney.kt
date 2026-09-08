package org.thanosapollo.nema.service

import android.app.Application
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.json.JSONObject
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import org.junit.Assert.*
import org.thanosapollo.nema.account.*
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.credentials.*
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.*
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.threads.*
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.chatstates.installNemaChatStateProviders
import org.thanosapollo.nema.xmpp.markers.installNemaChatMarkerProviders
import org.thanosapollo.nema.xmpp.reactions.installNemaReactionProviders
import org.thanosapollo.nema.xmpp.reply.installNemaReplyProviders
import org.thanosapollo.nema.xmpp.rtt.installNemaRttProviders

/**
 * Opt-in sockets -> production adapter/controller/runtime -> file-backed Room -> presenter.
 * Only the disposable proof hook may supply credentials. No production factory TLS override,
 * scripted directory responses, message seeding, device, real Activity or server restart claim.
 */
class SharedThreadNetworkJourney(private val context: Application) {
    fun run(fixtureFile: File) = runBlocking<Unit> {
        require(context.javaClass == Application::class.java) { "Bare test Application required" }
        val fixture = JSONObject(fixtureFile.readText())
        require(fixture.getString("host") == "proof.test")
        require(fixture.getString("muc_host") == "rooms.proof.test")
        require(fixture.getString("namespace") == THREAD_DIRECTORY_NAMESPACE)
        val address = InetAddress.getByName(fixture.getString("address"))
        require(address.isLoopbackAddress && fixture.getInt("port") in 1025..65535)
        SmackAndroid.initialize(context)
        val providers = SavedProviders()
        val sockets = mutableListOf<XMPPTCPConnection>()
        val clients = mutableListOf<Client>()
        val databases = mutableSetOf<String>()
        val backgroundFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val certificate = File(fixture.getString("ca_certificate")).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("disposable-proof", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        fun socket(name: String): XMPPTCPConnection = XMPPTCPConnection(
            XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain("proof.test").setHostAddress(address).setPort(fixture.getInt("port"))
                .setUsernameAndPassword(name, null)
                .setResource(Resourcepart.from("nema-proof-${UUID.randomUUID()}"))
                .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
                .setCustomX509TrustManager(trust)
                .setHostnameVerifier(XmppDomainCertificateVerifier("proof.test")).build(),
        ).apply {
            sockets += this
            replyTimeout = 8_000
            setUseStreamManagement(false)
            setUseStreamManagementResumption(false)
            setParsingExceptionCallback(NemaParsingExceptionCallback)
            advertiseNemaFeatures(this)
            ReconnectionManager.getInstanceFor(this).disableAutomaticReconnection()
        }
        suspend fun start(name: String, databaseName: String = "network-${UUID.randomUUID()}.db", online: Boolean = true): Client {
            databases += databaseName
            val db = NemaDatabase.create(context, databaseName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> backgroundFailures += error })
            val account = AccountConfiguration.create(AccountId.require("fixture-$name"), "$name@proof.test", name,
                null, "proof.test", NetworkEndpoint.create("127.0.0.1", fixture.getInt("port")))
            val accounts = AccountRepository(db.accountDao())
            val credentials = CredentialVault(MemoryBlobs(), EphemeralCipher())
            val store = MessageStore(db)
            // Match application ordering: a presenter is constructed only for a persisted account.
            accounts.save(account); accounts.activate(account.id)
            credentials.store(account.id, fixture.getJSONObject("accounts").getString(name).toCharArray())
            val factory = SessionConnectionFactory { configured, identity, event ->
                require(configured == account && identity.accountId == account.id)
                // The only substitute is connection construction for the fixture CA.
                // Authentication, attempt ownership, IQs, roster and ingress are production.
                SmackSessionConnection(socket(name), name, account.bareJid.value, event)
            }
            val runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(db.messageDao()), scope, factory)
            val repository = ChatRepository(db)
            val presenter = DirectChatPresenter(account, repository, scope, runtime::enqueueDirect,
                joinMuc = { runtime.joinMuc(it) }, observeRoom = { runtime.rooms.observe(account.id.value, it) },
                directoryConnection = runtime.state, refreshDirectory = runtime::refreshThreadDirectory,
                changeDirectory = runtime::changeThreadDirectory)
            val client = Client(databaseName, db, scope, runtime, presenter, repository, store, account, credentials)
            clients += client
            if (online) {
                assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
                withTimeout(12_000) { runtime.state.first { it is ConnectionState.Connected } }
                assertTrue(sockets.last().isSecureConnection)
                assertEquals(account.bareJid.value, sockets.last().user.asBareJid().toString())
                assertFalse(ServiceDiscoveryManager.getInstanceFor(sockets.last()).serverSupportsFeature("urn:xmpp:mam:2"))
            }
            return client
        }
        try {
            providers.install()
            // Native Smack peer bootstraps only the persistent members-only room and memberships.
            // It later acts as the creator's independent remote resource for rename.
            val remote = socket("bob")
            withContext(Dispatchers.IO) { remote.connect(); remote.login("bob", fixture.getJSONObject("accounts").getString("bob")) }
            val roomJid = "nema-${UUID.randomUUID()}@rooms.proof.test"
            val room = MultiUserChatManager.getInstanceFor(remote).getMultiUserChat(JidCreate.entityBareFrom(roomJid))
            withContext(Dispatchers.IO) {
                room.create(Resourcepart.from("bootstrap"))
                val form = room.configurationForm.fillableForm
                form.setAnswer("muc#roomconfig_persistentroom", true)
                form.setAnswer("muc#roomconfig_membersonly", true)
                room.sendConfigurationForm(form)
                room.grantMembership(JidCreate.entityBareFrom("eve@proof.test"))
            }
            val owner = start("bob")
            val directPeer = "eve@proof.test"
            val destinations = linkedMapOf<MessageKind, ThreadRef>()
            for (kind in listOf(MessageKind.CHAT, MessageKind.GROUPCHAT)) {
                val peer = if (kind == MessageKind.CHAT) directPeer else roomJid
                val state = owner.open(peer, kind)
                assertTrue(state.recentThreads.isEmpty())
                val view = withTimeout(12_000) { owner.presenter.directoryState.first { it.mode == DirectoryMode.SHARED } }
                assertTrue(owner.presenter.createSharedNamedThread(state.routeOccurrence, "Empty $kind < & 🚀", checkNotNull(view.context)))
                val created = withTimeout(12_000) { owner.presenter.state.first { it.selectedThread != null && it.recentThreads.size == 1 && it.contentStatus == ChatContentStatus.Ready } }
                destinations[kind] = created.recentThreads.single().thread
                assertTrue(created.recentThreads.single().shared!!.canModify)
                assertTrue(owner.store.messages(owner.account.id.value).isEmpty())
            }
            println("NEMA_NETWORK PASS presenter creates empty direct and MUC destinations through real authenticated runtime IQs")
            val reader = start("eve")
            suspend fun verifyNames(client: Client, renamed: Boolean, online: Boolean = true) {
                for ((kind, thread) in destinations) {
                    val peer = if (kind == MessageKind.CHAT) "bob@proof.test" else roomJid
                    client.open(peer, kind)
                    if (online) {
                        client.presenter.refreshNamedThreads(client.presenter.state.value.routeOccurrence)
                        withTimeout(12_000) { client.presenter.directoryState.first { it.mode == DirectoryMode.SHARED } }
                    }
                    val expected = if (renamed) "Remote $kind rename" else "Empty $kind < & 🚀"
                    val state = withTimeout(12_000) { client.presenter.state.first { it.recentThreads.singleOrNull()?.title == expected } }
                    assertEquals(thread, state.recentThreads.single().thread)
                    assertFalse(state.recentThreads.single().shared!!.canModify)
                    assertTrue(state.messages.isEmpty())
                    assertTrue(client.store.messages(client.account.id.value).isEmpty())
                    assertTrue(client.db.messageDao().observeThreadTitles(client.account.id.value, peer).first().isEmpty())
                    val rows = client.db.sharedThreadDao().rows(client.account.id.value, peer, kind)
                    assertEquals(expected, rows.single().title)
                    assertEquals(if (renamed) 2L else 1L, rows.single().revision)
                }
            }
            verifyNames(reader, false)
            println("NEMA_NETWORK PASS fresh second-account runtime discovers exact empty direct and MUC IDs in Room and presenter, no bodies or MAM")
            val directory = SmackThreadDirectoryClient(remote)
            for ((kind, thread) in destinations) {
                val peer = if (kind == MessageKind.CHAT) "bob@proof.test" else roomJid
                reader.open(peer, kind)
                val occurrence = reader.presenter.state.value.routeOccurrence
                reader.presenter.refreshNamedThreads(occurrence)
                withTimeout(12_000) { reader.presenter.directoryState.first { it.mode == DirectoryMode.SHARED } }
                assertEquals("Empty $kind < & 🚀", reader.presenter.state.value.recentThreads.single().title)
                val scope = if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct("bob@proof.test", "eve@proof.test")
                    else directory.list(ThreadDirectoryScope.Muc(roomJid)).scope
                directory.rename(scope, UUID.fromString(thread.id.value), 1, "Remote $kind rename", UUID.randomUUID())
                assertEquals(occurrence, reader.presenter.state.value.routeOccurrence)
                assertEquals("Empty $kind < & 🚀", reader.presenter.state.value.recentThreads.single().title)
                reader.presenter.refreshNamedThreads(occurrence)
                val refreshed = withTimeout(12_000) { reader.presenter.state.first { it.recentThreads.singleOrNull()?.title == "Remote $kind rename" } }
                assertEquals(occurrence, refreshed.routeOccurrence)
                assertEquals(thread, refreshed.recentThreads.single().thread)
                assertEquals(2L, reader.db.sharedThreadDao().rows(reader.account.id.value, peer, kind).single().revision)
            }
            verifyNames(reader, true)
            println("NEMA_NETWORK PASS creator remote Smack resource rename reaches explicit presenter refresh with stable IDs and revision two")
            val readerDatabase = reader.databaseName
            reader.close()
            val reopened = start("eve", readerDatabase, online = false)
            verifyNames(reopened, true, online = false)
            reopened.close()
            val fresh = start("eve")
            assertTrue(fresh.db.sharedThreadDao().rows(fresh.account.id.value, "bob@proof.test", MessageKind.CHAT).isEmpty())
            verifyNames(fresh, true)
            println("NEMA_NETWORK PASS stopped runtime and reopened file store recover offline; fresh empty Room plus fresh authenticated runtime recovers renamed metadata before bodies, MAM absent")
            // Only now are any bodies sent. Both ends use the production presenter/outbox and ingress.
            for ((kind, thread) in destinations) {
                val recipient = if (kind == MessageKind.CHAT) directPeer else roomJid
                val sourcePeer = if (kind == MessageKind.CHAT) "bob@proof.test" else roomJid
                if (kind == MessageKind.GROUPCHAT) {
                    assertTrue(owner.runtime.joinMuc(roomJid, "nema-owner"))
                    assertTrue(fresh.runtime.joinMuc(roomJid, "nema-reader"))
                }
                owner.open(recipient, kind)
                fresh.open(sourcePeer, kind)
                owner.presenter.selectThreadDestination(owner.presenter.state.value.routeOccurrence, thread)
                fresh.presenter.selectThreadDestination(fresh.presenter.state.value.routeOccurrence, thread)
                withTimeout(12_000) { owner.presenter.state.first { it.selectedThread == thread && it.contentStatus == ChatContentStatus.Ready } }
                withTimeout(12_000) { fresh.presenter.state.first { it.selectedThread == thread && it.contentStatus == ChatContentStatus.Ready } }
                val body = "Exact $kind body ${UUID.randomUUID()}"
                val draft = DraftSnapshot(DirectConversationKey(owner.account.id.value, recipient, thread), body, 1, groupChat = kind == MessageKind.GROUPCHAT)
                assertTrue(owner.presenter.sendDraft(draft).await())
                val received = withTimeout(12_000) { fresh.presenter.state.first { state -> state.messages.any { it.body == body } } }
                assertEquals(thread, received.selectedThread)
                val record = fresh.store.messages(fresh.account.id.value).single { it.body == body }
                assertEquals(thread.id.value, record.threadId)
                assertEquals(kind, record.messageKind)
                assertEquals(sourcePeer, record.peerJid)
            }
            println("NEMA_NETWORK PASS named direct and MUC bodies traverse presenter outbox sockets and production ingress into exact Room thread IDs and selected presenter timelines")
            backgroundFailures.peek()?.let { throw AssertionError("Uncaught background runtime/presenter failure", it) }
        } finally {
            try {
                withContext(NonCancellable) {
                    var failure: Throwable? = null
                    for (client in clients.asReversed()) try { client.close() } catch (error: Throwable) { failure = error }
                    for (connection in sockets.asReversed()) try {
                        if (connection.isConnected) connection.disconnect()
                    } catch (error: Throwable) { failure = error }
                    databases.forEach { name -> context.deleteDatabase(name); assertFalse(context.getDatabasePath(name).exists()) }
                    failure?.let { throw it }
                }
            } finally { providers.restore() }
        }
        // Include errors raised during runtime shutdown, not only during the journey.
        backgroundFailures.peek()?.let { throw AssertionError("Uncaught background runtime/presenter failure", it) }
    }

    private class Client(
        val databaseName: String, val db: NemaDatabase, val scope: CoroutineScope,
        val runtime: SessionRuntime, val presenter: DirectChatPresenter, val repository: ChatRepository,
        val store: MessageStore, val account: AccountConfiguration, val credentials: CredentialVault,
    ) {
        private var closed = false
        suspend fun open(peer: String, kind: MessageKind): DirectChatState {
            if (kind == MessageKind.GROUPCHAT) repository.markRoom(account.id.value, peer)
            presenter.selectPeer(peer)
            return withTimeout(12_000) { presenter.state.first { it.selectedPeer == peer && it.selectedThread == null && it.contentStatus == ChatContentStatus.Ready && it.selectedPeerGroupChat == (kind == MessageKind.GROUPCHAT) } }
        }
        suspend fun close() {
            if (closed) return
            closed = true
            try { presenter.close(); runtime.serviceDestroyed() } finally {
                try { scope.coroutineContext[Job]!!.cancelAndJoin() } finally {
                    try { credentials.delete(account.id) } finally { db.close() }
                }
            }
        }
    }

    /** Private in-memory fixture vault only; never Android keystore or user's credential store. */
    private class MemoryBlobs : CredentialBlobStore {
        private val values = mutableMapOf<AccountId, WrappedCredential>()
        override fun read(accountId: AccountId) = values[accountId]
        override fun write(accountId: AccountId, credential: WrappedCredential) { values[accountId] = credential }
        override fun delete(accountId: AccountId) { values.remove(accountId)?.ciphertext?.fill(0) }
    }
    private class EphemeralCipher : CredentialCipher {
        override fun encrypt(accountId: AccountId, plaintext: ByteArray) = WrappedCredential(byteArrayOf(1), plaintext.copyOf())
        override fun decrypt(accountId: AccountId, credential: WrappedCredential) = credential.ciphertext.copyOf()
        override fun deleteKey(accountId: AccountId) = Unit
    }

    /** Capture before Nema's application/factory installers; restore exact object identity, including null. */
    private class SavedProviders {
        private val keys = listOf(
            "sent" to "urn:xmpp:carbons:2", "received" to "urn:xmpp:carbons:2",
            "x" to "http://jabber.org/protocol/muc#user", "result" to "urn:xmpp:mam:2",
            "origin-id" to "urn:xmpp:sid:0", "stanza-id" to "urn:xmpp:sid:0",
            "occupant-id" to "urn:xmpp:occupant-id:0", "replace" to "urn:xmpp:message-correct:0",
            "reply" to "urn:xmpp:reply:0", "reactions" to "urn:xmpp:reactions:0", "rtt" to "urn:xmpp:rtt:0",
        ) + listOf("markable", "received", "displayed", "acknowledged").map { it to "urn:xmpp:chat-markers:0" } +
            listOf("active", "composing", "paused", "inactive", "gone").map { it to "http://jabber.org/protocol/chatstates" }
        private val previous = keys.associateWith { (element, namespace) -> ProviderManager.getExtensionProvider(element, namespace) }
        private val directory = ProviderManager.getIQProvider("directory", THREAD_DIRECTORY_NAMESPACE)
        fun install() {
            installNemaCarbonProvider(); installNemaMucUserProvider(); installNemaMamResultProvider(); installNemaSidProviders()
            requireNemaMucUserProvider(); requireNemaMamResultProvider()
            installNemaOccupantIdProvider(); installNemaCorrectionProvider(); installNemaReplyProviders()
            installNemaChatMarkerProviders(); installNemaChatStateProviders(); installNemaReactionProviders(); installNemaRttProviders()
        }
        fun restore() {
            previous.forEach { (key, provider) ->
                ProviderManager.removeExtensionProvider(key.first, key.second)
                if (provider != null) ProviderManager.addExtensionProvider(key.first, key.second, provider)
                assertSame(provider, ProviderManager.getExtensionProvider(key.first, key.second))
            }
            ProviderManager.removeIQProvider("directory", THREAD_DIRECTORY_NAMESPACE)
            if (directory != null) ProviderManager.addIQProvider("directory", THREAD_DIRECTORY_NAMESPACE, directory)
            assertSame(directory, ProviderManager.getIQProvider("directory", THREAD_DIRECTORY_NAMESPACE))
        }
    }
}
