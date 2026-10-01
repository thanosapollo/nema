package org.thanosapollo.nema.update

import android.app.Application
import java.io.File
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateNativeCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancelDuringNativeReadStopsBeforeWritingOrReadingAgainAndAllowsExplicitRetry() =
        cancelNativeRead(readTimesOut = false)

    @Test fun cancelledNativeReadTimeoutClosesAndSettlesBeforeExplicitRetry() =
        cancelNativeRead(readTimesOut = true)

    private fun cancelNativeRead(readTimesOut: Boolean) = runBlocking {
        val directory = temporary.newFolder()
        val part = directory.resolve(PART_FILE_NAME)
        val processJob = SupervisorJob()
        val process = CoroutineScope(processJob + Dispatchers.Default)
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val reads = AtomicInteger()
        val closed = AtomicInteger()
        val opens = AtomicInteger()
        val packageReads = AtomicInteger()
        val installs = AtomicInteger()
        val body = "12345678".toByteArray()
        val url = "https://git.thanosapollo.org/nema/releases/nema.apk"
        val stream = object : InputStream() {
            var offset = 0
            override fun read(): Int = error("bulk read expected")
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                reads.incrementAndGet()
                if (offset == 0) {
                    readEntered.countDown()
                    releaseRead.awaitGate()
                    if (readTimesOut) throw SocketTimeoutException("gated read timeout")
                }
                if (offset == body.size) return -1
                bytes[off] = body[offset++]
                return 1
            }
            override fun close() { closed.incrementAndGet() }
        }
        val firstConnection = Connection(url, stream) {
            disconnectEntered.countDown()
            releaseDisconnect.awaitGate()
        }
        val retryConnection = Connection(url, body.inputStream())
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val signer = "b".repeat(64)
        val json = """{"schemaVersion":1,"packageName":"org.thanosapollo.nema","versionCode":7,"versionName":"0.4","apkUrl":"$url","size":${body.size},"sha256":"$hash","signerSha256":"$signer","sourceCommit":"${"c".repeat(40)}"}"""
        val coordinator = UpdateCoordinator(process, {
            UpdateRepository(
                5, ManifestFetcher { json }, { 100L }, { 0L }, {}, { true },
                updateDirectory = directory,
                downloadEffect = HttpsApkDownloadEffect {
                    when (opens.incrementAndGet()) {
                        1 -> firstConnection
                        2 -> retryConnection
                        else -> error("unexpected automatic retry")
                    }
                },
                deleteFile = { file ->
                    if (file == part && opens.get() == 1) {
                        cleanupEntered.countDown()
                        releaseCleanup.awaitGate()
                    }
                    file.delete()
                },
                packageFacts = object : PackageFactsAdapter {
                    override fun installed(): PackageFacts {
                        packageReads.incrementAndGet()
                        return PackageFacts(NEMA_PACKAGE_NAME, 5, setOf(signer), setOf(signer))
                    }
                    override fun archive(file: File) =
                        PackageFacts(NEMA_PACKAGE_NAME, 7, setOf(signer), setOf(signer))
                },
                installLauncher = { _, _ -> installs.incrementAndGet(); true },
            )
        })
        try {
            withTimeout(10_000) { coordinator.requestCheck()!!.join() }
            val accepted = withTimeout(10_000) { coordinator.state.first { it is UpdateState.Available } }
            val preparing = coordinator.requestUpdate()!!
            readEntered.awaitGate()
            assertEquals(10_000, firstConnection.readTimeout)
            assertEquals(10_000, firstConnection.connectTimeout)
            val priorChildren = processJob.children.toSet()

            coordinator.cancelUpdate()
            val settlement = processJob.children.single { it !in priorChildren }
            assertTrue(preparing.isCancelled)
            assertFalse(preparing.isCompleted)
            assertFalse(settlement.isCompleted)
            assertNull(coordinator.requestUpdate())
            assertNull(coordinator.requestCheck())
            assertNull(coordinator.installRequest.value)
            assertEquals(0, firstConnection.disconnects.get())

            // One blocking read may finish (or hit the configured transport timeout).
            // Nothing may consume another chunk or write the returned cancelled chunk.
            releaseRead.countDown()
            disconnectEntered.awaitGate()
            assertEquals(1, closed.get())
            assertEquals("Cancel must stop at the next native read boundary", 1, reads.get())
            assertEquals("Cancelled bytes must not be written", 0L, part.length())
            assertFalse(preparing.isCompleted)
            assertFalse(settlement.isCompleted)
            assertNull(coordinator.requestUpdate())
            assertNull(coordinator.requestCheck())

            releaseDisconnect.countDown()
            cleanupEntered.awaitGate()
            assertEquals(1, firstConnection.disconnects.get())
            assertFalse(preparing.isCompleted)
            assertFalse(settlement.isCompleted)
            assertNull(coordinator.requestUpdate())
            assertNull(coordinator.requestCheck())
            releaseCleanup.countDown()
            withTimeout(10_000) { preparing.join(); settlement.join() }
            assertTrue(preparing.isCancelled && preparing.isCompleted)
            assertTrue(settlement.isCompleted && !settlement.isCancelled)
            assertEquals(accepted, withTimeout(10_000) { coordinator.state.first { it is UpdateState.Available } })
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(1, opens.get())
            assertEquals(0, packageReads.get())
            assertEquals(0, installs.get())
            assertNull(coordinator.installRequest.value)
            // No cancelled preparation can leave a permission continuation to revive.
            coordinator.onInstallPermissionResult(true)
            assertNull(coordinator.installRequest.value)

            val retry = coordinator.requestUpdate()!!
            assertNotSame(preparing, retry)
            withTimeout(10_000) { retry.join() }
            assertFalse(retry.isCancelled)
            val request = coordinator.installRequest.value!!
            assertSame(accepted.accepted, request.authority.artifact.accepted)
            assertTrue(request.mayRequestPermission)
            assertArrayEquals(body, request.authority.artifact.file.readBytes())
            assertFalse(part.exists())
            assertEquals(2, opens.get())
            assertEquals(1, retryConnection.disconnects.get())
            assertEquals(1, packageReads.get())
            assertEquals(0, installs.get()) // preparation alone never launches Android
        } finally {
            releaseRead.countDown()
            releaseDisconnect.countDown()
            releaseCleanup.countDown()
            withTimeout(10_000) { processJob.cancelAndJoin() }
        }
    }

    private fun CountDownLatch.awaitGate() =
        assertTrue("Native transport phase did not settle within the test bound", await(10, TimeUnit.SECONDS))

    private class Connection(
        url: String,
        private val stream: InputStream,
        private val beforeDisconnect: () -> Unit = {},
    ) : HttpsURLConnection(URL(url)) {
        val disconnects = AtomicInteger()
        override fun getResponseCode() = 200
        override fun disconnect() { beforeDisconnect(); disconnects.incrementAndGet() }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = ""
        override fun getLocalCertificates() = null
        override fun getServerCertificates() = null
        override fun getInputStream() = stream
    }
}
