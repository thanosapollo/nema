package org.thanosapollo.nema.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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

data class UpdateInstallRequest(val authority: VerifiedUpdate, val mayRequestPermission: Boolean)

class UpdateCoordinator(
    private val scope: CoroutineScope,
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

    // Jobs and continuation authority belong to the process, never an Activity or
    // composition. No artifact/permission/install authority survives process death.
    private var checkJob: Job? = null
    private var updateJob: Job? = null
    private var preparationCancelable = false
    private val mutableInstallRequest = MutableStateFlow<UpdateInstallRequest?>(null)
    val installRequest: StateFlow<UpdateInstallRequest?> = mutableInstallRequest.asStateFlow()
    private var permissionRequest: VerifiedUpdate? = null
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    fun onForeground() = requestCheck(manual = false)

    @Synchronized
    fun requestCheck(manual: Boolean = true): Job? {
        if (checkJob?.isCompleted == false) return checkJob
        if (updatePending()) return null
        return scope.launch(start = CoroutineStart.LAZY) {
            if (manual) {
                mutableMessage.value = null
                checkManual()
            } else checkAutomatic()
        }.also { checkJob = it; it.start() }
    }

    @Synchronized
    fun requestUpdate(): Job? {
        if (checkJob?.isCompleted == false || updatePending()) return null
        mutableMessage.value = null
        preparationCancelable = true
        return scope.launch(start = CoroutineStart.LAZY) {
            val available = repository.await() ?: return@launch
            try {
                available.download()
                available.verify()
                val context = currentCoroutineContext()
                synchronized(this@UpdateCoordinator) {
                    context.ensureActive()
                    mutableInstallRequest.value = (available.state.value as? UpdateState.Verified)?.authority
                        ?.let { UpdateInstallRequest(it, mayRequestPermission = true) }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { available.cancelPreparation() }
                throw cancelled
            }
        }.also { updateJob = it; it.start() }
    }

    @Synchronized
    fun cancelUpdate() {
        // Once handed off, cancellation belongs to Android's permission/installer UI.
        if (!preparationCancelable) return
        preparationCancelable = false
        mutableInstallRequest.value = null
        val preparing = updateJob
        preparing?.cancel()
        // Admit no Retry before even non-cooperative verification and cleanup settle.
        updateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                preparing?.join()
                repository.await()?.cancelPreparation()
            }
        }
    }

    @Synchronized
    fun claimInstallRequest(expected: UpdateInstallRequest, needsPermission: Boolean): Boolean {
        if (mutableInstallRequest.value !== expected) return false
        mutableInstallRequest.value = null
        preparationCancelable = false
        if (needsPermission) {
            if (!expected.mayRequestPermission) {
                mutableMessage.value = "Allow Nema to install updates, then try again."
                return false
            }
            permissionRequest = expected.authority
        } else launchInstall(expected.authority)
        return true
    }

    @Synchronized
    fun onInstallPermissionResult(granted: Boolean) {
        val expected = permissionRequest ?: return
        permissionRequest = null // consume once, even when denied or stale
        // Activity results arrive before onResume. Defer the handoff until the
        // Activity is resumed, then recheck permission without opening Settings again.
        if (granted) mutableInstallRequest.value = UpdateInstallRequest(expected, mayRequestPermission = false)
        else mutableMessage.value = "Allow Nema to install updates, then try again."
    }

    @Synchronized
    fun permissionLaunchFailed(expected: VerifiedUpdate) {
        if (permissionRequest !== expected) return
        permissionRequest = null
        mutableMessage.value = "Could not open install permission settings. Try again."
    }

    private fun updatePending(): Boolean = updateJob?.isCompleted == false ||
        mutableInstallRequest.value != null || permissionRequest != null

    private fun launchInstall(expected: VerifiedUpdate) {
        updateJob = scope.launch {
            if (repository.await()?.install(expected) != true) {
                mutableMessage.value = "Could not open installer. Try again."
            }
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
