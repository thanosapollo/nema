package org.thanosapollo.nema.update

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
const val UPDATE_ENDPOINT = "https://git.thanosapollo.org/nema/releases/latest.json"
internal const val MAX_APK_BYTES = 64L * 1024 * 1024
internal const val PART_FILE_NAME = "candidate.apk.part"
internal const val CANDIDATE_FILE_NAME = "candidate.apk"
private const val MAX_MANIFEST_BYTES = 16 * 1024
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
data class UpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val signerSha256: String,
    val sourceCommit: String,
) {
    fun accept(installedVersionCode: Long): AcceptedUpdate =
        if (versionCode > installedVersionCode) AcceptedUpdate.Available(this)
        else AcceptedUpdate.Current(this)

    companion object {
        private val keys = setOf("schemaVersion", "packageName", "versionCode", "versionName",
            "apkUrl", "size", "sha256", "signerSha256", "sourceCommit")
        private val hash = Regex("[0-9a-f]{64}")
        private val commit = Regex("[0-9a-f]{40}")
        private val integer = Regex("0|[1-9][0-9]*")
        private val pathSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        fun parse(text: String): UpdateManifest = try {
            JsonReader(StringReader(text)).use(::readManifest)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Invalid update manifest", failure)
        }

        private fun readManifest(reader: JsonReader): UpdateManifest {
            reader.isLenient = false
            val values = mutableMapOf<String, Any>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name in keys && name !in values)
                values[name] = when (name) {
                    "schemaVersion", "versionCode", "size" -> readPositiveInteger(reader)
                    else -> readString(reader)
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && values.keys == keys)
            require(values.getValue("schemaVersion") == 1L)
            require(values.getValue("packageName") == "org.thanosapollo.nema")
            val versionName = values.getValue("versionName") as String
            val apkUrl = values.getValue("apkUrl") as String
            val sha256 = values.getValue("sha256") as String
            val signer = values.getValue("signerSha256") as String
            val source = values.getValue("sourceCommit") as String
            require(versionName.isNotBlank())
            requireReleaseUrl(apkUrl)
            require(hash.matches(sha256) && hash.matches(signer) && commit.matches(source))
            return UpdateManifest(1, "org.thanosapollo.nema",
                values.getValue("versionCode") as Long, versionName, apkUrl,
                values.getValue("size") as Long, sha256, signer, source)
        }

        private fun readPositiveInteger(reader: JsonReader): Long {
            require(reader.peek() == JsonToken.NUMBER)
            val literal = reader.nextString()
            require(integer.matches(literal))
            return literal.toLong().also { require(it > 0) }
        }

        private fun readString(reader: JsonReader): String {
            require(reader.peek() == JsonToken.STRING)
            return reader.nextString()
        }

        private fun requireReleaseUrl(value: String) {
            val uri = URI(value)
            require(uri.scheme == "https" && uri.host == "git.thanosapollo.org")
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawAuthority == "git.thanosapollo.org" && uri.port == -1)
            require(uri.normalize() == uri)
            require('%' !in uri.rawPath && uri.rawPath == uri.path)
            val segments = uri.rawPath.split('/')
            require(segments.size >= 4 && segments[0].isEmpty())
            require(segments.drop(1).all(pathSegment::matches))
            require(segments[1] == "nema" && segments[2] == "releases")
            val releaseSegments = segments.drop(3)
            require(releaseSegments.dropLast(1).none { it.endsWith(".apk") })
            require(releaseSegments.last().endsWith(".apk"))
            require(releaseSegments.last().removeSuffix(".apk").isNotEmpty())
        }
    }
}
sealed interface AcceptedUpdate {
    val manifest: UpdateManifest
    data class Current(override val manifest: UpdateManifest) : AcceptedUpdate
    data class Available(override val manifest: UpdateManifest) : AcceptedUpdate
}
data class AcceptedGeneration(val generation: Long, val value: AcceptedUpdate)

sealed interface UpdateState {
    val accepted: AcceptedGeneration?

    data object Idle : UpdateState {
        override val accepted: AcceptedGeneration? = null
    }
    data class Checking(override val accepted: AcceptedGeneration?) : UpdateState
    data class Current(override val accepted: AcceptedGeneration) : UpdateState
    data class Available(override val accepted: AcceptedGeneration) : UpdateState
    data class Downloading(override val accepted: AcceptedGeneration) : UpdateState
    data class Downloaded(
        override val accepted: AcceptedGeneration,
        val artifact: BoundUpdateArtifact,
    ) : UpdateState
    data class Verified(
        override val accepted: AcceptedGeneration,
        val authority: VerifiedUpdate,
    ) : UpdateState
    data class Installing(
        override val accepted: AcceptedGeneration,
        val lease: InstallHandoffLease,
    ) : UpdateState
    data class Failed(
        val message: String,
        override val accepted: AcceptedGeneration?,
    ) : UpdateState
}
data class BoundUpdateArtifact(
    val accepted: AcceptedGeneration,
    val file: File,
    val size: Long,
    val sha256: String,
)
data class VerifiedUpdate(
    val artifact: BoundUpdateArtifact,
    val installed: PackageFacts,
    val candidate: PackageFacts,
)
data class InstallHandoffLease(val prior: UpdateState.Verified) {
    val verified: VerifiedUpdate get() = prior.authority
}
fun interface ManifestFetcher { suspend fun fetch(): String }

fun interface ApkDownloadEffect {
    suspend fun download(manifest: UpdateManifest, part: File)
}

class HttpsApkDownloadEffect(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : ApkDownloadEffect {
    override suspend fun download(manifest: UpdateManifest, part: File) = withContext(Dispatchers.IO) {
        require(manifest.size <= MAX_APK_BYTES)
        val expectedUrl = URL(manifest.apkUrl)
        val connection = open(expectedUrl)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            require(connection.url.toString() == manifest.apkUrl)
            check(connection.responseCode in 200..299)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            connection.inputStream.use { input ->
                FileOutputStream(part).use { file ->
                    DigestOutputStream(file, digest).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            currentCoroutineContext().ensureActive()
                            if (count == -1) break
                            check(count > 0)
                            size += count
                            check(size <= manifest.size && size <= MAX_APK_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                        file.fd.sync()
                    }
                }
            }
            check(size == manifest.size)
            check(digest.digest().hex() == manifest.sha256)
        } catch (failure: IOException) {
            // A cancelled blocked read may finish by timing out instead of returning.
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            connection.disconnect()
        }
    }
}

class HttpsManifestFetcher(
    private val open: () -> HttpsURLConnection = {
        URL(UPDATE_ENDPOINT).openConnection() as HttpsURLConnection
    },
) : ManifestFetcher {
    override suspend fun fetch(): String = withContext(Dispatchers.IO) {
        val connection = open()
        try {
            require(connection.url.toString() == UPDATE_ENDPOINT)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            check(connection.responseCode in 200..299)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= MAX_MANIFEST_BYTES) {
                    val remaining = MAX_MANIFEST_BYTES + 1 - output.size()
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count == -1) break
                    check(count > 0)
                    output.write(buffer, 0, count)
                }
                check(output.size() <= MAX_MANIFEST_BYTES)
                output.toByteArray()
            }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            connection.disconnect()
        }
    }
}

class UpdateRepository(
    private val installedVersionCode: Long,
    private val fetcher: ManifestFetcher,
    private val clock: () -> Long,
    private val lastSuccess: () -> Long,
    private val saveSuccess: (Long) -> Unit,
    private val networkAvailable: () -> Boolean,
    private val updateDirectory: File? = null,
    private val downloadEffect: ApkDownloadEffect? = null,
    private val deleteFile: (File) -> Boolean = File::delete,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packageFacts: PackageFactsAdapter? = null,
    private val apiLevel: Int = android.os.Build.VERSION.SDK_INT,
    private val installLauncher: (BoundUpdateArtifact, InstallHandoffLease) -> Boolean = { _, _ -> false },
) {
    constructor(
        installedVersionCode: Long,
        fetcher: ManifestFetcher,
        clock: () -> Long,
        lastSuccess: () -> Long,
        saveSuccess: (Long) -> Unit,
        networkAvailable: () -> Boolean,
        updateDirectory: File?,
        downloadEffect: ApkDownloadEffect?,
        deleteFile: (File) -> Boolean,
        blockingDispatcher: CoroutineDispatcher,
    ) : this(installedVersionCode, fetcher, clock, lastSuccess, saveSuccess, networkAvailable,
        updateDirectory, downloadEffect, deleteFile, blockingDispatcher, null)

    private val gate = Mutex()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private var generation = 0L
    private var downloading = false
    private var artifact: BoundUpdateArtifact? = null
    private var verified: VerifiedUpdate? = null
    private var lease: InstallHandoffLease? = null
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    init {
        pruneUpdateFiles()
    }

    suspend fun download() {
        val directory = updateDirectory ?: return
        val effect = downloadEffect ?: return
        currentCoroutineContext().ensureActive()
        val callerJob = requireNotNull(currentCoroutineContext()[Job])
        if (!gate.tryLock()) return
        val capture = try {
            if (lease != null) return
            val accepted = mutableState.value.accepted ?: return
            val available = accepted.value as? AcceptedUpdate.Available ?: return
            if (artifact != null) return
            if (downloading) return
            if (available.manifest.size > MAX_APK_BYTES) {
                mutableState.value = UpdateState.Failed("Could not download update. Try again.", accepted)
                return
            }
            try {
                pruneUpdateFiles()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableState.value = UpdateState.Failed("Could not download update. Try again.", accepted)
                return
            }
            downloading = true
            val prior = mutableState.value
            mutableState.value = UpdateState.Downloading(accepted)
            DownloadCapture(accepted, prior, callerJob)
        } finally {
            gate.unlock()
        }
        val part = directory.resolve(PART_FILE_NAME)
        val candidate = directory.resolve(CANDIDATE_FILE_NAME)
        var verified: BoundUpdateArtifact? = null
        try {
            effect.download(capture.accepted.value.manifest, part)
            currentCoroutineContext().ensureActive()
            val facts = withContext(blockingDispatcher) {
                val verifiedFacts = verifyPart(part, capture.accepted.value.manifest)
                moveAtomically(part, candidate)
                verifiedFacts
            }
            verified = BoundUpdateArtifact(capture.accepted, candidate, facts.first, facts.second)
        } catch (failure: CancellationException) {
            withContext(NonCancellable) { settleDownload(capture, null, part, candidate) }
            throw failure
        } catch (_: Exception) {
            withContext(NonCancellable) { settleDownload(capture, null, part, candidate, failed = true) }
            return
        }
        withContext(NonCancellable) { settleDownload(capture, verified, part, candidate) }
    }

    internal fun boundArtifact(): BoundUpdateArtifact? = artifact

    internal suspend fun cancelPreparation() = withContext(blockingDispatcher) {
        gate.withLock {
            if (lease == null) artifact?.let { rejectCandidate(it, cancelled = true) }
        }
    }

    suspend fun verify() {
        val adapter = packageFacts ?: return
        currentCoroutineContext().ensureActive()
        if (!gate.tryLock()) return
        val capture = try {
            if (lease != null) return
            if (verified != null) return
            val current = artifact ?: return
            if (mutableState.value.accepted != current.accepted) return
            if (current.accepted.value !is AcceptedUpdate.Available) return
            current
        } finally {
            gate.unlock()
        }
        val authority = try {
            withContext(blockingDispatcher) { authorize(capture, adapter) }
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                gate.withLock {
                    if (artifact == capture && verified == null && lease == null) {
                        rejectCandidate(capture, cancelled = true)
                    }
                }
            }
            throw failure
        } catch (_: Exception) {
            null
        }
        gate.withLock {
            val sameTruth = artifact == capture &&
                mutableState.value.accepted == capture.accepted && lease == null
            if (authority != null && sameTruth) {
                verified = authority
                mutableState.value = UpdateState.Verified(capture.accepted, authority)
            } else if (authority == null && sameTruth) {
                rejectCandidate(capture)
            }
        }
    }

    suspend fun install(expected: VerifiedUpdate? = null): Boolean {
        val adapter = packageFacts ?: return false
        currentCoroutineContext().ensureActive()
        return withContext(blockingDispatcher) {
            if (!gate.tryLock()) return@withContext false
            try {
                currentCoroutineContext().ensureActive()
                if (lease != null) return@withContext false
                val authority = verified ?: return@withContext false
                if (expected != null && authority !== expected) return@withContext false
                val prior = mutableState.value as? UpdateState.Verified ?: return@withContext false
                if (prior.authority !== authority) return@withContext false
                if (!finalAuthorityMatches(authority, adapter)) {
                    clearInstallAuthority(authority.artifact)
                    return@withContext false
                }
                currentCoroutineContext().ensureActive()
                val handoff = InstallHandoffLease(prior)
                lease = handoff
                mutableState.value = UpdateState.Installing(authority.artifact.accepted, handoff)
                try {
                    if (installLauncher(authority.artifact, handoff)) return@withContext true
                    restoreVerified(handoff)
                    false
                } catch (failure: CancellationException) {
                    restoreVerified(handoff)
                    throw failure
                } catch (_: Exception) {
                    restoreVerified(handoff)
                    false
                }
            } finally {
                gate.unlock()
            }
        }
    }

    suspend fun settleInstallOnResume(expectedLease: InstallHandoffLease) {
        val adapter = packageFacts ?: return
        withContext(blockingDispatcher) {
            gate.withLock {
                if (lease !== expectedLease) return@withLock
                val authority = expectedLease.verified
                val installed = safeInstalled(adapter)
                val candidate = safeArchive(adapter, authority.artifact.file)
                val upgraded = installed != null && candidate != null && installed.packageName == candidate.packageName &&
                    installed.versionCode == candidate.versionCode && installed.currentSigners == candidate.currentSigners
                val exact = installed == authority.installed && candidate == authority.candidate &&
                    artifact == authority.artifact && fileMatches(authority.artifact)
                if (!upgraded && exact) {
                    restoreVerified(expectedLease)
                } else {
                    clearInstallAuthority(authority.artifact)
                }
            }
        }
    }

    suspend fun checkAutomatic() {
        currentCoroutineContext().ensureActive()
        if (!gate.tryLock()) return
        try {
            // Foreground discovery must not revoke an explicit user's update.
            if (lease != null || downloading || artifact != null) return
            currentCoroutineContext().ensureActive()
            val now = clock()
            if (!networkAvailable()) return
            // The persisted timestamp is not a discovery result. Rebuild process-local
            // authority once after restart, including saved-state Activity restoration.
            if (mutableState.value.accepted != null && !automaticEligible(now, lastSuccess())) return
            checkLocked(manual = false)
        } finally {
            gate.unlock()
        }
    }

    suspend fun checkManual() {
        currentCoroutineContext().ensureActive()
        if (!gate.tryLock()) return
        try {
            if (lease != null) return
            currentCoroutineContext().ensureActive()
            checkLocked(manual = true)
        } finally {
            gate.unlock()
        }
    }

    private suspend fun checkLocked(manual: Boolean) {
        val prior = mutableState.value
        mutableState.value = UpdateState.Checking(prior.accepted)
        val (accepted, successTime) = try {
            val accepted = UpdateManifest.parse(fetcher.fetch()).accept(installedVersionCode)
            currentCoroutineContext().ensureActive()
            val successTime = clock()
            currentCoroutineContext().ensureActive()
            revokeArtifact()
            accepted to successTime
        } catch (failure: CancellationException) {
            mutableState.value = prior
            throw failure
        } catch (_: Exception) {
            mutableState.value = if (verified != null) {
                prior
            } else if (manual) {
                UpdateState.Failed("Could not check for updates. Try again.", prior.accepted)
            } else {
                prior
            }
            return
        }
        val saveFailure = try {
            saveSuccess(successTime)
            null
        } catch (failure: Throwable) {
            failure
        }
        val value = AcceptedGeneration(++generation, accepted)
        mutableState.value = when (accepted) {
            is AcceptedUpdate.Current -> UpdateState.Current(value)
            is AcceptedUpdate.Available -> UpdateState.Available(value)
        }
        when (saveFailure) {
            null -> Unit
            is CancellationException -> throw saveFailure
            is Error -> throw saveFailure
            is Exception -> Unit
            else -> throw saveFailure
        }
    }

    private fun automaticEligible(now: Long, last: Long): Boolean =
        last <= 0 || now < last || now - last >= CHECK_INTERVAL_MS

    private suspend fun settleDownload(
        capture: DownloadCapture,
        verified: BoundUpdateArtifact?,
        part: File,
        candidate: File,
        failed: Boolean = false,
    ) = gate.withLock {
        downloading = false
        val current = mutableState.value.accepted
        val stillAccepted = current?.generation == capture.accepted.generation &&
            current.value.manifest == capture.accepted.value.manifest
        if (verified != null && capture.callerJob.isActive && stillAccepted) {
            artifact = verified
            mutableState.value = UpdateState.Downloaded(current, verified)
        } else {
            val cleanupFailed = listOf(part, candidate).fold(false) { failed, file ->
                try {
                    checkedDelete(file)
                    failed
                } catch (_: Exception) {
                    true
                }
            }
            artifact = null
            mutableState.value = if (cleanupFailed) {
                UpdateState.Failed("Could not download update. Try again.", current)
            } else if (mutableState.value == UpdateState.Downloading(capture.accepted)) {
                if (failed) UpdateState.Failed("Could not download update. Try again.", current)
                else capture.prior
            } else {
                mutableState.value
            }
        }
    }

    private fun verifyPart(part: File, manifest: UpdateManifest): Pair<Long, String> {
        var size = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                size += count
                check(size <= manifest.size && size <= MAX_APK_BYTES)
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().hex()
        check(size == manifest.size && hash == manifest.sha256)
        return size to hash
    }

    private fun moveAtomically(part: File, candidate: File) {
        try {
            Files.move(part.toPath(), candidate.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Private update directory does not support atomic publication", failure)
        }
    }

    private fun revokeArtifact() {
        artifact?.file?.let(::checkedDelete)
        artifact = null
        verified = null
        lease = null
    }

    private fun pruneUpdateFiles() {
        updateDirectory?.let { directory ->
            directory.mkdirs()
            checkedDelete(directory.resolve(PART_FILE_NAME))
            checkedDelete(directory.resolve(CANDIDATE_FILE_NAME))
        }
        artifact = null
        verified = null
        lease = null
    }

    private fun checkedDelete(file: File) {
        if (!file.exists()) return
        check(deleteFile(file) && !file.exists())
    }

    private fun authorize(bound: BoundUpdateArtifact, adapter: PackageFactsAdapter): VerifiedUpdate? {
        if (!fileMatches(bound)) return null
        val installed = safeInstalled(adapter) ?: return null
        val candidate = safeArchive(adapter, bound.file) ?: return null
        val manifest = (bound.accepted.value as? AcceptedUpdate.Available)?.manifest ?: return null
        if (!packageUpgradeAuthorized(apiLevel, installed, candidate, manifest.versionCode)) return null
        return VerifiedUpdate(bound, installed, candidate)
    }

    private fun finalAuthorityMatches(authority: VerifiedUpdate, adapter: PackageFactsAdapter): Boolean {
        if (artifact != authority.artifact || mutableState.value.accepted != authority.artifact.accepted) return false
        if (!fileMatches(authority.artifact)) return false
        val installed = safeInstalled(adapter) ?: return false
        val candidate = safeArchive(adapter, authority.artifact.file) ?: return false
        val manifest = (authority.artifact.accepted.value as? AcceptedUpdate.Available)?.manifest ?: return false
        return installed == authority.installed && candidate == authority.candidate &&
            packageUpgradeAuthorized(apiLevel, installed, candidate, manifest.versionCode)
    }

    private fun fileMatches(bound: BoundUpdateArtifact): Boolean = try {
        verifyPart(bound.file, bound.accepted.value.manifest) == (bound.size to bound.sha256)
    } catch (_: Exception) {
        false
    }

    private fun safeInstalled(adapter: PackageFactsAdapter): PackageFacts? = try {
        adapter.installed()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun safeArchive(adapter: PackageFactsAdapter, file: File): PackageFacts? = try {
        adapter.archive(file)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun restoreVerified(handoff: InstallHandoffLease) {
        lease = null
        verified = handoff.verified
        mutableState.value = handoff.prior
    }

    private fun rejectCandidate(bound: BoundUpdateArtifact, cancelled: Boolean = false) {
        if (artifact != bound) return
        try {
            checkedDelete(bound.file)
            artifact = null
            verified = null
            mutableState.value = if (cancelled) UpdateState.Available(bound.accepted)
                else UpdateState.Failed("Could not verify update. Try again.", bound.accepted)
        } catch (_: Exception) {
            artifact = null
            verified = null
            mutableState.value = UpdateState.Failed("Could not verify update. Try again.", bound.accepted)
        }
    }

    private fun clearInstallAuthority(bound: BoundUpdateArtifact) {
        lease = null
        verified = null
        try {
            checkedDelete(bound.file)
            artifact = null
            mutableState.value = UpdateState.Available(bound.accepted)
        } catch (_: Exception) {
            artifact = null
            mutableState.value = UpdateState.Failed("Could not clear update. Try again.", bound.accepted)
        }
    }

    private data class DownloadCapture(
        val accepted: AcceptedGeneration,
        val prior: UpdateState,
        val callerJob: Job,
    )
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
