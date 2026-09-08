package org.thanosapollo.nema.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    @Test
    fun ordinaryFactoryFailureStaysUnavailableWithoutCancellingProcessScope() = runTest {
        val processJob = Job()
        val uncaught = mutableListOf<Throwable>()
        val processScope = CoroutineScope(
            processJob + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, failure -> uncaught += failure },
        )
        var constructions = 0
        val coordinator = UpdateCoordinator(
            scope = processScope,
            createRepository = {
                constructions++
                throw IllegalStateException("private construction detail")
            },
            constructionDispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            runCurrent()
            assertTrue(processJob.isActive)
            assertTrue(uncaught.isEmpty())

            coordinator.checkAutomatic()
            assertEquals(UpdateState.Idle, coordinator.state.value)
            coordinator.checkManual()
            assertEquals(
                UpdateState.Failed("Could not check for updates. Try again.", null),
                coordinator.state.value,
            )
            coordinator.downloadAndVerify()
            assertFalse(coordinator.install())
            assertEquals(1, constructions)
            assertTrue(processJob.isActive)
            assertTrue(uncaught.isEmpty())
        } finally {
            processScope.cancel()
        }
    }

    @Test
    fun factoryCancellationPropagatesToCaller() = runTest {
        val cancellation = CancellationException("stop")
        val coordinator = UpdateCoordinator(
            scope = backgroundScope,
            createRepository = { throw cancellation },
            constructionDispatcher = StandardTestDispatcher(testScheduler),
        )

        val observed = runCatching { coordinator.checkAutomatic() }.exceptionOrNull()

        assertTrue(observed is CancellationException)
        assertEquals(cancellation.message, observed?.message)
    }

    @Test
    fun constructionIsDeferredAndDoesNotFetchUntilLaunchCheck() = runTest {
        var constructions = 0
        var fetches = 0
        val coordinator = UpdateCoordinator(
            scope = backgroundScope,
            createRepository = {
                constructions++
                UpdateRepository(
                    2,
                    ManifestFetcher { fetches++; manifestJson() },
                    { 1 }, { 0 }, {}, { true },
                )
            },
            constructionDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(0, constructions)
        assertEquals(0, fetches)
        coordinator.checkAutomatic()
        assertEquals(1, constructions)
        assertEquals(1, fetches)
    }

    @Test
    fun androidFactoryOwnsExactVersionCachePreferencesAndIndependentXmppWiring() {
        val android = File("src/main/java/org/thanosapollo/nema/update/AndroidUpdates.kt").readText()
        val application = File("src/main/java/org/thanosapollo/nema/NemaApplication.kt").readText()
        val activity = File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()

        assertTrue(android.contains("BuildConfig.VERSION_CODE.toLong()"))
        assertTrue(android.contains("File(appContext.cacheDir, \"updates\")"))
        assertTrue(android.contains("getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)"))
        assertTrue(android.contains("HttpsManifestFetcher()"))
        assertTrue(android.contains("HttpsApkDownloadEffect()"))
        assertTrue(application.indexOf("updates = UpdateCoordinator") < application.indexOf("SmackAndroid.initialize"))
        val databaseOpen = application.indexOf("NemaDatabase.create(")
        assertTrue(databaseOpen >= 0)
        assertTrue(application.indexOf("updates = UpdateCoordinator") in 0 until databaseOpen)
        assertEquals(1, "updates\\.checkAutomatic\\(\\)".toRegex().findAll(activity).count())
        assertEquals(0, "settleInstallOnResume".toRegex().findAll(activity).count())
        assertEquals(1, "installResumeController\\.onResume\\(\\)".toRegex().findAll(activity).count())
        val processController = Regex(
            "InstallResumeController\\s*\\(\\s*applicationScope\\s*,\\s*installResumeGate\\s*\\)\\s*\\{\\s*handoff\\s*->\\s*updates\\.settleInstallOnResume\\(handoff\\)\\s*}",
        )
        assertEquals(1, processController.findAll(application).count())
        assertEquals(1, "updates\\.settleInstallOnResume\\(handoff\\)".toRegex().findAll(application).count())
        assertTrue(application.indexOf("updates = UpdateCoordinator") < application.indexOf("InstallResumeController("))
        assertTrue(android.contains("suspend fun settleInstallOnResume(handoff: InstallHandoffLease)"))
        assertTrue(android.contains("repository.await()?.settleInstallOnResume(handoff)"))
        assertTrue(android.contains("fun createAndroidUpdateRepository(context: Context, installResumeGate: InstallResumeGate)"))
        assertTrue(android.contains("packageInstallerLauncher(appContext, installResumeGate)"))
        assertTrue(application.contains("createAndroidUpdateRepository(applicationContext, installResumeGate)"))
        assertFalse(activity.contains("WorkManager"))
    }

    private fun manifestJson() = """{
        "schemaVersion":1,"packageName":"org.thanosapollo.nema","versionCode":3,
        "versionName":"0.2","apkUrl":"https://git.thanosapollo.org/nema/releases/nema.apk",
        "size":1,"sha256":"${"a".repeat(64)}","signerSha256":"${"b".repeat(64)}",
        "sourceCommit":"${"c".repeat(40)}"
    }""".trimIndent()
}
