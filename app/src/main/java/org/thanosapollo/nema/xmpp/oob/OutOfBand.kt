package org.thanosapollo.nema.xmpp.oob

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement

const val OOB_NAMESPACE = "jabber:x:oob"

data class OutOfBandShare(
    val url: String,
    val description: String? = null,
) {
    init {
        require(url.isNotEmpty()) { "OOB URL must not be empty" }
    }
}

fun oobExtension(share: OutOfBandShare): StandardExtensionElement {
    val builder = StandardExtensionElement.builder("x", OOB_NAMESPACE)
        .addElement("url", share.url)
    share.description?.takeIf(String::isNotEmpty)?.let { builder.addElement("desc", it) }
    return builder.build()
}

fun Message.oobShare(): OutOfBandShare? {
    val extension = getExtension("x", OOB_NAMESPACE) as? StandardExtensionElement ?: return null
    val url = extension.getFirstElement("url")?.text?.trim().orEmpty()
    if (url.isEmpty()) return null
    return OutOfBandShare(
        url = url,
        description = extension.getFirstElement("desc")?.text?.trim()?.takeIf(String::isNotEmpty),
    )
}
