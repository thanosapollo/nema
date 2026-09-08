package org.thanosapollo.nema

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.thanosapollo.nema.service.XmppConnectionService
import org.thanosapollo.nema.service.SessionRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Hold the production account mutex on Main, matching connectActive/activate across IO.
// A driver thread invokes the real destruction callback while Main is paused, so a
// regression can report failure and resume Main for cleanup instead of hanging the JVM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ColdServiceApplication::class)
class ServiceDestructionTest {
    @Test fun destroyDoesNotWaitForMainOwnedAccountCommand() {
        val app = ApplicationProvider.getApplicationContext<ColdServiceApplication>()
        app.release.countDown()
        runBlocking { withTimeout(10_000) { while (app.databaseStartup.value == DatabaseStartup.OPENING) delay(1) } }
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        val service = Robolectric.buildService(XmppConnectionService::class.java).create()
        val field = SessionRuntime::class.java.getDeclaredField("accountCommands").apply { isAccessible = true }
        val mutex = field.get(app.sessionRuntime) as Mutex
        val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val entered = CountDownLatch(1)
        val command = commandScope.launch {
            mutex.withLock {
                entered.countDown()
                awaitCancellation()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val returned = CountDownLatch(1)
        val driver = Thread {
            command.cancel() // A canceled Main command cannot release its lock until Main runs.
            service.get().onDestroy()
            returned.countDown()
        }
        driver.start()
        val prompt = returned.await(1, TimeUnit.SECONDS)
        try {
            println("DESTROY_DIAG prompt=$prompt accountMutexLocked=${mutex.isLocked} commandCompleted=${command.isCompleted}")
            assertTrue("onDestroy must not synchronously wait for a canceled Main-owned account command", prompt)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("diagnostic driver settled after Main resumed", returned.await(10, TimeUnit.SECONDS))
            driver.join(1_000)
            runBlocking { command.join(); app.sessionRuntime.stop() }
            commandScope.cancel()
            app.fixtureScope.cancel()
            app.database.close()
        }
    }
}
