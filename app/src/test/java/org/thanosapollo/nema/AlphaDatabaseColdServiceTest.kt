package org.thanosapollo.nema

import android.Manifest
import android.app.Service
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.thanosapollo.nema.credentials.CredentialBlobStore
import org.thanosapollo.nema.credentials.CredentialCipher
import org.thanosapollo.nema.credentials.CredentialVault
import org.thanosapollo.nema.credentials.WrappedCredential
import org.thanosapollo.nema.service.SessionRuntime
import org.thanosapollo.nema.service.XmppConnectionService
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionConnection
import org.thanosapollo.nema.session.SessionConnectionFactory
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@Config(sdk = [34], application = ColdServiceApplication::class)
class AlphaDatabaseColdServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<ColdServiceApplication>()
    private var service: ServiceController<XmppConnectionService>? = null

    @After fun close() {
        app.release.countDown()
        settled()
        service?.destroy()
        app.fixtureScope.cancel()
        if (app.databaseStartup.value == DatabaseStartup.READY) app.database.close()
    }

    private fun start(intent: Intent? = null, notificationsAllowed: Boolean = true): XmppConnectionService {
        assertTrue("production IO reached the open barrier", app.entered.await(10, TimeUnit.SECONDS))
        assertEquals(DatabaseStartup.OPENING, app.databaseStartup.value)
        if (notificationsAllowed) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildService(XmppConnectionService::class.java).create()
        service = controller
        controller.get().onStartCommand(intent, 0, 1)
        return controller.get()
    }

    private fun settled() = runBlocking {
        withTimeout(10_000) { app.databaseStartup.first { it != DatabaseStartup.OPENING } }
    }

    private fun ready() {
        app.release.countDown()
        assertEquals(DatabaseStartup.READY, settled())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCommand() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!app.connected.await(1, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("real runtime must reach the fixture connection exactly once", 1, app.connects.size)
        runBlocking { withTimeout(10_000) { app.sessionRuntime.state.first { it is org.thanosapollo.nema.session.ConnectionState.Connected } } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun nullStickyColdStartConnectsStoredActiveAccountOnceWithoutActivity() {
        val running = start()
        assertNotNull("foreground admission must not wait for Room", shadowOf(running).lastForegroundNotification)
        assertFalse(shadowOf(running).isStoppedBySelf)
        assertTrue(app.connects.isEmpty())
        ready()
        awaitCommand()
        assertEquals(listOf("a"), app.connects.toList())
        runBlocking { app.retryDatabaseStartup().join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a"), app.connects.toList())
        assertTrue(app.deletedNames.isEmpty())
        assertEquals("a", runBlocking { AccountRepository(app.database.accountDao()).activeAccount.first() }?.id?.value)
        app.database.openHelper.writableDatabase.query("SELECT body FROM message_drafts").use {
            assertTrue(it.moveToFirst()); assertEquals("draft body", it.getString(0))
        }
        assertNull("no Activity owns this delivery", shadowOf(app).nextStartedActivity)
    }

    @Test fun explicitConnectAlsoOwnsForegroundBeforeReadiness() {
        val running = start(Intent().setAction(XmppConnectionService.ACTION_CONNECT))
        assertNotNull(shadowOf(running).lastForegroundNotification)
        ready()
        awaitCommand()
        assertEquals(listOf("a"), app.connects.toList())
    }

    @Test fun destroyedServiceCannotReplayAfterReady() {
        start()
        service!!.destroy()
        service = null
        ready()
        assertTrue(app.connects.isEmpty())
        assertNull(app.sessionRuntime.onInsertedInbound)
    }

    @Test fun disconnectSupersedesDeferredConnectBeforeReady() {
        val running = start()
        assertEquals(Service.START_NOT_STICKY, running.onStartCommand(Intent().setAction(XmppConnectionService.ACTION_STOP), 0, 2))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("disconnect should stop waiting foreground immediately", shadowOf(running).isStoppedBySelf)
        assertTrue(shadowOf(running).isForegroundStopped)
        ready()
        assertTrue(app.connects.isEmpty())
        assertNull(app.sessionRuntime.onInsertedInbound)
    }

    @Test fun latestActivationReplacesDeferredStickyConnect() {
        val running = start()
        running.onStartCommand(Intent().setAction(XmppConnectionService.ACTION_ACTIVATE)
            .putExtra(XmppConnectionService.EXTRA_ACCOUNT_ID, "b"), 0, 2)
        ready()
        awaitCommand()
        assertEquals(listOf("b"), app.connects.toList())
    }

    @Test fun notificationLossWhileOpeningCancelsOwnedConnect() {
        val running = start()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(shadowOf(running).isStoppedBySelf)
        assertTrue(shadowOf(running).isForegroundStopped)
        ready()
        assertTrue(app.connects.isEmpty())
        assertNull(app.sessionRuntime.onInsertedInbound)
    }

    @Test fun deniedForegroundAdmissionCannotConnectAfterReady() {
        val running = start(notificationsAllowed = false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(running).isStoppedBySelf)
        assertNull(shadowOf(running).lastForegroundNotification)
        ready()
        assertTrue(app.connects.isEmpty())
        assertNull(app.sessionRuntime.onInsertedInbound)
    }

    @Test @Config(application = IncompatibleColdServiceApplication::class)
    fun terminalGateDoesNotReplayWhenUserLaterConfirmsReset() {
        val running = start()
        app.release.countDown()
        assertEquals(DatabaseStartup.RESET_REQUIRED, settled())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(running).isStoppedBySelf)
        assertTrue(app.deletedNames.isEmpty())
        runBlocking { app.resetDatabaseAndContinue().join() }
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("nema.db"), app.deletedNames.toList())
        assertTrue(app.connects.isEmpty())
        assertNull(app.sessionRuntime.onInsertedInbound)
    }

    @Test @Config(application = IncompatibleColdServiceApplication::class)
    fun terminalResetGateStopsDeferredForegroundWithoutRuntime() = terminalGate(DatabaseStartup.RESET_REQUIRED)

    @Test @Config(application = FailedColdServiceApplication::class)
    fun terminalFailureStopsDeferredForegroundWithoutRuntime() = terminalGate(DatabaseStartup.FAILED)

    @Test @Config(application = NewerColdServiceApplication::class)
    fun newerGateStopsDeferredForegroundWithoutRuntime() = terminalGate(DatabaseStartup.NEWER)

    private fun terminalGate(expected: DatabaseStartup) {
        val running = start()
        assertNotNull(shadowOf(running).lastForegroundNotification)
        app.release.countDown()
        assertEquals(expected, settled())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(running).isStoppedBySelf)
        assertTrue(shadowOf(running).isForegroundStopped)
        assertTrue(app.connects.isEmpty())
        assertTrue(app.deletedNames.isEmpty())
        assertTrue(runCatching { app.sessionRuntime }.isFailure)
    }
}

class IncompatibleColdServiceApplication : ColdServiceApplication() { override val mode = "incompatible" }
class FailedColdServiceApplication : ColdServiceApplication() { override val mode = "failed" }
class NewerColdServiceApplication : ColdServiceApplication() { override val mode = "newer" }

open class ColdServiceApplication : NemaApplication() {
    protected open val mode = "compatible"
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val connected = CountDownLatch(1)
    val connects = CopyOnWriteArrayList<String>()
    val deletedNames = CopyOnWriteArrayList<String>()
    val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var holdOpen = false

    override fun onCreate() {
        createHistoricalDatabase(this, avatar = mode == "incompatible")
        SQLiteDatabase.openDatabase(getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO accounts (id,bareJid,authenticationId,serviceDomain) VALUES ('b','b@example.org','b','example.org')")
            it.execSQL("INSERT INTO active_account (singletonId,accountId) VALUES (1,'a')")
            if (mode == "newer") it.version = 32
        }
        if (mode == "failed") getDatabasePath("nema.db").writeText("not sqlite")
        holdOpen = true
        super.onCreate()
    }

    override fun getDatabasePath(name: String): File {
        if (name == "nema.db" && holdOpen && Looper.myLooper() != Looper.getMainLooper()) {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "test open barrier expired" }
        }
        return super.getDatabasePath(name)
    }

    override fun deleteDatabase(name: String): Boolean {
        deletedNames += name
        return super.deleteDatabase(name)
    }

    internal override fun createSessionRuntime(opened: NemaDatabase): SessionRuntime {
        val vault = CredentialVault(object : CredentialBlobStore {
            override fun read(accountId: AccountId) = WrappedCredential(byteArrayOf(1), "fixture-only".toByteArray())
            override fun write(accountId: AccountId, credential: WrappedCredential) = error("No credential writes")
            override fun delete(accountId: AccountId) = Unit
        }, object : CredentialCipher {
            override fun encrypt(accountId: AccountId, plaintext: ByteArray) = error("No encryption")
            override fun decrypt(accountId: AccountId, credential: WrappedCredential) = credential.ciphertext.copyOf()
            override fun deleteKey(accountId: AccountId) = Unit
        })
        return SessionRuntime(AccountRepository(opened.accountDao()), vault, MessageStore(opened), peerIdentityStore,
            fixtureScope, SessionConnectionFactory { account, _, _ ->
                object : SessionConnection {
                    override var isUsable = true
                    override fun revoke() { isUsable = false }
                    override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
                        connects += account.id.value
                        connected.countDown()
                    }
                    override suspend fun reconnect(attempt: SessionAttemptIdentity) = error("No reconnect expected")
                    override fun updateAttempt(attempt: SessionAttemptIdentity) = Unit
                    override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = error("No network")
                    override suspend fun disconnect() { isUsable = false }
                }
            })
    }
}
