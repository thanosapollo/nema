package org.thanosapollo.nema.update

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateAndroidContractTest {
    @Test
    fun missingPermissionOpensOnlyPerAppUnknownSourceSettings() {
        val context = Robolectric.buildActivity(Activity::class.java).get()
        var installs = 0

        requestInstallOrPermission(context, 34, canInstallPackages = false) { installs++ }

        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        assertEquals(0, installs)
    }

    @Test
    fun grantedPermissionAndPreOInvokeInstallExactlyOnce() {
        val context = Robolectric.buildActivity(Activity::class.java).get()
        var installs = 0
        requestInstallOrPermission(context, 34, true) { installs++ }
        requestInstallOrPermission(context, 25, false) { installs++ }
        assertEquals(2, installs)
    }

    @Test
    fun unavailableUnknownSourceSettingsFailsClosedWithoutTouchingVerifiedState() {
        val base = Robolectric.buildActivity(Activity::class.java).get()
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no settings handler")
            }
        }
        val verified = verifiedFixture()
        val state = arrayOf<UpdateState>(verified)
        var installs = 0

        requestInstallOrPermission(context, 34, canInstallPackages = false) { installs++ }

        assertEquals(0, installs)
        assertSame(verified, state.single())
    }

    @Test
    fun installerUsesExactPrivateContentUriMimeAndFlags() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(context.cacheDir, "updates").also { it.mkdirs() }
        val file = File(directory, "candidate.apk").also { it.writeBytes(byteArrayOf(1)) }
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val bound = BoundUpdateArtifact(accepted, file, 1, "a".repeat(64))
        val handoff = InstallHandoffLease(verifiedFixture(bound))
        val gate = org.thanosapollo.nema.InstallResumeGate()
        assertEquals(null, gate.onResumeAndBeginSettlement())
        val authority = "${context.packageName}.files"
        val uri = Uri.parse("content://$authority/updates/candidate.apk")
        val expected = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_FOR_TEST)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val installer = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "installer"
                name = "InstallerActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(expected, installer)
        var uriRequest: Triple<Context, String, File>? = null

        assertTrue(packageInstallerLauncher(context, gate) { requestedContext, requestedAuthority, requestedFile ->
            uriRequest = Triple(requestedContext, requestedAuthority, requestedFile)
            uri
        }(bound, handoff))

        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Triple(context, authority, file), uriRequest)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(APK_MIME_FOR_TEST, intent.type)
        assertEquals(uri, intent.data)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.files", intent.data?.authority)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
            intent.flags,
        )
        gate.onPause()
        assertSame(handoff, gate.onResumeAndBeginSettlement())
    }

    @Test
    fun installerHiddenFromQueriesStillLaunchesAndSettles() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        var launched: Intent? = null
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                launched = intent
            }
        }
        val file = File(base.cacheDir, "updates/hidden.apk")
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val bound = BoundUpdateArtifact(accepted, file, 1, "a".repeat(64))
        val handoff = InstallHandoffLease(verifiedFixture(bound))
        val gate = org.thanosapollo.nema.InstallResumeGate()
        assertEquals(null, gate.onResumeAndBeginSettlement())
        val uri = Uri.parse("content://${base.packageName}.files/updates/hidden.apk")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_FOR_TEST)
        assertEquals(null, intent.resolveActivity(base.packageManager))

        assertTrue(packageInstallerLauncher(context, gate) { _, _, _ -> uri }(bound, handoff))
        assertEquals(uri, launched?.data)
        assertEquals(APK_MIME_FOR_TEST, launched?.type)
        gate.onPause()
        assertSame(handoff, gate.onResumeAndBeginSettlement())
    }

    @Test
    fun missingInstallerHandlerFailsClosed() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("no installer handler")
            }
        }
        val file = File(context.cacheDir, "updates/candidate.apk").also {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(1))
        }
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val uri = Uri.parse("content://${context.packageName}.files/updates/candidate.apk")
        val bound = BoundUpdateArtifact(accepted, file, 1, "a".repeat(64))
        val handoff = InstallHandoffLease(verifiedFixture(bound))
        val gate = org.thanosapollo.nema.InstallResumeGate()
        assertEquals(null, gate.onResumeAndBeginSettlement())
        assertFalse(packageInstallerLauncher(context, gate) { _, _, _ -> uri }(
            bound, handoff,
        ))
        gate.onPause()
        assertEquals(null, gate.onResumeAndBeginSettlement())
    }

    @Test
    fun pausedGateRejectsHandoffWithoutStartingInstaller() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        var launches = 0
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                launches++
            }
        }
        val file = File(base.cacheDir, "updates/rejected.apk").also {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(1))
        }
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val bound = BoundUpdateArtifact(accepted, file, 1, "a".repeat(64))
        val handoff = InstallHandoffLease(verifiedFixture(bound))
        val uri = Uri.parse("content://${base.packageName}.files/updates/rejected.apk")
        val expected = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_FOR_TEST)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        shadowOf(base.packageManager).addResolveInfoForIntent(expected, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "installer"
                name = "InstallerActivity"
            }
        })
        val gate = org.thanosapollo.nema.InstallResumeGate()
        gate.onPause()

        assertFalse(packageInstallerLauncher(context, gate) { _, _, _ -> uri }(bound, handoff))
        assertEquals(0, launches)
        assertEquals(null, gate.onResumeAndBeginSettlement())
    }

    @Test
    fun installerStartFailureAbortsLeaseMovedToPendingByPause() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        lateinit var gate: org.thanosapollo.nema.InstallResumeGate
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) {
                gate.onPause()
                throw ActivityNotFoundException("installer disappeared")
            }
        }
        val file = File(base.cacheDir, "updates/failed-start.apk").also {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(1))
        }
        val accepted = AcceptedGeneration(1, AcceptedUpdate.Available(manifest()))
        val bound = BoundUpdateArtifact(accepted, file, 1, "a".repeat(64))
        val handoff = InstallHandoffLease(verifiedFixture(bound))
        val uri = Uri.parse("content://${base.packageName}.files/updates/failed-start.apk")
        val expected = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_FOR_TEST)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        shadowOf(base.packageManager).addResolveInfoForIntent(expected, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "installer"
                name = "InstallerActivity"
            }
        })
        gate = org.thanosapollo.nema.InstallResumeGate()
        assertEquals(null, gate.onResumeAndBeginSettlement())

        assertFalse(packageInstallerLauncher(context, gate) { _, _, _ -> uri }(bound, handoff))
        assertEquals(null, gate.onResumeAndBeginSettlement())
    }

    @Test
    fun manifestAndProviderPathAreNarrow() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val paths = File("src/main/res/xml/file_paths.xml").readText()
        assertTrue(manifest.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.files\""))
        assertTrue(paths.contains("<cache-path name=\"updates\" path=\"updates/\""))
        assertFalse(paths.contains("path=\".\""))
        assertFalse(paths.contains("<root-path"))
    }

    @Test
    fun installerRequiresExactArmImmediatelyBeforeStartingActivity() {
        val source = File("src/main/java/org/thanosapollo/nema/update/AndroidUpdates.kt").readText()
        val armThenStart = Regex(
            """if \(!installResumeGate\.arm\(handoff\)\) false\s*else\s*try \{\s*context\.startActivity\(intent\)""",
        )

        assertTrue(armThenStart.containsMatchIn(source))
    }

    private fun manifest() = UpdateManifest(
        1, NEMA_PACKAGE_NAME, 3, "0.2", "https://git.thanosapollo.org/nema/releases/nema.apk",
        1, "a".repeat(64), "b".repeat(64), "c".repeat(40),
    )

    private fun verifiedFixture(
        artifact: BoundUpdateArtifact = BoundUpdateArtifact(
            AcceptedGeneration(1, AcceptedUpdate.Available(manifest())),
            File("candidate.apk"),
            1,
            "a".repeat(64),
        ),
    ): UpdateState.Verified {
        val facts = PackageFacts(
            NEMA_PACKAGE_NAME,
            2,
            setOf("b".repeat(64)),
            signingHistory = setOf("b".repeat(64)),
        )
        return UpdateState.Verified(artifact.accepted, VerifiedUpdate(artifact, facts, facts))
    }

    private companion object {
        const val APK_MIME_FOR_TEST = "application/vnd.android.package-archive"
    }
}
