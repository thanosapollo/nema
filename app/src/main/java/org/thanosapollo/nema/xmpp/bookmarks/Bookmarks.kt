package org.thanosapollo.nema.xmpp.bookmarks

import org.jxmpp.jid.impl.JidCreate

const val BOOKMARKS2_NODE = "urn:xmpp:bookmarks:1"
const val STORAGE_BOOKMARKS_NAMESPACE = "storage:bookmarks"

data class Bookmark2Item(
    val id: String?,
    val payloadXml: String? = null,
)

data class RoomBookmark(
    val roomJid: String,
    val name: String? = null,
    val nick: String? = null,
    val password: String? = null,
    val autojoin: Boolean = false,
)

fun roomsFromBookmark2Items(items: List<Bookmark2Item>): List<String> =
    parseBookmark2Conferences(items).map(RoomBookmark::roomJid)

fun roomsFromStorageBookmarks(xml: String): List<String> =
    parseStorageBookmarks(xml).map(RoomBookmark::roomJid)

fun mergeBookmarkedRooms(preferred: List<String>, fallback: List<String>): List<String> {
    val rooms = linkedSetOf<String>()
    preferred.forEach(rooms::add)
    fallback.forEach(rooms::add)
    return rooms.toList()
}

fun parseBookmark2Conferences(items: List<Bookmark2Item>): List<RoomBookmark> =
    items.mapNotNull { parseBookmark2Conference(it.id, it.payloadXml) }

fun parseBookmark2Conference(id: String?, payloadXml: String?): RoomBookmark? {
    val roomJid = canonicalRoomJid(id) ?: return null
    val payload = payloadXml.orEmpty()
    return RoomBookmark(
        roomJid = roomJid,
        name = firstAttribute(payload, "name") ?: firstChildText(payload, "name"),
        nick = firstChildText(payload, "nick"),
        password = firstChildText(payload, "password"),
        autojoin = booleanAttribute(payload, "autojoin"),
    )
}

fun parseStorageBookmarks(xml: String): List<RoomBookmark> =
    CONFERENCE_BLOCK.findAll(xml).mapNotNull { match ->
        val block = match.value
        val roomJid = canonicalRoomJid(firstAttribute(block, "jid")) ?: return@mapNotNull null
        RoomBookmark(
            roomJid = roomJid,
            name = firstAttribute(block, "name"),
            nick = firstChildText(block, "nick"),
            password = firstChildText(block, "password"),
            autojoin = booleanAttribute(block, "autojoin"),
        )
    }.toList()

fun mergeRoomBookmarks(preferred: List<RoomBookmark>, fallback: List<RoomBookmark>): List<RoomBookmark> {
    val rooms = linkedMapOf<String, RoomBookmark>()
    preferred.forEach { rooms[it.roomJid] = it }
    fallback.forEach { rooms.putIfAbsent(it.roomJid, it) }
    return rooms.values.toList()
}

fun autojoinRooms(bookmarks: List<RoomBookmark>): List<RoomBookmark> =
    bookmarks.filter(RoomBookmark::autojoin)

fun joinRoomBookmark(
    roomJid: String,
    nick: String?,
    password: String?,
    existing: RoomBookmark?,
): RoomBookmark = RoomBookmark(
    roomJid = roomJid,
    name = existing?.name?.trim()?.takeIf(String::isNotEmpty),
    nick = nick?.trim()?.takeIf(String::isNotEmpty) ?: existing?.nick,
    password = password?.trim()?.takeIf(String::isNotEmpty) ?: existing?.password,
    autojoin = true,
)

fun preferredRoomNick(bookmarkNick: String?, accountBareJid: String): String {
    val nick = bookmarkNick?.trim().orEmpty()
    if (nick.isNotEmpty()) return nick
    val local = accountBareJid.substringBefore('@').trim()
    return local.ifEmpty { "nema" }
}

fun bookmark2Xml(bookmark: RoomBookmark): String {
    val attrs = buildString {
        append("xmlns='").append(BOOKMARKS2_NODE).append("'")
        bookmark.name?.takeIf(String::isNotEmpty)?.let { append(" name='").append(escapeXml(it)).append("'") }
        if (bookmark.autojoin) append(" autojoin='true'")
    }
    val children = buildString {
        bookmark.nick?.takeIf(String::isNotEmpty)?.let {
            append("<nick>").append(escapeXml(it)).append("</nick>")
        }
        bookmark.password?.takeIf(String::isNotEmpty)?.let {
            append("<password>").append(escapeXml(it)).append("</password>")
        }
    }
    return if (children.isEmpty()) {
        "<conference $attrs/>"
    } else {
        "<conference $attrs>$children</conference>"
    }
}

fun canonicalRoomJid(value: String?): String? = runCatching {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return@runCatching null
    val bare = JidCreate.from(raw).asBareJid()
    if (!bare.isEntityBareJid) return@runCatching null
    bare.asEntityBareJidOrThrow().toString()
}.getOrNull()

private fun booleanAttribute(xml: String, name: String): Boolean {
    val value = firstAttribute(xml, name)?.trim()?.lowercase() ?: return false
    return value == "true" || value == "1"
}

private fun firstAttribute(xml: String, name: String): String? =
    Regex("""\b$name=['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::unescapeXml)

private fun firstChildText(xml: String, name: String): String? =
    Regex("""<$name(?:\s[^>]*)?>([^<]*)</$name>""", RegexOption.IGNORE_CASE)
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::unescapeXml)

private val CONFERENCE_BLOCK = Regex("""<conference\b[^>]*(?:/>|>[\s\S]*?</conference>)""", RegexOption.IGNORE_CASE)

private fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun unescapeXml(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
