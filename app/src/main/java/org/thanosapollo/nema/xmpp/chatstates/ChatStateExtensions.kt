package org.thanosapollo.nema.xmpp.chatstates

import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager

internal val CHAT_STATE_ELEMENTS = listOf("composing", "paused", "active", "inactive", "gone")

internal fun installNemaChatStateProviders() {
    CHAT_STATE_ELEMENTS.forEach { element ->
        ProviderManager.addExtensionProvider(
            element,
            CHAT_STATES_NAMESPACE,
            StandardExtensionElementProvider.INSTANCE,
        )
    }
}

internal fun MessageBuilder.addChatState(activity: ChatActivity): MessageBuilder = addExtension(
    StandardExtensionElement.builder(activity.elementName(), CHAT_STATES_NAMESPACE).build(),
)
