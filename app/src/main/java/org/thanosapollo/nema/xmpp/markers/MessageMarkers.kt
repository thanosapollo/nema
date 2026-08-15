package org.thanosapollo.nema.xmpp.markers

import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager

const val CHAT_MARKERS_NAMESPACE = "urn:xmpp:chat-markers:0"
const val RECEIPTS_NAMESPACE = "urn:xmpp:receipts"

internal const val MARKABLE_ELEMENT = "markable"
internal const val RECEIVED_ELEMENT = "received"
internal const val DISPLAYED_ELEMENT = "displayed"
internal const val ACKNOWLEDGED_ELEMENT = "acknowledged"

internal fun installNemaChatMarkerProviders() {
    listOf(MARKABLE_ELEMENT, RECEIVED_ELEMENT, DISPLAYED_ELEMENT, ACKNOWLEDGED_ELEMENT)
        .forEach { element ->
            ProviderManager.addExtensionProvider(
                element,
                CHAT_MARKERS_NAMESPACE,
                StandardExtensionElementProvider.INSTANCE,
            )
        }
}

internal fun MessageBuilder.addMarkable(): MessageBuilder = addExtension(
    StandardExtensionElement.builder(MARKABLE_ELEMENT, CHAT_MARKERS_NAMESPACE).build(),
)
