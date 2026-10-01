package org.thanosapollo.nema.update

import android.app.Application
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `exact bytes publish a generation-bound private candidate only after validation`() = runTest {
        val bytes = "verified apk".toByteArray()
        val release = CompletableDeferred<Unit>()
        val effect = RecordingEffect(bytes, beforeReturn = release)
        val fixture = fixture(bytes, effect)
        fixture.repository.checkManual()

        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }

        assertTrue(fixture.repository.state.value is UpdateState.Downloading)
        assertFalse(fixture.directory.resolve(CANDIDATE_FILE_NAME).exists())
        release.complete(Unit)
        download.await()

        val state = fixture.repository.state.value as UpdateState.Downloaded
        assertEquals(state.accepted, state.artifact.accepted)
        assertEquals(bytes.size.toLong(), state.artifact.size)
        assertEquals(sha256(bytes), state.artifact.sha256)
        assertEquals(fixture.directory.resolve(CANDIDATE_FILE_NAME), state.artifact.file)
        assertArrayEquals(bytes, state.artifact.file.readBytes())
        assertFalse(fixture.directory.resolve(PART_FILE_NAME).exists())
    }

    @Test fun `invalid byte outcomes leave no partial or final artifact`() = runTest {
        val bytes = "expected bytes".toByteArray()
        val outcomes = listOf(
            bytes.dropLast(1).toByteArray(),
            bytes + 0,
            "XXXXXXXXXXXXXX".toByteArray(),
        )

        outcomes.forEach { delivered ->
            val fixture = fixture(bytes, RecordingEffect(delivered))
            fixture.repository.checkManual()
            val accepted = fixture.repository.state.value as UpdateState.Available

            fixture.repository.download()

            assertEquals(accepted.accepted, fixture.repository.state.value.accepted)
            assertTrue(fixture.repository.state.value is UpdateState.Failed)
            assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun `manifest above absolute cap is rejected without invoking transport`() = runTest {
        val calls = AtomicInteger()
        val fixture = fixture(ByteArray(1), ApkDownloadEffect { _, _ -> calls.incrementAndGet() }, size = MAX_APK_BYTES + 1)
        fixture.repository.checkManual()
        val accepted = fixture.repository.state.value

        fixture.repository.download()

        assertSame(accepted.accepted, fixture.repository.state.value.accepted)
        assertTrue(fixture.repository.state.value is UpdateState.Failed)
        assertEquals(0, calls.get())
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `cancellation restores available authority and removes every artifact`() = runTest {
        val bytes = "cancel me".toByteArray()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val effect = ApkDownloadEffect { _, part ->
            part.writeBytes(bytes.copyOfRange(0, 3))
            entered.complete(Unit)
            release.await()
        }
        val fixture = fixture(bytes, effect)
        fixture.repository.checkManual()
        val accepted = fixture.repository.state.value as UpdateState.Available
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }
        entered.await()

        download.cancel(CancellationException("stop"))
        try { download.await(); fail() } catch (_: CancellationException) {}

        assertEquals(accepted, fixture.repository.state.value)
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `cancellation after candidate move preserves exact newer failed presentation`() = runTest {
        val bytes = "settlement fence".toByteArray()
        val effectEntered = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val moved = CompletableDeferred<Unit>()
        val directory = temporary.newFolder("cancel-after-move")
        val fetcher = FakeFetcher(manifestJson(manifest(bytes)))
        val marker = ThreadLocal<Boolean>()
        val dispatcher = RecordingDispatcher(marker, AtomicInteger()) {
            if (directory.resolve(CANDIDATE_FILE_NAME).exists() &&
                !directory.resolve(PART_FILE_NAME).exists()) {
                moved.complete(Unit)
            }
        }
        try {
            val repository = repositoryWithSeams(directory, fetcher, ApkDownloadEffect { _, part ->
                part.writeBytes(bytes)
                effectEntered.complete(Unit)
                releaseEffect.await()
            }, dispatcher = dispatcher)
            repository.checkManual()
            fetcher.result = Result.failure(IllegalStateException("offline"))
            repository.checkManual()
            val prior = repository.state.value as UpdateState.Failed
            val checkEntered = CompletableDeferred<Unit>()
            val releaseCheck = CompletableDeferred<Unit>()
            fetcher.result = Result.failure(IllegalStateException("still offline"))
            fetcher.beforeReturn = checkEntered
            fetcher.release = releaseCheck
            val download = async(start = CoroutineStart.UNDISPATCHED) { repository.download() }
            effectEntered.await()
            val check = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
            checkEntered.await()
            releaseEffect.complete(Unit)
            moved.await()

            assertTrue(directory.resolve(CANDIDATE_FILE_NAME).exists())
            val cancellation = CancellationException("cancel before settlement")
            download.cancel(cancellation)
            releaseCheck.complete(Unit)
            check.await()
            val newer = repository.state.value as UpdateState.Failed
            val observed = try {
                download.await()
                throw AssertionError("download completed despite cancellation")
            } catch (failure: CancellationException) {
                failure
            }

            assertFalse(newer === prior)
            assertSame(newer, repository.state.value)
            assertEquals(cancellation.message, observed.message)
            assertNull(repository.boundArtifact())
            assertFalse(directory.resolve(PART_FILE_NAME).exists())
            assertFalse(directory.resolve(CANDIDATE_FILE_NAME).exists())
        } finally {
            dispatcher.close()
        }
    }

    @Test fun `download failure reports safe retry feedback with the same accepted authority`() = runTest {
        val bytes = "restore failure".toByteArray()
        val fixture = fixture(bytes, ApkDownloadEffect { _, _ -> error("download failed") })
        fixture.repository.checkManual()
        val prior = failPresentation(fixture)

        fixture.repository.download()

        val failed = fixture.repository.state.value as UpdateState.Failed
        assertSame(prior.accepted, failed.accepted)
        assertEquals("Could not download update. Try again.", failed.message)
        assertNull(fixture.repository.boundArtifact())
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `download cancellation during effect restores exact failed presentation`() = runTest {
        val bytes = "restore cancellation".toByteArray()
        val entered = CompletableDeferred<Unit>()
        val fixture = fixture(bytes, ApkDownloadEffect { _, part ->
            part.writeBytes(bytes)
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        })
        fixture.repository.checkManual()
        val prior = failPresentation(fixture)
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }
        entered.await()

        download.cancel(CancellationException("cancel effect"))
        try { download.await(); fail() } catch (_: CancellationException) {}

        assertSame(prior, fixture.repository.state.value)
        assertNull(fixture.repository.boundArtifact())
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `failed settlement preserves newer failed presentation with same authority`() = runTest {
        val bytes = "same authority".toByteArray()
        val releaseDownload = CompletableDeferred<Unit>()
        val fixture = fixture(bytes, RecordingEffect(bytes.dropLast(1).toByteArray(), releaseDownload))
        fixture.repository.checkManual()
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }
        fixture.fetcher.result = Result.failure(IllegalStateException("new failure"))

        fixture.repository.checkManual()
        val newer = fixture.repository.state.value as UpdateState.Failed
        releaseDownload.complete(Unit)
        download.await()

        assertSame(newer, fixture.repository.state.value)
        assertNull(fixture.repository.boundArtifact())
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `only one download is in flight`() = runTest {
        val bytes = "one flight".toByteArray()
        val release = CompletableDeferred<Unit>()
        val effect = RecordingEffect(bytes, beforeReturn = release)
        val fixture = fixture(bytes, effect)
        fixture.repository.checkManual()

        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }
        fixture.repository.download()
        assertEquals(1, effect.calls)
        release.complete(Unit)
        first.await()
        fixture.repository.download()
        assertEquals(1, effect.calls)
    }

    @Test fun `current manifest cannot start a download`() = runTest {
        val bytes = "already current".toByteArray()
        val effect = RecordingEffect(bytes)
        val fixture = fixture(bytes, effect)
        val repository = UpdateRepository(3, fixture.fetcher, { 1L }, { 0L }, {}, { true }, fixture.directory, effect)
        repository.checkManual()

        repository.download()

        assertTrue(repository.state.value is UpdateState.Current)
        assertEquals(0, effect.calls)
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `unwired download fails closed and later manifest checks still work`() = runTest {
        val bytes = "unwired".toByteArray()
        val fetcher = FakeFetcher(manifestJson(manifest(bytes)))
        val repository = UpdateRepository(2, fetcher, { 1L }, { 0L }, {}, { true })
        repository.checkManual()
        val accepted = repository.state.value as UpdateState.Available

        repository.download()

        assertEquals(accepted, repository.state.value)
        assertEquals(1, fetcher.calls)

        fetcher.result = Result.success(manifestJson(manifest(bytes).copy(versionCode = 4, versionName = "0.3")))
        repository.checkManual()

        val replacement = repository.state.value as UpdateState.Available
        assertEquals(4L, (replacement.accepted.value as AcceptedUpdate.Available).manifest.versionCode)
        assertEquals(2, fetcher.calls)
    }

    @Test fun `failed presentation downloads from preserved accepted authority`() = runTest {
        val bytes = "preserved authority".toByteArray()
        val effect = RecordingEffect(bytes)
        val fixture = fixture(bytes, effect)
        fixture.repository.checkManual()
        val accepted = (fixture.repository.state.value as UpdateState.Available).accepted
        fixture.fetcher.result = Result.failure(IllegalStateException("offline"))
        fixture.repository.checkManual()

        val failed = fixture.repository.state.value as UpdateState.Failed
        assertEquals(accepted, failed.accepted)

        fixture.repository.download()

        val downloaded = fixture.repository.state.value as UpdateState.Downloaded
        assertEquals(accepted, downloaded.accepted)
        assertEquals(accepted, downloaded.artifact.accepted)
        assertEquals(accepted.generation, downloaded.accepted.generation)
        assertEquals(accepted.value, downloaded.accepted.value)
        assertEquals(1, effect.calls)
    }

    @Test fun `transient check failure retains downloaded candidate`() = runTest {
        val bytes = "retained".toByteArray()
        val fixture = fixture(bytes, RecordingEffect(bytes))
        fixture.repository.checkManual()
        fixture.repository.download()
        val downloaded = fixture.repository.state.value as UpdateState.Downloaded
        fixture.fetcher.result = Result.failure(IllegalStateException("offline"))

        fixture.repository.checkManual()

        val failed = fixture.repository.state.value as UpdateState.Failed
        assertEquals(downloaded.accepted, failed.accepted)
        assertTrue(downloaded.artifact.file.exists())
        assertEquals(downloaded.artifact, fixture.repository.boundArtifact())
    }

    @Test fun `every accepted manifest response revokes prior candidate`() = runTest {
        val bytes = "revoked".toByteArray()
        val fixture = fixture(bytes, RecordingEffect(bytes))
        fixture.repository.checkManual()
        fixture.repository.download()
        val old = (fixture.repository.state.value as UpdateState.Downloaded).artifact
        val replacements = listOf(
            fixture.json.replace("\"versionCode\":3", "\"versionCode\":2"),
            fixture.json.replace("\"versionName\":\"0.2\"", "\"versionName\":\"changed\""),
            fixture.json,
        )

        replacements.forEach { replacement ->
            fixture.fetcher.result = Result.success(replacement)
            fixture.repository.checkManual()
            assertNull(fixture.repository.boundArtifact())
            assertFalse(old.file.exists())
            fixture.fetcher.result = Result.success(fixture.json)
            fixture.repository.checkManual()
            fixture.repository.download()
            assertTrue(fixture.repository.boundArtifact()!!.file.exists())
        }
    }

    @Test fun `new accepted generation overtakes download before settlement`() = runTest {
        val bytes = "generation a".toByteArray()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(bytes, RecordingEffect(bytes, beforeReturn = release))
        fixture.repository.checkManual()
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }
        fixture.fetcher.result = Result.success(fixture.json)

        fixture.repository.checkManual()
        val replacement = fixture.repository.state.value as UpdateState.Available
        release.complete(Unit)
        download.await()

        assertEquals(replacement, fixture.repository.state.value)
        assertNull(fixture.repository.boundArtifact())
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `process recreation prunes stale partial and candidate files`() {
        val directory = temporary.newFolder("recreated")
        directory.resolve(PART_FILE_NAME).writeText("partial")
        directory.resolve(CANDIDATE_FILE_NAME).writeText("candidate")
        directory.resolve("unrelated").writeText("leave")

        repository(directory, FakeFetcher("{}"), ApkDownloadEffect { _, _ -> })

        assertFalse(directory.resolve(PART_FILE_NAME).exists())
        assertFalse(directory.resolve(CANDIDATE_FILE_NAME).exists())
        assertTrue(directory.resolve("unrelated").exists())
    }

    @Test fun `constructor fails closed when stale named files cannot be deleted`() {
        val directory = temporary.newFolder("refused-prune")
        val stale = directory.resolve(CANDIDATE_FILE_NAME).apply { writeText("stale") }

        try {
            repositoryWithSeams(directory, FakeFetcher("{}"), ApkDownloadEffect { _, _ -> },
                delete = { false })
            fail("repository claimed a clean start")
        } catch (_: IllegalStateException) {}

        assertTrue(stale.exists())
    }

    @Test fun `lying successful delete is rejected by its file existence postcondition`() {
        val directory = temporary.newFolder("lying-prune")
        val stale = directory.resolve(PART_FILE_NAME).apply { writeText("stale") }

        try {
            repositoryWithSeams(directory, FakeFetcher("{}"), ApkDownloadEffect { _, _ -> },
                delete = { true })
            fail("delete return value bypassed the postcondition")
        } catch (_: IllegalStateException) {}

        assertTrue(stale.exists())
    }

    @Test fun `accepted manifest cannot revoke authority when candidate deletion is refused`() = runTest {
        val bytes = "old candidate".toByteArray()
        var saved = 0L
        var refuseDelete = false
        val directory = temporary.newFolder("refused-revoke")
        val manifest = manifest(bytes)
        val fetcher = FakeFetcher(manifestJson(manifest))
        val repository = repositoryWithSeams(directory, fetcher, RecordingEffect(bytes),
            saveSuccess = { saved = it }, delete = { file -> if (refuseDelete) false else file.delete() })
        repository.checkManual()
        repository.download()
        val prior = repository.state.value as UpdateState.Downloaded
        val priorTimestamp = saved
        refuseDelete = true
        fetcher.result = Result.success(manifestJson(manifest.copy(versionName = "replacement")))

        repository.checkManual()

        val failed = repository.state.value as UpdateState.Failed
        assertEquals(prior.accepted, failed.accepted)
        assertEquals(prior.artifact, repository.boundArtifact())
        assertTrue(prior.artifact.file.exists())
        assertEquals(priorTimestamp, saved)
        assertEquals(prior.accepted.generation, failed.accepted!!.generation)
    }

    @Test fun `throwing completion clock preserves downloaded authority and candidate`() = runTest {
        val bytes = "clock-bound candidate".toByteArray()
        val directory = temporary.newFolder("throwing-completion-clock")
        val original = manifest(bytes)
        val replacement = original.copy(versionCode = 4, versionName = "clock replacement")
        val fetcher = FakeFetcher(manifestJson(original))
        val clockFailure = IllegalStateException("completion clock failed")
        var clockCalls = 0
        val saved = mutableListOf<Long>()
        val repository = UpdateRepository(
            2, fetcher,
            { if (++clockCalls == 1) 101L else throw clockFailure },
            { 0L }, { saved += it }, { true }, directory, RecordingEffect(bytes),
        )
        repository.checkManual()
        repository.download()
        val prior = repository.state.value as UpdateState.Downloaded
        val oldArtifact = prior.artifact
        val oldPath = oldArtifact.file
        val oldBytes = oldPath.readBytes()
        val priorSaved = saved.toList()
        fetcher.result = Result.success(manifestJson(replacement))

        val result = runCatching { repository.checkManual() }

        assertNull(result.exceptionOrNull())
        val failed = repository.state.value as UpdateState.Failed
        assertEquals("Could not check for updates. Try again.", failed.message)
        assertSame(prior.accepted, failed.accepted)
        assertEquals(prior.accepted.generation, failed.accepted!!.generation)
        assertEquals(priorSaved, saved)
        assertEquals(oldArtifact, repository.boundArtifact())
        assertEquals(oldPath, repository.boundArtifact()!!.file)
        assertTrue(oldPath.exists())
        assertArrayEquals(oldBytes, oldPath.readBytes())
    }

    @Test fun `throwing save publishes replacement after checked revocation`() = runTest {
        val bytes = "save-bound candidate".toByteArray()
        val directory = temporary.newFolder("throwing-save-success")
        val original = manifest(bytes)
        val replacement = original.copy(versionCode = 4, versionName = "save replacement")
        val later = original.copy(versionCode = 5, versionName = "later replacement")
        val fetcher = FakeFetcher(manifestJson(original))
        var now = 201L
        var saveCalls = 0
        var throwOnSave = false
        val repository = UpdateRepository(
            2, fetcher, { now }, { 0L }, {
                saveCalls++
                if (throwOnSave) throw IllegalStateException("save success failed")
            }, { true }, directory, RecordingEffect(bytes),
        )
        repository.checkManual()
        repository.download()
        val prior = repository.state.value as UpdateState.Downloaded
        val oldArtifact = prior.artifact
        throwOnSave = true
        now = 202L
        fetcher.result = Result.success(manifestJson(replacement))

        repository.checkManual()

        val acceptedReplacement = repository.state.value as UpdateState.Available
        assertEquals(replacement, (acceptedReplacement.accepted.value as AcceptedUpdate.Available).manifest)
        assertEquals(prior.accepted.generation + 1, acceptedReplacement.accepted.generation)
        assertNull(repository.boundArtifact())
        assertFalse(oldArtifact.file.exists())
        assertEquals(2, saveCalls)

        throwOnSave = false
        now = 203L
        fetcher.result = Result.success(manifestJson(later))
        repository.checkManual()

        val acceptedLater = repository.state.value as UpdateState.Available
        assertEquals(later, (acceptedLater.accepted.value as AcceptedUpdate.Available).manifest)
        assertEquals(acceptedReplacement.accepted.generation + 1, acceptedLater.accepted.generation)
    }

    @Test fun `cleanup refusal retry stays failed without escaping or invoking transport`() = runTest {
        val bytes = "bad cleanup".toByteArray()
        var refuseDelete = false
        val directory = temporary.newFolder("refused-cleanup")
        val manifest = manifest(bytes)
        val fetcher = FakeFetcher(manifestJson(manifest))
        val effect = RecordingEffect(bytes.dropLast(1).toByteArray())
        val repository = repositoryWithSeams(directory, fetcher, effect,
            delete = { file -> if (refuseDelete) false else file.delete() })
        repository.checkManual()
        val accepted = repository.state.value.accepted
        refuseDelete = true

        repository.download()
        val firstFailure = repository.state.value as UpdateState.Failed
        assertSame(accepted, firstFailure.accepted)
        assertEquals(1, effect.calls)

        repository.download()

        val retryFailure = repository.state.value as UpdateState.Failed
        assertEquals("Could not download update. Try again.", retryFailure.message)
        assertSame(accepted, retryFailure.accepted)
        assertEquals(1, effect.calls)
        assertNull(repository.boundArtifact())
        assertTrue(directory.resolve(PART_FILE_NAME).exists())
    }

    @Test fun `retry preflight propagates cancellation and errors without invoking transport`() = runTest {
        listOf<Throwable>(CancellationException("stop"), AssertionError("fatal")).forEachIndexed { index, failure ->
            val bytes = "preflight $index".toByteArray()
            var deleteFailure: Throwable? = null
            val directory = temporary.newFolder("preflight-$index")
            val fetcher = FakeFetcher(manifestJson(manifest(bytes)))
            val effect = RecordingEffect(bytes.dropLast(1).toByteArray())
            val repository = repositoryWithSeams(directory, fetcher, effect, delete = {
                deleteFailure?.let { throw it }
                false
            })
            repository.checkManual()
            repository.download()
            val failed = repository.state.value as UpdateState.Failed
            deleteFailure = failure

            var observed: Throwable? = null
            try { repository.download() } catch (caught: Throwable) { observed = caught }

            assertSame(failure, observed)
            assertSame(failed, repository.state.value)
            assertEquals(1, effect.calls)
        }
    }

    @Test fun `cancelled cleanup refusal propagates cancellation and exposes failure`() = runTest {
        val bytes = "cancel cleanup".toByteArray()
        val entered = CompletableDeferred<Unit>()
        var refuseDelete = false
        val directory = temporary.newFolder("refused-cancel-cleanup")
        val fetcher = FakeFetcher(manifestJson(manifest(bytes)))
        val repository = repositoryWithSeams(directory, fetcher, ApkDownloadEffect { _, part ->
            part.writeBytes(bytes)
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }, delete = { file -> if (refuseDelete) false else file.delete() })
        repository.checkManual()
        val accepted = repository.state.value.accepted
        refuseDelete = true
        val download = async(start = CoroutineStart.UNDISPATCHED) { repository.download() }
        entered.await()

        download.cancel(CancellationException("cancel with refused cleanup"))
        try { download.await(); fail() } catch (_: CancellationException) {}

        val failed = repository.state.value as UpdateState.Failed
        assertEquals(accepted, failed.accepted)
        assertNull(repository.boundArtifact())
        assertTrue(directory.resolve(PART_FILE_NAME).exists())
    }

    @Test fun `second pass file work uses blocking dispatcher and publication does not`() = runTest {
        val bytes = "blocking dispatcher".toByteArray()
        val effectEntered = CompletableDeferred<Unit>()
        val releaseEffect = CompletableDeferred<Unit>()
        val checkEntered = CompletableDeferred<Unit>()
        val releaseCheck = CompletableDeferred<Unit>()
        val moved = CompletableDeferred<Unit>()
        val marker = ThreadLocal<Boolean>()
        val dispatches = AtomicInteger()
        val directory = temporary.newFolder("dispatcher")
        val movedOnDispatcher = AtomicBoolean()
        val stateWhenMoved = AtomicReference<UpdateState>()
        lateinit var repository: UpdateRepository
        val dispatcher = RecordingDispatcher(marker, dispatches) {
            if (directory.resolve(CANDIDATE_FILE_NAME).exists() &&
                !directory.resolve(PART_FILE_NAME).exists()) {
                movedOnDispatcher.set(marker.get() == true)
                stateWhenMoved.set(repository.state.value)
                moved.complete(Unit)
            }
        }
        try {
            val fetcher = FakeFetcher(manifestJson(manifest(bytes)))
            val effect = ApkDownloadEffect { _, part ->
                part.writeBytes(bytes)
                effectEntered.complete(Unit)
                releaseEffect.await()
            }
            repository = repositoryWithSeams(directory, fetcher, effect, dispatcher = dispatcher)
            repository.checkManual()
            val accepted = repository.state.value.accepted
            val download = async(start = CoroutineStart.UNDISPATCHED) { repository.download() }
            effectEntered.await()
            fetcher.result = Result.failure(IllegalStateException("offline"))
            fetcher.beforeReturn = checkEntered
            fetcher.release = releaseCheck
            val check = async(start = CoroutineStart.UNDISPATCHED) { repository.checkManual() }
            checkEntered.await()
            val checking = repository.state.value as UpdateState.Checking

            assertSame(accepted, checking.accepted)
            releaseEffect.complete(Unit)
            moved.await()

            assertTrue(dispatches.get() > 0)
            assertTrue(movedOnDispatcher.get())
            assertSame(checking, stateWhenMoved.get())
            assertTrue(dispatcher.thread.get() !== Thread.currentThread())
            assertSame(checking, repository.state.value)

            releaseCheck.complete(Unit)
            check.await()
            download.await()
            val downloaded = repository.state.value as UpdateState.Downloaded
            assertSame(accepted, downloaded.accepted)
        } finally {
            releaseEffect.complete(Unit)
            releaseCheck.complete(Unit)
            dispatcher.close()
        }
    }

    @Test fun `binding compares the complete manifest identity`() = runTest {
        val bytes = "identity".toByteArray()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(bytes, RecordingEffect(bytes, beforeReturn = release))
        fixture.repository.checkManual()
        val download = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.download() }
        fixture.fetcher.result = Result.success(fixture.json.replace("\"signerSha256\":\"${"b".repeat(64)}\"", "\"signerSha256\":\"${"e".repeat(64)}\""))
        fixture.repository.checkManual()
        release.complete(Unit)
        download.await()

        assertNull(fixture.repository.boundArtifact())
        assertTrue(fixture.directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `https transport rejects redirect and wrong connection URL and always disconnects`() = runTest {
        listOf(
            FakeApkConnection("https://git.thanosapollo.org/nema/releases/v/app.apk", byteArrayOf()).apply { code = 302 },
            FakeApkConnection("https://git.thanosapollo.org/nema/releases/other.apk", byteArrayOf()),
        ).forEach { connection ->
            val directory = temporary.newFolder()
            val manifest = manifest("x".toByteArray())
            try {
                HttpsApkDownloadEffect { connection }.download(manifest, directory.resolve(PART_FILE_NAME))
                fail()
            } catch (_: Exception) {}
            assertTrue(connection.disconnected)
            assertEquals(false, connection.instanceFollowRedirects)
            assertEquals(10_000, connection.connectTimeout)
            assertEquals(10_000, connection.readTimeout)
        }
    }

    @Test fun `https transport streams exact bytes with validation and syncs before return`() = runTest {
        val bytes = "transport bytes".toByteArray()
        val manifest = manifest(bytes)
        val connection = FakeApkConnection(manifest.apkUrl, bytes)
        val part = temporary.newFolder().resolve(PART_FILE_NAME)

        HttpsApkDownloadEffect { connection }.download(manifest, part)

        assertArrayEquals(bytes, part.readBytes())
        assertTrue(connection.disconnected)
    }

    private fun fixture(bytes: ByteArray, effect: ApkDownloadEffect, size: Long = bytes.size.toLong()): Fixture {
        val directory = temporary.newFolder()
        val manifest = manifest(bytes, size)
        val json = manifestJson(manifest)
        val fetcher = FakeFetcher(json)
        return Fixture(directory, json, fetcher, repository(directory, fetcher, effect))
    }

    private fun repository(directory: File, fetcher: FakeFetcher, effect: ApkDownloadEffect) =
        UpdateRepository(2, fetcher, { 1L }, { 0L }, {}, { true }, directory, effect)

    private fun repositoryWithSeams(
        directory: File,
        fetcher: FakeFetcher,
        effect: ApkDownloadEffect,
        saveSuccess: (Long) -> Unit = {},
        delete: (File) -> Boolean = { it.delete() },
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): UpdateRepository {
        val constructor = UpdateRepository::class.java.declaredConstructors.singleOrNull {
            it.parameterTypes.size == 10 &&
                it.parameterTypes[8].name == "kotlin.jvm.functions.Function1" &&
                CoroutineDispatcher::class.java.isAssignableFrom(it.parameterTypes[9])
        } ?: throw AssertionError("UpdateRepository must inject checked delete and blocking dispatcher")
        return try {
            constructor.newInstance(
                2L, fetcher, { 1L }, { 0L }, saveSuccess, { true }, directory, effect, delete, dispatcher,
            ) as UpdateRepository
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }

    private suspend fun failPresentation(fixture: Fixture): UpdateState.Failed {
        fixture.fetcher.result = Result.failure(IllegalStateException("offline"))
        fixture.repository.checkManual()
        return fixture.repository.state.value as UpdateState.Failed
    }

    private fun manifest(bytes: ByteArray, size: Long = bytes.size.toLong()) = UpdateManifest(
        1, "org.thanosapollo.nema", 3, "0.2",
        "https://git.thanosapollo.org/nema/releases/v/app.apk", size, sha256(bytes),
        "b".repeat(64), "c".repeat(40),
    )

    private fun manifestJson(value: UpdateManifest) =
        """{"schemaVersion":1,"packageName":"${value.packageName}","versionCode":${value.versionCode},"versionName":"${value.versionName}","apkUrl":"${value.apkUrl}","size":${value.size},"sha256":"${value.sha256}","signerSha256":"${value.signerSha256}","sourceCommit":"${value.sourceCommit}"}"""

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val directory: File,
        val json: String,
        val fetcher: FakeFetcher,
        val repository: UpdateRepository,
    )

    private class FakeFetcher(var body: String) : ManifestFetcher {
        var result: Result<String> = Result.success(body)
        var calls = 0
        var beforeReturn: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        override suspend fun fetch(): String {
            calls++
            beforeReturn?.complete(Unit)
            release?.await()
            return result.getOrThrow()
        }
    }

    private class RecordingDispatcher(
        private val marker: ThreadLocal<Boolean>,
        private val dispatches: AtomicInteger,
        private val afterRun: () -> Unit = {},
    ) : CoroutineDispatcher(), Closeable {
        private val delegate = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "update-download-test-dispatcher")
        }.asCoroutineDispatcher()
        val thread = AtomicReference<Thread>()

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, Runnable {
                thread.set(Thread.currentThread())
                marker.set(true)
                try { block.run() } finally {
                    afterRun()
                    marker.remove()
                }
            })
        }

        override fun close() = delegate.close()
    }

    private class RecordingEffect(
        private val bytes: ByteArray,
        private val beforeReturn: CompletableDeferred<Unit>? = null,
    ) : ApkDownloadEffect {
        var calls = 0
        override suspend fun download(manifest: UpdateManifest, part: File) {
            calls++
            part.writeBytes(bytes)
            beforeReturn?.await()
        }
    }

    private class FakeApkConnection(url: String, private val body: ByteArray) : HttpsURLConnection(URL(url)) {
        var code = 200
        var disconnected = false
        override fun getResponseCode() = code
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = ""
        override fun getLocalCertificates() = null
        override fun getServerCertificates() = null
        override fun getInputStream() = body.inputStream()
    }
}
