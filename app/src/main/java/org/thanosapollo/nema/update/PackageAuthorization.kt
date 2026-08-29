package org.thanosapollo.nema.update

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.security.MessageDigest

internal const val NEMA_PACKAGE_NAME = "org.thanosapollo.nema"

data class PackageFacts(
    val packageName: String,
    val versionCode: Long,
    val currentSigners: Set<String>,
    val signingHistory: Set<String>,
    val singleApk: Boolean = true,
    val currentSignerCount: Int = currentSigners.size,
)

interface PackageFactsAdapter {
    fun installed(): PackageFacts?
    fun archive(file: File): PackageFacts?
}

fun packageUpgradeAuthorized(
    apiLevel: Int,
    installed: PackageFacts,
    candidate: PackageFacts,
    acceptedVersionCode: Long,
): Boolean {
    if (!installed.singleApk || !candidate.singleApk) return false
    if (installed.packageName != NEMA_PACKAGE_NAME || candidate.packageName != NEMA_PACKAGE_NAME) return false
    if (candidate.versionCode != acceptedVersionCode || candidate.versionCode <= installed.versionCode) return false
    if (installed.currentSignerCount != 1 || candidate.currentSignerCount != 1) return false
    if (installed.currentSigners.size != 1 || candidate.currentSigners.size != 1) return false
    if (installed.signingHistory.isEmpty() || candidate.signingHistory.isEmpty()) return false
    if (!installed.signingHistory.containsAll(installed.currentSigners)) return false
    if (!candidate.signingHistory.containsAll(candidate.currentSigners)) return false
    return if (apiLevel >= 28) {
        installed.currentSigners.any(candidate.signingHistory::contains)
    } else {
        installed.currentSigners == candidate.currentSigners
    }
}

class AndroidPackageFactsAdapter(
    private val packageManager: PackageManager,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
) : PackageFactsAdapter {
    override fun installed(): PackageFacts? = try {
        extract(packageManager.getPackageInfo(NEMA_PACKAGE_NAME, flags()))
    } catch (_: Exception) {
        null
    }

    override fun archive(file: File): PackageFacts? = try {
        packageManager.getPackageArchiveInfo(file.absolutePath, flags())?.let(::extract)
    } catch (_: Exception) {
        null
    }

    private fun flags(): Int = packageInfoFlags(apiLevel)

    private fun extract(info: PackageInfo): PackageFacts? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && apiLevel >= Build.VERSION_CODES.P) {
            extractModern(info)
        } else {
            extractLegacy(info)
        }
}

@Suppress("DEPRECATION")
@SuppressLint("InlinedApi")
internal fun packageInfoFlags(apiLevel: Int): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && apiLevel >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

@Suppress("DEPRECATION")
internal fun extractLegacy(info: PackageInfo): PackageFacts {
    val current = info.signatures.orEmpty()
    return packageFacts(
        info = info,
        versionCode = info.versionCode.toLong(),
        current = current,
        history = current,
        currentSignerCount = current.size,
    )
}

@RequiresApi(Build.VERSION_CODES.P)
internal fun extractModern(info: PackageInfo): PackageFacts? {
    val signing = info.signingInfo ?: return null
    val current = signing.apkContentsSigners.orEmpty()
    return packageFacts(
        info = info,
        versionCode = info.longVersionCode,
        current = current,
        history = signing.signingCertificateHistory.orEmpty(),
        currentSignerCount = if (signing.hasMultipleSigners()) maxOf(2, current.size) else current.size,
    )
}

internal fun packageFacts(
    info: PackageInfo,
    versionCode: Long,
    current: Array<out Signature>,
    history: Array<out Signature>,
    currentSignerCount: Int,
): PackageFacts = PackageFacts(
    packageName = info.packageName,
    versionCode = versionCode,
    currentSigners = current.map { it.toByteArray().certificateHash() }.toSet(),
    signingHistory = history.map { it.toByteArray().certificateHash() }.toSet(),
    singleApk = info.splitNames.isNullOrEmpty(),
    currentSignerCount = currentSignerCount,
)

private fun ByteArray.certificateHash(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
