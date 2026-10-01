package org.thanosapollo.nema.update

import android.app.Application
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
class UpdateAuthorizationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signer = "12".repeat(32)

    @Test fun `exact package and file facts publish verified authority while bad facts reject`() = runTest {
        val good = fixture()
        good.prepare()
        good.repository.verify()
        val verified = good.repository.state.value as UpdateState.Verified
        assertEquals(good.installed, verified.authority.installed)
        assertEquals(good.candidate, verified.authority.candidate)
        val candidate = requireNotNull(good.candidate)

        listOf(
            candidate.copy(packageName = "wrong"),
            candidate.copy(versionCode = 4),
            candidate.copy(versionCode = 2),
            candidate.copy(currentSigners = setOf("34".repeat(32)), signingHistory = setOf("34".repeat(32))),
        ).forEach { bad ->
            val fixture = fixture(candidate = bad)
            fixture.prepare()
            fixture.repository.verify()
            assertFalse(fixture.repository.state.value is UpdateState.Verified)
            assertNull(fixture.repository.boundArtifact())
        }
    }

    @Test fun `changed bytes and package parse failure reject candidate`() = runTest {
        val changed = fixture()
        changed.prepare()
        changed.directory.resolve(CANDIDATE_FILE_NAME).appendText("changed")
        changed.repository.verify()
        assertNull(changed.repository.boundArtifact())

        val missing = fixture(candidate = null)
        missing.prepare()
        missing.repository.verify()
        assertNull(missing.repository.boundArtifact())
    }

    @Test fun `verification recovers exact downloaded authority from transient failed presentation`() = runTest {
        val fixture = fixture()
        fixture.prepare()
        val downloaded = fixture.repository.state.value as UpdateState.Downloaded
        fixture.fetcher.failure = IllegalStateException("private")

        fixture.repository.checkManual()

        val failed = fixture.repository.state.value as UpdateState.Failed
        assertSame(downloaded.accepted, failed.accepted)
        assertSame(downloaded.artifact, fixture.repository.boundArtifact())
        fixture.repository.verify()

        val verified = fixture.repository.state.value as UpdateState.Verified
        assertSame(downloaded.accepted, verified.accepted)
        assertSame(downloaded.artifact, verified.authority.artifact)
        assertSame(downloaded.artifact, fixture.repository.boundArtifact())
        assertTrue(downloaded.artifact.file.exists())
    }

    @Test fun `transient failure overtakes blocking verification without revoking exact authority`() = runTest {
        val fixture = fixture(dispatcher = Dispatchers.IO)
        fixture.prepare()
        val downloaded = fixture.repository.state.value as UpdateState.Downloaded
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.adapter.beforeInstalled = { entered.countDown(); release.await() }
        val verification = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.verify() }
        assertTrue(kotlinx.coroutines.withContext(Dispatchers.IO) {
            entered.await(5, TimeUnit.SECONDS)
        })

        fixture.fetcher.failure = IllegalStateException("private")
        fixture.repository.checkManual()
        try {
            val failed = fixture.repository.state.value as UpdateState.Failed
            assertSame(downloaded.accepted, failed.accepted)
            assertSame(downloaded.artifact, fixture.repository.boundArtifact())
            release.countDown()
            verification.await()
        } finally {
            release.countDown()
        }

        val verified = fixture.repository.state.value as UpdateState.Verified
        assertSame(downloaded.accepted, verified.accepted)
        assertSame(downloaded.artifact, verified.authority.artifact)
        assertSame(downloaded.artifact, fixture.repository.boundArtifact())
        assertTrue(downloaded.artifact.file.exists())
    }

    @Test fun `accepted manifest overtakes blocking verification without stale publication`() = runTest {
        val fixture = fixture(dispatcher = Dispatchers.IO)
        fixture.prepare()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.adapter.beforeInstalled = { entered.countDown(); release.await() }
        val verification = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.verify() }
        assertTrue(kotlinx.coroutines.withContext(Dispatchers.IO) {
            entered.await(5, TimeUnit.SECONDS)
        })

        fixture.repository.checkManual()
        release.countDown()
        verification.await()

        assertFalse(fixture.repository.state.value is UpdateState.Verified)
        assertNull(fixture.repository.boundArtifact())
    }

    @Test fun `accepted manifest revokes verified while transient failure preserves its authority`() = runTest {
        val fixture = fixture()
        fixture.prepareVerified()
        fixture.fetcher.failure = IllegalStateException("private")
        fixture.repository.checkManual()
        assertFalse(fixture.repository.install())
        assertEquals(1, fixture.launches)

        val revoked = fixture()
        revoked.prepareVerified()
        revoked.repository.checkManual()
        assertNull(revoked.repository.boundArtifact())
        assertFalse(revoked.repository.install())
        assertEquals(0, revoked.launches)
    }

    @Test fun `permission continuation cannot install a successor verified artifact`() = runTest {
        val fixture = fixture(launcher = { _, _ -> true })
        fixture.prepareVerified()
        val old = (fixture.repository.state.value as UpdateState.Verified).authority
        fixture.repository.checkManual()
        fixture.repository.download()
        fixture.repository.verify()
        val successor = fixture.repository.state.value as UpdateState.Verified
        assertFalse(fixture.repository.install(old))
        assertSame(successor, fixture.repository.state.value)
        assertEquals(0, fixture.launches)
        assertTrue(fixture.repository.install(successor.authority))
        assertEquals(1, fixture.launches)
    }

    @Test fun `final install revalidation blocks changed file installed or archive facts`() = runTest {
        suspend fun rejected(mutate: Fixture.() -> Unit) {
            val fixture = fixture()
            fixture.prepareVerified()
            fixture.mutate()
            assertFalse(fixture.repository.install())
            assertEquals(0, fixture.launches)
        }
        rejected { directory.resolve(CANDIDATE_FILE_NAME).appendText("x") }
        rejected { adapter.installed = installed.copy(versionCode = 3) }
        rejected { adapter.installed = installed.copy(currentSigners = setOf("34".repeat(32))) }
        rejected { adapter.installed = installed.copy(signingHistory = setOf("34".repeat(32))) }
        rejected { adapter.candidate = candidate!!.copy(versionCode = 4) }
        rejected { adapter.candidate = candidate!!.copy(currentSigners = setOf("34".repeat(32))) }
        rejected { adapter.candidate = candidate!!.copy(signingHistory = setOf("34".repeat(32))) }
    }

    @Test fun `lease is visible before launch and false or exception restores exact verified`() = runTest {
        listOf<(Fixture) -> Boolean>(
            { false },
            { throw IllegalStateException("private") },
        ).forEach { outcome ->
            lateinit var fixture: Fixture
            fixture = fixture(launcher = { _, _ ->
                assertTrue(fixture.repository.state.value is UpdateState.Installing)
                outcome(fixture)
            })
            fixture.prepareVerified()
            val prior = fixture.repository.state.value
            assertFalse(fixture.repository.install())
            assertSame(prior, fixture.repository.state.value)
        }
    }

    @Test fun `launcher cancellation restores exact verified and propagates`() = runTest {
        val fixture = fixture(launcher = { _, _ -> throw CancellationException("stop") })
        fixture.prepareVerified()
        val prior = fixture.repository.state.value
        try { fixture.repository.install(); fail() } catch (_: CancellationException) {}
        assertSame(prior, fixture.repository.state.value)
    }

    @Test fun `cancellation during final install revalidation never publishes lease or launches`() = runTest {
        val fixture = fixture(dispatcher = Dispatchers.IO)
        fixture.prepareVerified()
        val prior = fixture.repository.state.value
        val candidate = fixture.repository.boundArtifact()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.adapter.beforeInstalled = { entered.countDown(); release.await() }

        val install = async { fixture.repository.install() }
        assertTrue(kotlinx.coroutines.withContext(Dispatchers.IO) {
            entered.await(5, TimeUnit.SECONDS)
        })
        install.cancel()
        release.countDown()

        try { install.await(); fail() } catch (_: CancellationException) {}
        assertEquals(0, fixture.launches)
        assertSame(prior, fixture.repository.state.value)
        assertSame(candidate, fixture.repository.boundArtifact())
        assertTrue(fixture.directory.resolve(CANDIDATE_FILE_NAME).exists())
        assertFalse(fixture.repository.state.value is UpdateState.Installing)
    }

    @Test fun `adapter cancellation during verify restores retry and removes downloaded authority`() = runTest {
        listOf<(FakePackageFacts) -> Unit>(
            { it.beforeInstalled = { throw CancellationException("installed") } },
            { it.beforeArchive = { throw CancellationException("archive") } },
        ).forEach { cancelAdapter ->
            val fixture = fixture()
            fixture.prepare()
            val prior = fixture.repository.state.value
            val candidate = fixture.repository.boundArtifact()
            cancelAdapter(fixture.adapter)

            try { fixture.repository.verify(); fail() } catch (_: CancellationException) {}
            assertEquals(UpdateState.Available(requireNotNull(prior.accepted)), fixture.repository.state.value)
            assertNull(fixture.repository.boundArtifact())
            assertFalse(requireNotNull(candidate).file.exists())
        }
    }

    @Test fun `adapter cancellation during final install revalidation preserves exact verified authority`() = runTest {
        listOf<(FakePackageFacts) -> Unit>(
            { it.beforeInstalled = { throw CancellationException("installed") } },
            { it.beforeArchive = { throw CancellationException("archive") } },
        ).forEach { cancelAdapter ->
            val fixture = fixture()
            fixture.prepareVerified()
            val prior = fixture.repository.state.value
            val candidate = fixture.repository.boundArtifact()
            cancelAdapter(fixture.adapter)

            try { fixture.repository.install(); fail() } catch (_: CancellationException) {}
            assertSame(prior, fixture.repository.state.value)
            assertSame(candidate, fixture.repository.boundArtifact())
            assertTrue(fixture.directory.resolve(CANDIDATE_FILE_NAME).exists())
            assertEquals(0, fixture.launches)
            assertFalse(fixture.repository.state.value is UpdateState.Installing)
        }
    }

    @Test fun `adapter cancellation while settling install preserves exact lease and candidate`() = runTest {
        listOf<(FakePackageFacts) -> Unit>(
            { it.beforeInstalled = { throw CancellationException("installed") } },
            { it.beforeArchive = { throw CancellationException("archive") } },
        ).forEach { cancelAdapter ->
            val fixture = fixture(launcher = { _, _ -> true })
            fixture.prepareVerified()
            assertTrue(fixture.repository.install())
            val prior = fixture.repository.state.value
            val lease = (prior as UpdateState.Installing).lease
            val candidate = fixture.repository.boundArtifact()
            cancelAdapter(fixture.adapter)

            try { fixture.repository.settleInstallOnResume(lease); fail() } catch (_: CancellationException) {}
            assertSame(prior, fixture.repository.state.value)
            assertSame(candidate, fixture.repository.boundArtifact())
            assertTrue(fixture.directory.resolve(CANDIDATE_FILE_NAME).exists())
        }
    }

    @Test fun `true launcher leaves lease and blocks every repository action`() = runTest {
        val fixture = fixture(launcher = { _, _ -> true })
        fixture.prepareVerified()
        assertTrue(fixture.repository.install())
        assertTrue(fixture.repository.state.value is UpdateState.Installing)
        val fetches = fixture.fetcher.calls
        val downloads = fixture.downloads
        fixture.repository.checkManual()
        fixture.repository.download()
        fixture.repository.verify()
        assertFalse(fixture.repository.install())
        assertEquals(fetches, fixture.fetcher.calls)
        assertEquals(downloads, fixture.downloads)
        assertTrue(fixture.repository.state.value is UpdateState.Installing)
    }

    @Test fun `resume cancellation restores exact verified and upgrade clears candidate`() = runTest {
        val canceled = fixture(launcher = { _, _ -> true })
        canceled.prepareVerified()
        val prior = canceled.repository.state.value as UpdateState.Verified
        canceled.repository.install()
        val canceledLease = (canceled.repository.state.value as UpdateState.Installing).lease
        canceled.repository.settleInstallOnResume(canceledLease)
        assertSame(prior, canceled.repository.state.value)

        val upgraded = fixture(launcher = { _, _ -> true })
        upgraded.prepareVerified()
        upgraded.repository.install()
        val upgradedLease = (upgraded.repository.state.value as UpdateState.Installing).lease
        upgraded.adapter.installed = upgraded.candidate
        upgraded.repository.settleInstallOnResume(upgradedLease)
        assertNull(upgraded.repository.boundArtifact())
        assertFalse(upgraded.directory.resolve(CANDIDATE_FILE_NAME).exists())
    }

    @Test fun `resume stale candidate clears authority and cleanup refusal fails closed`() = runTest {
        val stale = fixture(launcher = { _, _ -> true })
        stale.prepareVerified()
        stale.repository.install()
        val staleLease = (stale.repository.state.value as UpdateState.Installing).lease
        stale.adapter.candidate = stale.candidate!!.copy(versionCode = 4)
        stale.repository.settleInstallOnResume(staleLease)
        assertNull(stale.repository.boundArtifact())

        var refuse = false
        val refused = fixture(launcher = { _, _ -> true }, delete = { file -> if (refuse) false else file.delete() })
        refused.prepareVerified()
        refused.repository.install()
        val refusedLease = (refused.repository.state.value as UpdateState.Installing).lease
        refused.adapter.candidate = null
        refuse = true
        refused.repository.settleInstallOnResume(refusedLease)
        assertTrue(refused.repository.state.value is UpdateState.Failed)
        assertNull(refused.repository.boundArtifact())
    }

    @Test fun `delayed settlement for stale lease cannot settle a newer install`() = runTest {
        val fixture = fixture(launcher = { _, _ -> true })
        fixture.prepareVerified()
        val verified = fixture.repository.state.value as UpdateState.Verified
        val candidate = fixture.repository.boundArtifact()

        assertTrue(fixture.repository.install())
        val lease1 = (fixture.repository.state.value as UpdateState.Installing).lease
        assertSame(lease1, fixture.launchedLease)
        val delayed = async(start = CoroutineStart.LAZY) {
            fixture.repository.settleInstallOnResume(lease1)
        }

        fixture.repository.settleInstallOnResume(lease1)
        assertSame(verified, fixture.repository.state.value)
        assertTrue(fixture.repository.install())
        val installing2 = fixture.repository.state.value as UpdateState.Installing
        val lease2 = installing2.lease
        assertTrue(lease1 !== lease2)
        assertSame(lease2, fixture.launchedLease)

        delayed.await()
        assertSame(installing2, fixture.repository.state.value)
        assertSame(lease2, (fixture.repository.state.value as UpdateState.Installing).lease)
        assertSame(candidate, fixture.repository.boundArtifact())

        fixture.repository.settleInstallOnResume(lease2)
        assertSame(verified, fixture.repository.state.value)
        assertSame(candidate, fixture.repository.boundArtifact())
    }

    private fun fixture(
        candidate: PackageFacts? = PackageFacts(
            NEMA_PACKAGE_NAME,
            3,
            setOf(signer),
            signingHistory = setOf(signer),
            currentSignerCount = 1,
        ),
        launcher: (BoundUpdateArtifact, InstallHandoffLease) -> Boolean = { _, _ -> false },
        delete: (File) -> Boolean = File::delete,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ): Fixture {
        val bytes = "signed candidate".toByteArray()
        val directory = temporary.newFolder()
        val manifest = UpdateManifest(1, NEMA_PACKAGE_NAME, 3, "0.2",
            "https://git.thanosapollo.org/nema/releases/v/app.apk", bytes.size.toLong(), sha256(bytes),
            "ff".repeat(32), "ab".repeat(20))
        val fetcher = Fetcher(manifestJson(manifest))
        val installed = PackageFacts(
            NEMA_PACKAGE_NAME,
            2,
            setOf(signer),
            signingHistory = setOf(signer),
            currentSignerCount = 1,
        )
        val adapter = FakePackageFacts(installed, candidate)
        var downloads = 0
        var launches = 0
        var launchedLease: InstallHandoffLease? = null
        val repository = UpdateRepository(2, fetcher, { 1L }, { 0L }, {}, { true }, directory,
            ApkDownloadEffect { _, part -> downloads++; part.writeBytes(bytes) }, delete,
            dispatcher, adapter, 34) { artifact, handoff ->
                launches++
                launchedLease = handoff
                launcher(artifact, handoff)
            }
        return Fixture(directory, repository, fetcher, adapter, installed, candidate,
            { downloads }, { launches }, { launchedLease })
    }

    private data class Fixture(
        val directory: File,
        val repository: UpdateRepository,
        val fetcher: Fetcher,
        val adapter: FakePackageFacts,
        val installed: PackageFacts,
        val candidate: PackageFacts?,
        private val downloadCount: () -> Int,
        private val launchCount: () -> Int,
        private val launchedLeaseValue: () -> InstallHandoffLease?,
    ) {
        val downloads get() = downloadCount()
        val launches get() = launchCount()
        val launchedLease get() = launchedLeaseValue()
        suspend fun prepare() { repository.checkManual(); repository.download() }
        suspend fun prepareVerified() { prepare(); repository.verify() }
    }

    private class FakePackageFacts(
        var installed: PackageFacts?,
        var candidate: PackageFacts?,
    ) : PackageFactsAdapter {
        var beforeInstalled: () -> Unit = {}
        var beforeArchive: () -> Unit = {}
        override fun installed(): PackageFacts? { beforeInstalled(); return installed }
        override fun archive(file: File): PackageFacts? { beforeArchive(); return candidate }
    }

    private class Fetcher(private val json: String) : ManifestFetcher {
        var calls = 0
        var failure: Exception? = null
        override suspend fun fetch(): String { calls++; failure?.let { throw it }; return json }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestJson(value: UpdateManifest) =
        """{"schemaVersion":1,"packageName":"${value.packageName}","versionCode":${value.versionCode},"versionName":"${value.versionName}","apkUrl":"${value.apkUrl}","size":${value.size},"sha256":"${value.sha256}","signerSha256":"${value.signerSha256}","sourceCommit":"${value.sourceCommit}"}"""
}
