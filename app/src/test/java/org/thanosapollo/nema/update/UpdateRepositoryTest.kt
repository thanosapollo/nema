package org.thanosapollo.nema.update

import android.app.Application
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateRepositoryTest {
    private val apkUrl = "https://git.thanosapollo.org/nema/releases/0.1.1/Nema-0.1.1.apk"
    private val json = """{"schemaVersion":1,"packageName":"org.thanosapollo.nema","versionCode":3,"versionName":"0.2","apkUrl":"$apkUrl","size":42,"sha256":"${"a".repeat(64)}","signerSha256":"${"b".repeat(64)}","sourceCommit":"${"c".repeat(40)}"}"""

    @Test fun `canonical manifest parses and version code decides availability`() {
        val manifest = UpdateManifest.parse(json)
        assertEquals(3L, manifest.versionCode)
        assertTrue(manifest.accept(3) is AcceptedUpdate.Current)
        assertTrue(manifest.accept(2) is AcceptedUpdate.Available)
    }

    @Test fun `strict manifest rejects invalid fields`() {
        val invalid = listOf(
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            json.replace("org.thanosapollo.nema", "org.example.nema"),
            json.replace("\"versionName\":\"0.2\"", "\"versionName\":\" \""),
            json.replace("c".repeat(40), "c".repeat(39)),
            json.replace("c".repeat(40), "c".repeat(41)),
            json.replace("a".repeat(64), "A" + "a".repeat(63)),
            json.replace("b".repeat(64), "B" + "b".repeat(63)),
            json.replace("\"size\":42,", ""),
            json.dropLast(1) + ",\"extra\":1}",
            json.replace("\"versionCode\":3", "\"versionCode\":0"),
            json.replace("\"versionCode\":3", "\"versionCode\":1.5"),
            json.replace("https://", "http://"),
            json.replace("git.thanosapollo.org", "evil.example"),
            json.replace("/nema/releases/0.1.1/Nema-0.1.1.apk", "/other.apk"),
            json.replace("https://", "https://user@"),
            json.replace(apkUrl, "$apkUrl?x=1"),
            json.replace(apkUrl, "$apkUrl#x"),
            json.replace("0.1.1/Nema-0.1.1.apk", "%2e%2e/evil.apk"),
        )
        invalid.forEach { candidate ->
            try { UpdateManifest.parse(candidate); fail(candidate) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `strict manifest rejects permissive JSON syntax and every wrong token type`() {
        val invalid = listOf(
            json.dropLast(1) + ",\"schemaVersion\":1}",
            "$json true",
            "$json trailing",
            "/* comment */$json",
            json.replace("\"schemaVersion\":1", "'schemaVersion':1"),
            json.replace("\"schemaVersion\":1", "schemaVersion:1"),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            json.replace("\"packageName\":\"org.thanosapollo.nema\"", "\"packageName\":1"),
            json.replace("\"versionCode\":3", "\"versionCode\":\"3\""),
            json.replace("\"versionName\":\"0.2\"", "\"versionName\":false"),
            json.replace("\"apkUrl\":\"$apkUrl\"", "\"apkUrl\":[]"),
            json.replace("\"size\":42", "\"size\":42.0"),
            json.replace("\"sha256\":\"${"a".repeat(64)}\"", "\"sha256\":{}"),
            json.replace("\"signerSha256\":\"${"b".repeat(64)}\"", "\"signerSha256\":null"),
            json.replace("\"sourceCommit\":\"${"c".repeat(40)}\"", "\"sourceCommit\":1"),
        )

        invalid.forEach { candidate ->
            try { UpdateManifest.parse(candidate); fail(candidate) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `release URL accepts safe directories ending in one apk`() {
        val invalidUrls = listOf(
            "https://git.thanosapollo.org/nema/releases/nema.zip",
            "https://git.thanosapollo.org/nema/releases//evil.apk",
            "https://git.thanosapollo.org//nema/releases/evil.apk",
            "https://git.thanosapollo.org/nema/releases/nema.apk/",
            "https://git.thanosapollo.org/nema/releases/nested.apk/evil.apk",
            "https://git.thanosapollo.org/nema/releases/nema.apk;download",
            "https://git.thanosapollo.org/nema/releases/;name.apk",
            "https://git.thanosapollo.org/nema/releases/nema.apk/../evil.apk",
            """https://git.thanosapollo.org/nema/releases/nema.apk\\evil.apk""",
        )

        assertEquals(apkUrl, UpdateManifest.parse(json).apkUrl)
        invalidUrls.forEach { url ->
            val candidate = json.replace(apkUrl, url)
            try { UpdateManifest.parse(candidate); fail(url) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `checking is exposed while a gated check is active`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = UpdateRepository(2, FakeFetcher(json, gate), { 1L }, { 0L }, {}, { true })

        assertFalse(repository.state is MutableStateFlow<*>)
        val check = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }

        assertTrue(repository.state.value is UpdateState.Checking)
        assertNull(repository.state.value.accepted)
        gate.complete(Unit)
        check.await()
        assertTrue(repository.state.value is UpdateState.Available)
    }

    @Test fun `manual replacement failure preserves accepted authority until next success`() = runTest {
        val failures = listOf(
            Result.success(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
            Result.failure(IllegalStateException("transport failed")),
        )

        failures.forEach { failure ->
            var now = 7L
            var saved = 0L
            val fetcher = FakeFetcher(json)
            val repository = UpdateRepository(2, fetcher, { now }, { saved }, { saved = it }, { true })
            repository.checkManual()
            assertTrue(repository.state.value is UpdateState.Available)
            val accepted = repository.state.value.accepted!!
            val acceptedTimestamp = saved
            assertEquals(7L, acceptedTimestamp)
            val gate = CompletableDeferred<Unit>()
            fetcher.result = failure
            fetcher.gate = gate
            now = 9L

            val replacement = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
            assertTrue(repository.state.value is UpdateState.Checking)
            assertSame(accepted, repository.state.value.accepted)
            gate.complete(Unit)
            replacement.await()

            val failed = repository.state.value as UpdateState.Failed
            assertEquals("Could not check for updates. Try again.", failed.message)
            assertSame(accepted, failed.accepted)
            assertSame(accepted.value, failed.accepted!!.value)
            assertEquals(accepted.generation, failed.accepted!!.generation)
            assertEquals(acceptedTimestamp, saved)

            fetcher.result = Result.success(json)
            fetcher.gate = null
            repository.checkManual()

            val replacementAccepted = repository.state.value.accepted!!
            assertEquals(accepted.generation + 1, replacementAccepted.generation)
            assertEquals(now, saved)
        }
    }

    @Test fun `manual failure from idle has no accepted authority`() = runTest {
        var saved = 0L
        val repository = UpdateRepository(
            2,
            FakeFetcher(json).apply {
                result = Result.failure(IllegalStateException("transport failed"))
            },
            { 7L },
            { saved },
            { saved = it },
            { true },
        )

        repository.checkManual()

        val failed = repository.state.value as UpdateState.Failed
        assertEquals("Could not check for updates. Try again.", failed.message)
        assertNull(failed.accepted)
        assertEquals(0L, saved)
    }

    @Test fun `cancellation propagates without failure timestamp or generation advance`() = runTest {
        var saved = 0L
        val fetcher = FakeFetcher(json)
        val repository = UpdateRepository(2, fetcher, { 7L }, { saved }, { saved = it }, { true })
        repository.checkManual()
        val accepted = repository.state.value as UpdateState.Available
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate

        val check = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
        check.cancel(CancellationException("cancel check"))
        try { check.await(); fail() } catch (_: CancellationException) {}

        assertEquals(7L, saved)
        assertEquals(accepted, repository.state.value)
        fetcher.gate = null
        repository.checkManual()
        assertEquals(accepted.accepted.generation + 1,
            (repository.state.value as UpdateState.Available).accepted.generation)
    }

    @Test fun `cancelled manual check leaves accepted state untouched before suspension`() = runTest {
        var clockCalls = 0
        var saved = 0L
        var saveCalls = 0
        val fetcher = FakeFetcher(json)
        val repository = UpdateRepository(2, fetcher, { clockCalls++; 7L }, { saved }, { saved = it; saveCalls++ }, { true })
        repository.checkManual()
        val accepted = repository.state.value as UpdateState.Available

        val check = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("cancel before check"))
            repository.checkManual()
        }
        try { check.await(); fail() } catch (_: CancellationException) {}

        assertEquals(1, fetcher.calls)
        assertEquals(1, clockCalls)
        assertEquals(1, saveCalls)
        assertEquals(7L, saved)
        assertEquals(accepted, repository.state.value)
        repository.checkManual()
        assertEquals(2, clockCalls)
        assertEquals(accepted.accepted.generation + 1,
            (repository.state.value as UpdateState.Available).accepted.generation)
    }

    @Test fun `automatic parse failure restores accepted state without timestamp while manual is visible`() = runTest {
        var now = 1L
        var saved = 0L
        val fetcher = FakeFetcher(json)
        val repository = UpdateRepository(2, fetcher, { now }, { saved }, { saved = it }, { true })
        repository.checkManual()
        val accepted = repository.state.value
        val acceptedTimestamp = saved
        fetcher.result = Result.success(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        val gate = CompletableDeferred<Unit>()
        fetcher.gate = gate
        now += 86_400_000L

        val automatic = async(start = CoroutineStart.UNDISPATCHED) { repository.checkAutomatic() }
        assertTrue(repository.state.value is UpdateState.Checking)
        gate.complete(Unit)
        automatic.await()
        assertEquals(accepted, repository.state.value)
        assertEquals(acceptedTimestamp, saved)
        fetcher.gate = null
        repository.checkManual()
        val failed = repository.state.value as UpdateState.Failed
        assertEquals("Could not check for updates. Try again.", failed.message)
        assertSame(accepted.accepted, failed.accepted)
    }

    @Test fun `automatic lifecycle is silent throttled and records accepted success`() = runTest {
        val interval = 86_400_000L
        val start = interval
        val completion = start + 37L
        var now = start
        var saved = 0L
        var online = false
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeFetcher(json, gate)
        val repository = UpdateRepository(2, fetcher, { now }, { saved }, { saved = it }, { online })
        repository.checkAutomatic()
        assertEquals(0, fetcher.calls)
        online = true
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.checkAutomatic() }
        assertEquals(1, fetcher.calls)
        now = completion
        gate.complete(Unit)
        first.await()
        assertEquals(completion, saved)
        assertTrue(repository.state.value is UpdateState.Available)
        fetcher.gate = null
        now = completion + interval - 1
        repository.checkAutomatic()
        assertEquals(1, fetcher.calls)
        now++
        repository.checkAutomatic()
        assertEquals(2, fetcher.calls)
        assertEquals(now, saved)
        fetcher.result = Result.failure(IllegalStateException("boom"))
        val acceptedTimestamp = saved
        now += interval
        repository.checkAutomatic()
        assertEquals(acceptedTimestamp, saved)
        assertTrue(repository.state.value is UpdateState.Available)
    }

    @Test fun `manual success records completion time in shared authority`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var now = 11L
        var saved = 0L
        val repository = UpdateRepository(2, FakeFetcher(json, gate), { now }, { saved }, { saved = it }, { true })

        val check = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
        now = 29L
        gate.complete(Unit)
        check.await()

        assertEquals(29L, saved)
        assertTrue(repository.state.value is UpdateState.Available)
    }

    @Test fun `automatic throttle handles sentinel boundaries rollback and extreme timestamps`() = runTest {
        data class Case(val now: Long, val last: Long, val expectedFetches: Int)
        val interval = 86_400_000L
        val cases = listOf(
            Case(1L, 0L, 1),
            Case(interval, 1L, 0),
            Case(interval + 1L, 1L, 1),
            Case(100L, 101L, 1),
            Case(Long.MAX_VALUE, Long.MIN_VALUE, 1),
            Case(Long.MIN_VALUE, Long.MAX_VALUE, 1),
        )

        cases.forEach { case ->
            var saved = case.last
            var saveCalls = 0
            val fetcher = FakeFetcher(json)
            val repository = UpdateRepository(2, fetcher, { case.now }, { case.last }, {
                saved = it
                saveCalls++
            }, { true })

            repository.checkManual() // same-process accepted state is required for throttling
            fetcher.calls = 0
            saveCalls = 0
            saved = case.last
            repository.checkAutomatic()

            assertEquals(case.toString(), case.expectedFetches, fetcher.calls)
            assertEquals(case.toString(), case.expectedFetches, saveCalls)
            assertEquals(case.toString(), if (case.expectedFetches == 1) case.now else case.last, saved)
            assertTrue(case.toString(), repository.state.value is UpdateState.Available)
        }
    }

    @Test fun `automatic eligibility is read only after winning the check lock`() = runTest {
        val firstEligibilityEntered = CountDownLatch(1)
        val releaseFirstEligibility = CountDownLatch(1)
        val eligibilityCalls = AtomicInteger()
        val fetchCalls = AtomicInteger()
        val saveCalls = AtomicInteger()
        val firstCompleted = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val repository = UpdateRepository(2, ManifestFetcher {
            fetchCalls.incrementAndGet()
            json
        }, { 1L }, {
            if (eligibilityCalls.incrementAndGet() == 1) {
                firstEligibilityEntered.countDown()
                check(releaseFirstEligibility.await(5, TimeUnit.SECONDS))
            }
            0L
        }, { saveCalls.incrementAndGet() }, { true })

        repository.checkManual()
        fetchCalls.set(0)
        saveCalls.set(0)
        val first = launch(Dispatchers.Default) { repository.checkAutomatic() }
        first.invokeOnCompletion { firstCompleted.countDown() }
        assertTrue(firstEligibilityEntered.await(5, TimeUnit.SECONDS))
        val second = launch(Dispatchers.Default) { repository.checkAutomatic() }
        second.invokeOnCompletion { secondCompleted.countDown() }
        assertTrue(secondCompleted.await(5, TimeUnit.SECONDS))
        releaseFirstEligibility.countDown()
        assertTrue(firstCompleted.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { first.join(); second.join() }

        assertEquals(1, eligibilityCalls.get())
        assertEquals(1, fetchCalls.get())
        assertEquals(1, saveCalls.get())
        assertTrue(repository.state.value is UpdateState.Available)
    }

    @Test fun `manual bypasses guards reports failure and concurrent calls drop`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fetcher = FakeFetcher(json, gate)
        val repository = UpdateRepository(3, fetcher, { 1L }, { 1L }, {}, { false })
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
        assertEquals(1, fetcher.calls)
        gate.complete(Unit)
        first.await(); second.await()
        assertEquals(1, fetcher.calls)
        assertTrue(repository.state.value is UpdateState.Current)
        val accepted = repository.state.value.accepted
        fetcher.result = Result.failure(IllegalStateException("secret details"))
        repository.checkManual()
        val failed = repository.state.value as UpdateState.Failed
        assertEquals("Could not check for updates. Try again.", failed.message)
        assertSame(accepted, failed.accepted)
    }

    @Test fun `https fetcher rejects redirects before reading body`() = runTest {
        val connection = FakeConnection().apply { code = 302 }
        val fetcher = HttpsManifestFetcher { connection }
        try { fetcher.fetch(); fail() } catch (_: IllegalStateException) {}
        assertEquals(UPDATE_ENDPOINT, connection.url.toString())
        assertEquals(false, connection.instanceFollowRedirects)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }

    @Test fun `https fetcher opens and reads away from caller thread`() = runTest {
        val caller = Thread.currentThread()
        val openedOn = AtomicReference<Thread>()
        val connection = FakeConnection(body = json.toByteArray()).apply {
            readOn = AtomicReference()
        }

        HttpsManifestFetcher {
            openedOn.set(Thread.currentThread())
            connection
        }.fetch()

        assertTrue(openedOn.get() !== caller)
        assertTrue(connection.readOn.get() !== caller)
    }

    @Test fun `https fetcher enforces bounded body and disconnects`() = runTest {
        val exact = FakeConnection(ByteArray(16 * 1024) { 'a'.code.toByte() })
        assertEquals(16 * 1024, HttpsManifestFetcher { exact }.fetch().length)
        assertTrue(exact.disconnected)

        val oversized = FakeConnection(ByteArray(16 * 1024 + 1) { 'a'.code.toByte() })
        try { HttpsManifestFetcher { oversized }.fetch(); fail() } catch (_: IllegalStateException) {}
        assertTrue(oversized.disconnected)
    }

    @Test fun `https fetcher rejects malformed UTF-8 and disconnects`() = runTest {
        val marker = "\"versionName\":\"0.2\""
        val malformed = json.substringBefore(marker).toByteArray() +
            "\"versionName\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) +
            "\"".toByteArray() + json.substringAfter(marker).toByteArray()
        val connection = FakeConnection(malformed)

        try { HttpsManifestFetcher { connection }.fetch(); fail() } catch (_: Exception) {}

        assertTrue(connection.disconnected)
    }

    private class FakeFetcher(var body: String, var gate: CompletableDeferred<Unit>? = null) : ManifestFetcher {
        var calls = 0
        var result: Result<String> = Result.success(body)
        override suspend fun fetch(): String { calls++; gate?.await(); return result.getOrThrow() }
    }

    private class FakeConnection(private val body: ByteArray = "{}".toByteArray()) : HttpsURLConnection(URL(UPDATE_ENDPOINT)) {
        var code = 200; var disconnected = false
        lateinit var readOn: AtomicReference<Thread>
        override fun getResponseCode() = code
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = ""
        override fun getLocalCertificates() = null
        override fun getServerCertificates() = null
        override fun getInputStream() = object : java.io.ByteArrayInputStream(body) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (::readOn.isInitialized) readOn.set(Thread.currentThread())
                return super.read(b, off, len)
            }
        }
    }
}
