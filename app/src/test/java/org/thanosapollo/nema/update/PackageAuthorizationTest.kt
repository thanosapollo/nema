package org.thanosapollo.nema.update

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PackageAuthorizationTest {
    private val old = "11".repeat(32)
    private val current = "22".repeat(32)
    private val unrelated = "33".repeat(32)

    @Test fun `api 26 and 27 require one equal current signer`() {
        listOf(26, 27).forEach { api ->
            assertTrue(packageUpgradeAuthorized(api, facts(2, old), facts(3, old), 3))
            assertFalse(packageUpgradeAuthorized(api, facts(2, old), facts(3, current), 3))
            assertFalse(packageUpgradeAuthorized(api, facts(2, old, setOf(old, current)), facts(3, old), 3))
        }
    }

    @Test fun `api 28 accepts verified rotation and rejects unrelated or malformed histories`() {
        assertTrue(packageUpgradeAuthorized(28, facts(2, old), facts(3, current, history = setOf(old, current)), 3))
        assertFalse(packageUpgradeAuthorized(28, facts(2, old), facts(3, unrelated), 3))
        assertFalse(packageUpgradeAuthorized(28, facts(2, old, history = setOf(unrelated)), facts(3, old), 3))
        assertFalse(packageUpgradeAuthorized(28, facts(2, old), facts(3, current, history = setOf(old)), 3))
        assertFalse(packageUpgradeAuthorized(28, facts(2, old), facts(3, current, history = emptySet()), 3))
        assertFalse(packageUpgradeAuthorized(28, facts(2, old, setOf(old, current)), facts(3, current), 3))
    }

    @Test fun `raw current signer cardinality must be exactly one`() {
        listOf(0, 2).forEach { count ->
            assertFalse(packageUpgradeAuthorized(34, facts(2, old, currentSignerCount = count), facts(3, old), 3))
            assertFalse(packageUpgradeAuthorized(34, facts(2, old), facts(3, old, currentSignerCount = count), 3))
        }
    }

    @Test fun `package and version facts are exact`() {
        assertFalse(packageUpgradeAuthorized(34, facts(2, old), facts(3, old, pkg = "other"), 3))
        assertFalse(packageUpgradeAuthorized(34, facts(2, old), facts(3, old).copy(singleApk = false), 3))
        assertFalse(packageUpgradeAuthorized(34, facts(2, old), facts(4, old), 3))
        assertFalse(packageUpgradeAuthorized(34, facts(3, old), facts(3, old), 3))
        assertTrue(packageUpgradeAuthorized(34, facts(2, old), facts(3, old), 3))
    }

    @Test
    @Config(sdk = [26])
    fun `api 26 uses legacy flags and preserves raw signatures as history`() {
        val first = Signature(byteArrayOf(1))
        val duplicate = Signature(byteArrayOf(1))
        val info = PackageInfo().apply {
            packageName = NEMA_PACKAGE_NAME
            versionCode = 7
            signatures = arrayOf(first, duplicate)
        }

        assertEquals(PackageManager.GET_SIGNATURES, packageInfoFlags(34))
        val extracted = extractLegacy(info)
        assertEquals(7L, extracted.versionCode)
        assertEquals(2, extracted.currentSignerCount)
        assertEquals(extracted.currentSigners, extracted.signingHistory)
        assertEquals(setOf(first.hash()), extracted.signingHistory)
    }

    @Test
    @Config(sdk = [34])
    fun `api 34 uses modern flags and maps raw signer history`() {
        val current = Signature(byteArrayOf(2))
        val previous = Signature(byteArrayOf(1))
        val info = PackageInfo().apply { packageName = NEMA_PACKAGE_NAME }

        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, packageInfoFlags(34))
        assertEquals(PackageManager.GET_SIGNATURES, packageInfoFlags(27))
        val extracted = packageFacts(info, 9L, arrayOf(current), arrayOf(previous, current), 2)
        assertEquals(9L, extracted.versionCode)
        assertEquals(2, extracted.currentSignerCount)
        assertEquals(setOf(current.hash()), extracted.currentSigners)
        assertEquals(setOf(previous.hash(), current.hash()), extracted.signingHistory)
    }

    private fun facts(
        version: Long,
        signer: String,
        currentSigners: Set<String> = setOf(signer),
        currentSignerCount: Int = currentSigners.size,
        history: Set<String> = currentSigners,
        pkg: String = NEMA_PACKAGE_NAME,
    ) = PackageFacts(
        packageName = pkg,
        versionCode = version,
        currentSigners = currentSigners,
        currentSignerCount = currentSignerCount,
        signingHistory = history,
    )

    private fun Signature.hash(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
}
