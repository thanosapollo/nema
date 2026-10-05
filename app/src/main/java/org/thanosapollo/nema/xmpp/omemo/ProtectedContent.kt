package org.thanosapollo.nema.xmpp.omemo

import org.json.JSONArray
import org.json.JSONObject
import org.thanosapollo.nema.xmpp.XmppElement

/** These states describe syntax only, never authentication or decryption. */
enum class ProtectedState(val status: String) {
    UNSUPPORTED_PAYLOAD("Protected message is not supported"),
    UNSUPPORTED_HEADER_ONLY("Protected session content is not supported"),
    REJECTED("Protected content could not be read"),
}

enum class ProtectedRejection { MALFORMED, DUPLICATE, BUDGET, PROVIDER, CARRIER_BUDGET }
enum class ProtectedCarrierKind { LIVE, RECEIVED_CARBON, SENT_CARBON, MAM }

data class ProtectedCarrier(
    val kind: ProtectedCarrierKind,
    val from: String?,
    val to: String?,
    val outerFrom: String? = null,
    val outerTo: String? = null,
    val archiveAuthority: String? = null,
    val resultId: String? = null,
    val archiveScope: String? = null,
)

data class ProtectedContent(
    val protocols: Set<OmemoProtocol>,
    val content: OmemoContent?,
    val rejection: ProtectedRejection?,
    val carriers: List<ProtectedCarrier>,
) {
    val state: ProtectedState get() = when {
        content == null -> ProtectedState.REJECTED
        content.payload == null -> ProtectedState.UNSUPPORTED_HEADER_ONLY
        else -> ProtectedState.UNSUPPORTED_PAYLOAD
    }

    /**
     * Whether two observations retain the same protected evidence. Accepted content must match
     * exactly. Rejected input keeps no ciphertext, so copies agree only on protocols and reason;
     * an accepted observation never matches a rejected one.
     */
    internal fun sameContent(other: ProtectedContent): Boolean = when {
        content != null && other.content != null -> normalized() == other.normalized()
        else -> content == null && other.content == null && rejection != null &&
            rejection == other.rejection && protocols == other.protocols
    }

    private fun normalized() = content?.copy(keys = content.keys.sortedWith(
        compareBy({ it.recipientBareJid.orEmpty() }, { it.device }),
    ))

    internal fun union(other: ProtectedContent): ProtectedContent {
        require(sameContent(other))
        return copy(carriers = (carriers + other.carriers).distinctBy { it.kind }.sortedBy { it.kind.ordinal })
    }
}

/** Versioned deterministic JSON; no runtime objects or truncated accepted ciphertext. */
internal object ProtectedContentCodec {
    const val MAX_ENCODED = 8_388_608
    private const val MAX_ADDRESS = 4096

    fun encode(value: ProtectedContent): String {
        validate(value)
        val json = JSONObject().put("version", 1)
            .put("protocols", JSONArray(value.protocols.sortedBy { it.ordinal }.map { it.name }))
            .put("rejection", value.rejection?.name ?: JSONObject.NULL)
        value.content?.let { c ->
            json.put("content", JSONObject().put("protocol", c.protocol.name).put("sender", c.senderDevice)
                .put("iv", c.legacyIv ?: JSONObject.NULL).put("payload", c.payload ?: JSONObject.NULL)
                .put("keys", JSONArray(c.keys.sortedWith(compareBy({ it.recipientBareJid.orEmpty() }, { it.device })).map { k ->
                    JSONObject().put("jid", k.recipientBareJid ?: JSONObject.NULL).put("device", k.device)
                        .put("exchange", k.keyExchange).put("ciphertext", k.ciphertext)
                })))
        }
        json.put("carriers", JSONArray(value.carriers.sortedBy { it.kind.ordinal }.map { c ->
            JSONObject().put("kind", c.kind.name).put("from", c.from ?: JSONObject.NULL)
                .put("to", c.to ?: JSONObject.NULL).put("outerFrom", c.outerFrom ?: JSONObject.NULL)
                .put("outerTo", c.outerTo ?: JSONObject.NULL).put("archive", c.archiveAuthority ?: JSONObject.NULL)
                .put("result", c.resultId ?: JSONObject.NULL).put("scope", c.archiveScope ?: JSONObject.NULL)
        }))
        return json.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED) }
    }

    fun decode(state: String, raw: String?): ProtectedContent? = runCatching {
        require(raw != null && raw.length <= MAX_ENCODED && raw.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED)
        boundContainers(raw)
        val json = JSONObject(raw)
        json.fields(if (json.has("content")) ROOT_FIELDS + "content" else ROOT_FIELDS)
        require(json.get("version") is Int && json.get("version") == 1)
        val protocols = json.array("protocols", 1, 2).map { rawProtocol ->
            OmemoProtocol.valueOf(rawProtocol.string(16))
        }
        require(protocols.distinct().size == protocols.size)
        val content = if (json.has("content")) decodeContent(json.get("content").objectValue()) else null
        val rejection = json.nullableString("rejection", 32)?.let(ProtectedRejection::valueOf)
        val carriers = json.array("carriers", 1, 4).map { decodeCarrier(it.objectValue()) }
        ProtectedContent(protocols.toSet(), content, rejection, carriers).also {
            validate(it)
            require(it.state.name == state)
            // Android's JSON parser overwrites duplicate names and accepts non-JSON syntax.
            // Only the exact canonical record written by this version is durable evidence.
            require(encode(it) == raw)
        }
    }.getOrNull()

    private fun decodeContent(json: JSONObject): OmemoContent {
        json.fields(setOf("protocol", "sender", "iv", "payload", "keys"))
        val keys = json.array("keys", 1, 512).map { value ->
            val key = value.objectValue()
            key.fields(setOf("jid", "device", "exchange", "ciphertext"))
            OmemoRecipientKey(key.nullableString("jid", 1024), key.device("device"),
                key.get("exchange").let { require(it is Boolean); it }, key.get("ciphertext").string(1_048_576))
        }
        return OmemoContent(OmemoProtocol.valueOf(json.get("protocol").string(16)), json.device("sender"),
            keys, json.nullableString("iv", 1_048_576), json.nullableString("payload", 1_048_576))
    }

    private fun decodeCarrier(json: JSONObject): ProtectedCarrier {
        json.fields(setOf("kind", "from", "to", "outerFrom", "outerTo", "archive", "result", "scope"))
        return ProtectedCarrier(ProtectedCarrierKind.valueOf(json.get("kind").string(32)),
            json.nullableString("from", MAX_ADDRESS), json.nullableString("to", MAX_ADDRESS),
            json.nullableString("outerFrom", MAX_ADDRESS), json.nullableString("outerTo", MAX_ADDRESS),
            json.nullableString("archive", MAX_ADDRESS), json.nullableString("result", MAX_ADDRESS),
            json.nullableString("scope", MAX_ADDRESS))
    }

    private fun JSONObject.fields(expected: Set<String>) {
        require(keys().asSequence().toSet() == expected)
    }

    private fun Any.objectValue(): JSONObject = this as? JSONObject ?: error("Expected object")
    private fun Any.string(max: Int): String {
        require(this is String && length <= max)
        return this
    }

    private fun JSONObject.nullableString(key: String, max: Int): String? =
        get(key).let { if (it === JSONObject.NULL) null else it.string(max) }

    private fun JSONObject.device(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 1L..Int.MAX_VALUE.toLong()) }
    }

    private fun JSONObject.array(key: String, min: Int, max: Int): List<Any> {
        val array = get(key) as? JSONArray ?: error("Expected array")
        require(array.length() in min..max)
        return (0 until array.length()).map(array::get)
    }

    // Bound native parser recursion and allocations before asking it to decode containers.
    // Exact grammar and canonical spelling are checked by the parser and round trip above.
    private fun boundContainers(raw: String) {
        val commas = IntArray(4)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { require(depth < commas.size); commas[depth++] = 0 }
                '}', ']' -> { require(depth > 0); depth-- }
                ',' -> { require(depth > 0); require(++commas[depth - 1] < 512) }
                ';', '=', '\'', '/' -> error("Non-JSON delimiter")
            }
        }
        require(depth == 0 && !quoted)
    }

    private val ROOT_FIELDS = setOf("version", "protocols", "rejection", "carriers")

    private fun validate(value: ProtectedContent) {
        require(value.protocols.isNotEmpty())
        require((value.content == null) == (value.rejection != null))
        require(value.carriers.size in 1..4 && value.carriers.distinctBy { it.kind }.size == value.carriers.size)
        value.carriers.forEach { c ->
            require(listOf(c.from, c.to, c.outerFrom, c.outerTo, c.archiveAuthority, c.resultId, c.archiveScope)
                .all { it == null || it.length <= MAX_ADDRESS })
            if (value.rejection != ProtectedRejection.CARRIER_BUDGET) {
                require(!c.from.isNullOrEmpty())
                when (c.kind) {
                    ProtectedCarrierKind.MAM -> require(!c.archiveAuthority.isNullOrEmpty() &&
                        !c.resultId.isNullOrEmpty() && !c.archiveScope.isNullOrEmpty())
                    ProtectedCarrierKind.RECEIVED_CARBON, ProtectedCarrierKind.SENT_CARBON ->
                        require(!c.outerFrom.isNullOrEmpty())
                    ProtectedCarrierKind.LIVE -> Unit
                }
            }
        }
        value.content?.let { c ->
            require(value.protocols == setOf(c.protocol))
            val inspected = OmemoContentCodec.inspect(listOf(c.element())) as? OmemoInspection.Unsupported
            require(inspected?.content == c)
        }
    }
}

internal fun OmemoContent.element(): XmppElement {
    fun key(k: OmemoRecipientKey) = XmppElement("key", protocol.namespace,
        mapOf("rid" to k.device.toString()) + if (k.keyExchange) mapOf(
            (if (protocol == OmemoProtocol.LEGACY) "prekey" else "kex") to "1") else emptyMap(), k.ciphertext)
    val children = if (protocol == OmemoProtocol.LEGACY) keys.map(::key) + XmppElement("iv", protocol.namespace, text = legacyIv)
    else keys.groupBy { it.recipientBareJid }.map { (jid, keys) ->
        XmppElement("keys", protocol.namespace, mapOf("jid" to requireNotNull(jid)), children = keys.map(::key))
    }
    return XmppElement("encrypted", protocol.namespace, children = listOf(
        XmppElement("header", protocol.namespace, mapOf("sid" to senderDevice.toString()), children = children),
    ) + listOfNotNull(payload?.let { XmppElement("payload", protocol.namespace, text = it) }))
}

fun protectedStatus(state: String): String? = if (state == "NONE") null else
    ProtectedState.entries.firstOrNull { it.name == state }?.status ?: ProtectedState.REJECTED.status
