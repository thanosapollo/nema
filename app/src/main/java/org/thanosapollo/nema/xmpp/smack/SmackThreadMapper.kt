package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.MessageView
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

internal fun MessageView.toThreadRef(): ThreadRef? {
    val threads = getExtensions(Message.Thread::class.java)
    if (threads.size != 1) return null

    val element = threads.single()
    val id = ThreadId.parse(element.thread) ?: return null
    val parentId = when (val parent = element.parent) {
        null -> null
        else -> ThreadId.parse(parent) ?: return null
    }
    if (parentId == id) return null

    return ThreadRef(id, parentId)
}

internal fun MessageBuilder.setThreadRef(thread: ThreadRef): MessageBuilder =
    setThread(thread.id.value, thread.parentId?.value)
