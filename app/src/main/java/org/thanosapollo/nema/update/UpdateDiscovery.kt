package org.thanosapollo.nema.update

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
const val UPDATE_ENDPOINT = "https://git.thanosapollo.org/nema/releases/latest.json"
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
    data class Failed(
        val message: String,
        override val accepted: AcceptedGeneration?,
    ) : UpdateState
}
fun interface ManifestFetcher { suspend fun fetch(): String }

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
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private var generation = 0L
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    suspend fun checkAutomatic() {
        currentCoroutineContext().ensureActive()
        if (!gate.tryLock()) return
        try {
            currentCoroutineContext().ensureActive()
            val now = clock()
            if (!networkAvailable() || !automaticEligible(now, lastSuccess())) return
            checkLocked(manual = false)
        } finally {
            gate.unlock()
        }
    }

    suspend fun checkManual() {
        currentCoroutineContext().ensureActive()
        if (!gate.tryLock()) return
        try {
            currentCoroutineContext().ensureActive()
            checkLocked(manual = true)
        } finally {
            gate.unlock()
        }
    }

    private suspend fun checkLocked(manual: Boolean) {
        val prior = mutableState.value
        mutableState.value = UpdateState.Checking(prior.accepted)
        try {
            val accepted = UpdateManifest.parse(fetcher.fetch()).accept(installedVersionCode)
            currentCoroutineContext().ensureActive()
            saveSuccess(clock())
            val value = AcceptedGeneration(++generation, accepted)
            mutableState.value = when (accepted) {
                is AcceptedUpdate.Current -> UpdateState.Current(value)
                is AcceptedUpdate.Available -> UpdateState.Available(value)
            }
        } catch (failure: CancellationException) {
            mutableState.value = prior
            throw failure
        } catch (_: Exception) {
            mutableState.value = if (manual) {
                UpdateState.Failed("Could not check for updates. Try again.", prior.accepted)
            } else {
                prior
            }
        }
    }

    private fun automaticEligible(now: Long, last: Long): Boolean =
        last <= 0 || now < last || now - last >= CHECK_INTERVAL_MS
}
