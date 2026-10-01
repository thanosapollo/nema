package org.thanosapollo.nema

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.thanosapollo.nema.update.*

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@Config(sdk = [34], application = NemaApplication::class)
class MainActivityUpdateJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: NemaApplication
    private lateinit var coordinator: UpdateCoordinator
    private val fetches = AtomicInteger()
    private val downloads = AtomicInteger()
    private val installs = AtomicInteger()
    private val bytes = "test archive".toByteArray()
    private var downloadRelease: CompletableDeferred<Unit>? = null
    @Volatile private var failDownload = false
    @Volatile private var badSigner = false
    private var beforeInstalled: () -> Unit = {}
    private lateinit var repository: UpdateRepository
    private val directory get() = File(app.cacheDir, "updates")
    @Volatile private var online = true
    @Volatile private var failFetch = false
    @Volatile private var now = 100L
    @Volatile private var saved = 0L
    private var release: CompletableDeferred<Unit>? = null
    private var activity: ActivityController<MainActivity>? = null

    @Before fun setup() {
        java.security.Security.addProvider(object : java.security.Provider("NemaUpdateTest", 1.0, "test") {
            init { put("KeyStore.AndroidKeyStore", "com.sun.crypto.provider.JceKeyStore") }
        })
        app = ApplicationProvider.getApplicationContext()
        // AndroidX caches authority roots statically, but Robolectric gives each test
        // a different cacheDir. Do not let another test's root impersonate this app.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
        runBlocking { withTimeout(10_000) { app.databaseStartup.first { it != DatabaseStartup.OPENING } } }
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        replaceProcessCoordinator()
    }

    private fun replaceProcessCoordinator() {
        coordinator = UpdateCoordinator(process, {
            UpdateRepository(5, ManifestFetcher {
                fetches.incrementAndGet()
                release?.await()
                if (failFetch) error("offline")
                manifest
            }, { now }, { saved }, { saved = it }, { online },
                updateDirectory = directory,
                downloadEffect = ApkDownloadEffect { _, part ->
                    downloads.incrementAndGet()
                    part.writeBytes(bytes)
                    downloadRelease?.await()
                    if (failDownload) error("private transport detail")
                },
                packageFacts = object : PackageFactsAdapter {
                    override fun installed(): PackageFacts {
                        beforeInstalled()
                        return PackageFacts(NEMA_PACKAGE_NAME, 5, setOf("signer"), setOf("signer"))
                    }
                    override fun archive(file: File) = PackageFacts(NEMA_PACKAGE_NAME, 7,
                        setOf(if (badSigner) "other" else "signer"), setOf(if (badSigner) "other" else "signer"))
                },
                installLauncher = { artifact, lease ->
                    installs.incrementAndGet()
                    packageInstallerLauncher(app, app.installResumeGate)(artifact, lease)
                },
            ).also { repository = it }
        })
        NemaApplication::class.java.getDeclaredField("updates").apply { isAccessible = true }.set(app, coordinator)
    }

    private fun launch(savedState: Bundle? = null) {
        activity = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState).start().resume().visible()
    }

    private fun awaitAvailable() {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            coordinator.state.value is UpdateState.Available
        }
    }

    @After fun cleanup() {
        activity?.pause()?.stop()?.destroy()
        process.cancel()
        java.security.Security.removeProvider("NemaUpdateTest")
    }

    @Test fun availableUpdateIsVisibleOnLoginAndOpensUpdateControls() {
        launch()
        awaitAvailable()
        compose.onNodeWithTag("app-update").performClick()
        compose.onNodeWithText("Nema 0.4 is available").assertExists()
        compose.onNodeWithTag("update-action").assertExists()
        assertEquals(1, fetches.get())
    }

    @Test fun offlineLaunchRetriesOnForegroundWithoutRecreatingActivity() {
        online = false
        launch()
        compose.waitForIdle()
        assertEquals(0, fetches.get())
        activity!!.pause().stop()
        online = true
        activity!!.start().resume()
        awaitAvailable()
        assertEquals(1, fetches.get())
    }

    @Test fun failedNetworkCheckRetriesOnNextForeground() {
        failFetch = true
        release = CompletableDeferred()
        launch()
        compose.waitUntil(5_000) { fetches.get() == 1 }
        val check = requireNotNull(coordinator.onForeground())
        release!!.complete(Unit)
        runBlocking { check.join() }
        activity!!.pause().stop()
        failFetch = false
        activity!!.start().resume()
        awaitAvailable()
        assertEquals(2, fetches.get())
    }

    @Test fun savedActivityInNewProcessDiscoversDespiteRecentPersistedSuccess() {
        saved = 99L
        launch(Bundle())
        awaitAvailable()
        assertEquals(1, fetches.get())
        activity!!.pause().stop().destroy()
        activity = null
        replaceProcessCoordinator()
        launch(Bundle())
        awaitAvailable()
        assertEquals(2, fetches.get())
    }

    @Test fun recreationDoesNotCancelOrDuplicateInFlightDiscovery() {
        release = CompletableDeferred()
        launch()
        compose.waitUntil(5_000) { fetches.get() == 1 }
        activity!!.recreate()
        release!!.complete(Unit)
        awaitAvailable()
        activity!!.pause().stop().start().resume()
        compose.waitForIdle()
        assertEquals(1, fetches.get())
    }

    private fun startUpdate() {
        launch()
        awaitAvailable()
        assertEquals(0, downloads.get())
        compose.onNodeWithTag("app-update").performClick()
        compose.onNodeWithTag("update-action").performClick()
    }

    private fun awaitInstall() {
        try {
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                coordinator.state.value is UpdateState.Installing
            }
        } catch (failure: Throwable) {
            throw AssertionError("state=${coordinator.state.value}; message=${coordinator.message.value}; " +
                "request=${coordinator.installRequest.value}; installs=${installs.get()}; lifecycle=${activity!!.get().lifecycle.currentState}", failure)
        }
        assertEquals(1, installs.get())
    }

    @Test fun oneUpdateActionSurvivesRecreationAndOpensInstallerWithoutSecondClick() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        downloadRelease = CompletableDeferred()
        startUpdate()
        compose.waitUntil(5_000) { downloads.get() == 1 }
        val accepted = coordinator.state.value.accepted
        now += 86_400_000L // discovery would be due, but must not revoke the user action
        activity!!.recreate()
        assertEquals(1, fetches.get())
        assertEquals(accepted, coordinator.state.value.accepted)
        downloadRelease!!.complete(Unit)
        awaitInstall()
        val installer = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, installer.action)
        assertEquals("application/vnd.android.package-archive", installer.type)
        assertEquals("content", installer.data!!.scheme)
        // Installer cancellation restores a retry, not an automatic launch loop.
        activity!!.pause().stop().start().resume()
        compose.waitUntil(5_000) { coordinator.state.value is UpdateState.Verified }
        compose.waitForIdle()
        assertEquals(1, installs.get())
        assertEquals(1, downloads.get())
    }

    @Test fun permissionReturnContinuesOnceAfterRecreationUsingActualPermission() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        startUpdate()
        compose.waitUntil(5_000) { shadowOf(activity!!.get()).peekNextStartedActivityForResult() != null }
        val request = shadowOf(activity!!.get()).nextStartedActivityForResult
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.intent.action)
        assertEquals("package:${app.packageName}", request.intent.data.toString())
        assertEquals(0, installs.get())
        activity!!.recreate()
        activity!!.pause().stop().start()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        activity!!.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        assertEquals(0, installs.get()) // real registry delivery precedes onResume
        activity!!.resume()
        awaitInstall()
        activity!!.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        compose.waitForIdle()
        assertEquals(1, installs.get())
    }

    @Test fun revokedPermissionBetweenResultAndResumeDoesNotReopenSettings() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        startUpdate()
        compose.waitUntil(5_000) { shadowOf(activity!!.get()).peekNextStartedActivityForResult() != null }
        val request = shadowOf(activity!!.get()).nextStartedActivityForResult
        activity!!.pause().stop().start()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        activity!!.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        activity!!.resume()
        compose.onNodeWithText("Allow Nema to install updates, then try again.").assertExists()
        assertEquals(0, installs.get())
        assertNull(shadowOf(activity!!.get()).nextStartedActivityForResult)
    }

    @Test fun permissionDenialShowsRetryAndNeverLoopsOrInstalls() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        startUpdate()
        compose.waitUntil(5_000) { shadowOf(activity!!.get()).peekNextStartedActivityForResult() != null }
        val request = shadowOf(activity!!.get()).nextStartedActivityForResult
        activity!!.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        compose.onNodeWithText("Allow Nema to install updates, then try again.").assertExists()
        activity!!.pause().stop().start().resume()
        compose.waitForIdle()
        assertEquals(0, installs.get())
        assertNull(shadowOf(activity!!.get()).nextStartedActivityForResult)
        compose.onNodeWithTag("update-action").assertExists()
    }

    @Test fun changedArtifactOnPermissionReturnCannotAuthorizeInstallation() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        startUpdate()
        compose.waitUntil(5_000) { shadowOf(activity!!.get()).peekNextStartedActivityForResult() != null }
        val request = shadowOf(activity!!.get()).nextStartedActivityForResult
        directory.resolve("candidate.apk").appendText("tampered")
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        activity!!.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        compose.waitUntil(5_000) { coordinator.message.value != null }
        assertEquals(0, installs.get())
        assertFalse(directory.resolve("candidate.apk").exists())
        compose.onNodeWithText("Could not open installer. Try again.").assertExists()
    }

    @Test fun newProcessDoesNotRestorePermissionOrInstallAuthority() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        startUpdate()
        compose.waitUntil(5_000) { shadowOf(activity!!.get()).peekNextStartedActivityForResult() != null }
        val request = shadowOf(activity!!.get()).nextStartedActivityForResult
        val savedState = Bundle()
        activity!!.pause().saveInstanceState(savedState).stop().destroy()
        activity = null
        replaceProcessCoordinator()
        launch(savedState)
        awaitAvailable()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        activity!!.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
        compose.waitForIdle()
        assertEquals(0, installs.get())
        assertFalse(directory.resolve("candidate.apk").exists())
        assertNull(coordinator.installRequest.value)
    }

    @Test fun downloadFailureIsVisibleAndRetryUsesSameExplicitJourney() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        failDownload = true
        startUpdate()
        compose.waitUntil(5_000) { coordinator.state.value is UpdateState.Failed }
        compose.onNodeWithText("Update failed. Try again.").assertExists()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(0, installs.get())
        failDownload = false
        compose.onNodeWithTag("update-action").performClick()
        awaitInstall()
        assertEquals(2, downloads.get())
    }

    @Test fun badSignerShowsFailureAndNeverLaunchesPermissionOrInstaller() {
        badSigner = true
        startUpdate()
        compose.waitUntil(5_000) { coordinator.state.value is UpdateState.Failed }
        compose.onNodeWithText("Update failed. Try again.").assertExists()
        assertEquals(0, installs.get())
        assertNull(shadowOf(activity!!.get()).nextStartedActivityForResult)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun missingPermissionSettingsShowsFailureAndRetainsExplicitRetry() {
        shadowOf(app).checkActivities(true)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        startUpdate()
        compose.waitUntil(5_000) { coordinator.message.value != null }
        compose.onNodeWithText("Could not open install permission settings. Try again.").assertExists()
        assertTrue(coordinator.state.value is UpdateState.Verified)
        assertEquals(0, installs.get())
        assertNull(coordinator.installRequest.value)
        shadowOf(app).checkActivities(false)
        compose.onNodeWithTag("update-action").performClick()
        compose.waitUntil(5_000) { shadowOf(activity!!.get()).peekNextStartedActivityForResult() != null }
        assertEquals(1, downloads.get())
    }

    @Test fun missingInstallerShowsFailureWithoutLosingVerifiedRetry() {
        shadowOf(app).checkActivities(true)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        startUpdate()
        compose.waitUntil(5_000) { coordinator.message.value != null }
        compose.onNodeWithText("Could not open installer. Try again.").assertExists()
        assertTrue(coordinator.state.value is UpdateState.Verified)
        assertEquals(1, installs.get())
        activity!!.pause().stop().start().resume()
        compose.waitForIdle()
        assertEquals(1, installs.get())
    }

    @Test fun cancelReadyContinuationBeforeResumeCannotOpenInstaller() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        downloadRelease = CompletableDeferred()
        startUpdate()
        compose.waitUntil(5_000) { downloads.get() == 1 }
        activity!!.pause().stop()
        downloadRelease!!.complete(Unit)
        compose.waitUntil(5_000) { coordinator.installRequest.value != null }
        coordinator.cancelUpdate()
        compose.waitUntil(5_000) { coordinator.state.value is UpdateState.Available }
        activity!!.start().resume()
        compose.waitForIdle()
        assertNull(coordinator.installRequest.value)
        assertEquals(0, installs.get())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelVerificationSettlesAfterBlockingWorkWithoutInstallerOrStuckProgress() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        beforeInstalled = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        try {
            startUpdate()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("update-cancel").performClick()
            assertNull(coordinator.requestUpdate()) // old verification still owns its file
            finish.countDown()
            awaitAvailable()
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertNull(coordinator.installRequest.value)
            assertEquals(0, installs.get())
            compose.onNodeWithText("Update failed. Try again.").assertDoesNotExist()
        } finally { finish.countDown() }
    }

    @Test fun cancelDownloadCleansFilesWithoutPresentingFailure() {
        downloadRelease = CompletableDeferred()
        startUpdate()
        compose.waitUntil(5_000) { coordinator.state.value is UpdateState.Downloading }
        compose.onNodeWithTag("update-cancel").performClick()
        awaitAvailable()
        compose.onNodeWithText("Update failed. Try again.").assertDoesNotExist()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(0, installs.get())
    }

    private val manifest = """{"schemaVersion":1,"packageName":"org.thanosapollo.nema","versionCode":7,"versionName":"0.4","apkUrl":"https://git.thanosapollo.org/nema/releases/nema.apk","size":${bytes.size},"sha256":"${MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }}","signerSha256":"${"b".repeat(64)}","sourceCommit":"${"c".repeat(40)}"}"""
}
