package org.thanosapollo.nema.xmpp

import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

data class XmppElement(
    val name: String,
    val namespace: String?,
    val attributes: Map<String, String> = emptyMap(),
    val text: String? = null,
    val children: List<XmppElement> = emptyList(),
)

object Xep0201 {
    const val CORE_NAMESPACE = "jabber:client"

    fun parse(children: List<XmppElement>): ThreadRef? {
        val coreThreads = children.filter { element ->
            element.name == "thread" &&
                (element.namespace == null || element.namespace == CORE_NAMESPACE)
        }
        if (coreThreads.size != 1) return null

        val element = coreThreads.single()
        if (element.children.isNotEmpty()) return null

        val id = ThreadId.parse(element.text) ?: return null
        val parentId = when (val parent = element.attributes["parent"]) {
            null -> null
            else -> ThreadId.parse(parent) ?: return null
        }
        if (parentId == id) return null
        return ThreadRef(id, parentId)
    }

    fun serialize(thread: ThreadRef): XmppElement = XmppElement(
        name = "thread",
        namespace = CORE_NAMESPACE,
        attributes = thread.parentId?.let { mapOf("parent" to it.value) }.orEmpty(),
        text = thread.id.value,
    )
}
