package org.thanosapollo.nema.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.thanosapollo.nema.BuildConfig
import org.thanosapollo.nema.InstallResumeGate

private const val UPDATE_PREFERENCES = "updates"
private const val LAST_SUCCESS = "last-success"
private const val APK_MIME = "application/vnd.android.package-archive"

class UpdateCoordinator(
    scope: CoroutineScope,
    createRepository: () -> UpdateRepository,
    constructionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val repository: Deferred<UpdateRepository?> = scope.async(constructionDispatcher) {
        try {
            createRepository()
        } catch (failure: CancellationException) {
            throw failure
        } catch (error: Error) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    init {
        scope.launch {
            val available = repository.await() ?: return@launch
            available.state.collect { mutableState.value = it }
        }
    }

    suspend fun checkAutomatic() {
        repository.await()?.checkAutomatic()
    }

    suspend fun checkManual() {
        val available = repository.await()
        if (available == null) {
            mutableState.value = UpdateState.Failed(
                "Could not check for updates. Try again.",
                mutableState.value.accepted,
            )
            return
        }
        available.checkManual()
    }

    suspend fun downloadAndVerify() {
        repository.await()?.run {
            download()
            verify()
        }
    }

    suspend fun install(): Boolean = repository.await()?.install() ?: false
    suspend fun settleInstallOnResume(handoff: InstallHandoffLease) =
        repository.await()?.settleInstallOnResume(handoff)
}

internal fun createAndroidUpdateRepository(context: Context, installResumeGate: InstallResumeGate): UpdateRepository {
    val appContext = context.applicationContext
    val preferences = appContext.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
    return UpdateRepository(
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        fetcher = HttpsManifestFetcher(),
        clock = System::currentTimeMillis,
        lastSuccess = { preferences.getLong(LAST_SUCCESS, 0) },
        saveSuccess = { preferences.edit().putLong(LAST_SUCCESS, it).apply() },
        networkAvailable = { networkAvailable(appContext) },
        updateDirectory = File(appContext.cacheDir, "updates"),
        downloadEffect = HttpsApkDownloadEffect(),
        packageFacts = AndroidPackageFactsAdapter(appContext.packageManager),
        apiLevel = Build.VERSION.SDK_INT,
        installLauncher = packageInstallerLauncher(appContext, installResumeGate),
    )
}

fun requestInstallOrPermission(
    context: Context,
    apiLevel: Int = Build.VERSION.SDK_INT,
    canInstallPackages: Boolean = context.packageManager.canRequestPackageInstalls(),
    install: () -> Unit,
) {
    if (apiLevel >= Build.VERSION_CODES.O && !canInstallPackages) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
        return
    }
    install()
}

internal fun packageInstallerLauncher(
    context: Context,
    installResumeGate: InstallResumeGate,
    uriForFile: (Context, String, File) -> Uri = { ctx, authority, file ->
        FileProvider.getUriForFile(ctx, authority, file)
    },
): (BoundUpdateArtifact, InstallHandoffLease) -> Boolean = { bound, handoff ->
    try {
        val authority = "${context.packageName}.files"
        val uri = uriForFile(context, authority, bound.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!installResumeGate.arm(handoff)) false
        else try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            installResumeGate.abort(handoff)
            false
        }
    } catch (_: Exception) {
        false
    }
}

private fun networkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
