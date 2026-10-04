package org.thanosapollo.nema.xmpp.omemo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.xmpp.smack.ProtectedFixtures
import org.thanosapollo.nema.xmpp.smack.SmackAndroid
import org.thanosapollo.nema.xmpp.smack.installNemaOmemoProviders

internal object MalformedProtectedRecords {
    fun from(canonical: String): Map<String, String> = buildMap {
        fun mutation(name: String, change: (JSONObject) -> Unit) {
            put(name, JSONObject(canonical).also(change).toString())
        }
        for (field in listOf("version", "protocols", "rejection", "carriers", "content")) {
            mutation("missing-$field") { it.remove(field) }
        }
        for (version in listOf<Any>("1", true, JSONObject.NULL, 2)) mutation("version-$version") { it.put("version", version) }
        mutation("extra-root") { it.put("ignored", "value") }
        mutation("duplicate-protocol") { it.getJSONArray("protocols").put("LEGACY") }
        mutation("null-content") { it.put("content", JSONObject.NULL) }
        for (field in listOf("protocol", "sender", "iv", "payload", "keys")) {
            mutation("missing-content-$field") { it.getJSONObject("content").remove(field) }
        }
        for (number in listOf<Any>("1", 1.5, true, JSONObject.NULL, 0, 2147483648L)) {
            mutation("sender-$number") { it.getJSONObject("content").put("sender", number) }
        }
        mutation("extra-content") { it.getJSONObject("content").put("ignored", 1) }
        mutation("bad-payload") { it.getJSONObject("content").put("payload", "invalid") }
        mutation("numeric-payload") { it.getJSONObject("content").put("payload", 1) }
        mutation("empty-keys") { it.getJSONObject("content").put("keys", org.json.JSONArray()) }
        for (field in listOf("jid", "device", "exchange", "ciphertext")) {
            mutation("missing-key-$field") { it.getJSONObject("content").getJSONArray("keys").getJSONObject(0).remove(field) }
        }
        for (number in listOf<Any>("2", 2.5, true, JSONObject.NULL, 0, 2147483648L)) {
            mutation("device-$number") { it.getJSONObject("content").getJSONArray("keys").getJSONObject(0).put("device", number) }
        }
        mutation("numeric-carrier") { it.getJSONArray("carriers").getJSONObject(0).put("from", 1) }
        mutation("string-boolean") { it.getJSONObject("content").getJSONArray("keys").getJSONObject(0).put("exchange", "false") }
        mutation("extra-key") { it.getJSONObject("content").getJSONArray("keys").getJSONObject(0).put("ignored", 1) }
        mutation("null-carrier") { it.getJSONArray("carriers").put(0, JSONObject.NULL) }
        mutation("extra-carrier") { it.getJSONArray("carriers").getJSONObject(0).put("ignored", 1) }
        for (field in listOf("kind", "from", "to", "outerFrom", "outerTo", "archive", "result", "scope")) {
            mutation("missing-carrier-$field") { it.getJSONArray("carriers").getJSONObject(0).remove(field) }
        }
        mutation("carrier-too-long") { it.getJSONArray("carriers").getJSONObject(0).put("from", "a".repeat(4097)) }
        put("duplicate-version", canonical.replace("\"version\":1", "\"version\":2,\"version\":1"))
        put("duplicate-payload", canonical.replace("\"payload\":\"AQID\"", "\"payload\":\"BAUG\",\"payload\":\"AQID\""))
        put("semicolon-separators", canonical.replace(",", ";"))
        put("trailing-object", canonical + "{}")
        put("noncanonical-whitespace", " $canonical")
        put("decimal-version", canonical.replace("\"version\":1", "\"version\":1.0"))
        put("noncanonical-number", canonical.replace("\"version\":1", "\"version\":1e0"))
        put("unbounded-depth", "[".repeat(50) + canonical + "]".repeat(50))
        put("unbounded-list", canonical.replace("\"protocols\":[", "\"protocols\":[" + "\"LEGACY\",".repeat(513)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectedContentCodecTest {
    @Before fun initialize() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaOmemoProviders()
    }

    @Test fun exactCanonicalEvidenceRejectsLossyOrCoercibleStoredValues() {
        val evidence = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).protection!!
        val canonical = ProtectedContentCodec.encode(evidence)
        assertEquals(evidence, ProtectedContentCodec.decode(evidence.state.name, canonical))
        MalformedProtectedRecords.from(canonical).forEach { (name, raw) ->
            assertNull(name, ProtectedContentCodec.decode(evidence.state.name, raw))
        }
        assertNull(ProtectedContentCodec.decode("NONE", canonical))
        assertNull(ProtectedContentCodec.decode("UNSUPPORTED_HEADER_ONLY", canonical))
        assertNull(ProtectedContentCodec.decode(evidence.state.name, null))
        assertNull(ProtectedContentCodec.decode(evidence.state.name, " ".repeat(ProtectedContentCodec.MAX_ENCODED + 1)))
    }

    @Test fun nativeAndroidDuplicateOverwriteCannotBecomeCompleteEvidence() {
        val raw = "{\"version\":2,\"version\":1}"
        assertEquals(1, JSONObject(raw).get("version"))
        val evidence = ProtectedFixtures.envelope(OmemoProtocol.LEGACY).protection!!
        val duplicated = ProtectedContentCodec.encode(evidence).replace("\"version\":1", "\"version\":2,\"version\":1")
        assertNull(ProtectedContentCodec.decode(evidence.state.name, duplicated))
    }
}
