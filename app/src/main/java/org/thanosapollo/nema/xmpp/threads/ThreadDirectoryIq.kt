package org.thanosapollo.nema.xmpp.threads

import java.io.IOException
import java.util.WeakHashMap
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.IqData
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.IqProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.xml.XmlPullParser

internal class ThreadDirectoryIq(
    val fields: Map<String, String>,
    val rows: List<Map<String, String>> = emptyList(),
    val structurallyValid: Boolean = true,
) : IQ("directory", THREAD_DIRECTORY_NAMESPACE) {
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        fields.forEach { (name, value) -> xml.attribute(name, value) }
        if (rows.isEmpty()) xml.setEmptyElement() else {
            xml.rightAngleBracket()
            rows.forEach { row ->
                xml.halfOpenElement("thread")
                row.forEach { (name, value) -> xml.attribute(name, value) }
                xml.closeEmptyElement()
            }
        }
        return xml
    }
}

/**
 * Consumes malformed payloads without throwing into Smack's connection reader.
 * Smack 4.4.8 can discard preceding foreign IQ payloads before invoking this provider.
 * Client admission covers the selected directory and projected IQ identity/type/error, not raw outer XML.
 * Repeated directory payloads can be detected because they share the same IqData instance.
 */
internal object ThreadDirectoryIqProvider : IqProvider<ThreadDirectoryIq>() {
    private val seen = WeakHashMap<IqData, Boolean>()

    @Synchronized
    fun install() {
        val existing = ProviderManager.getIQProvider("directory", THREAD_DIRECTORY_NAMESPACE)
        check(existing == null || existing === this) { "Unexpected thread directory IQ provider" }
        if (existing == null) ProviderManager.addIQProvider("directory", THREAD_DIRECTORY_NAMESPACE, this)
    }

    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        iqData: IqData,
        xmlEnvironment: XmlEnvironment,
    ): ThreadDirectoryIq {
        var valid = synchronized(seen) { seen.put(iqData, true) == null }
        fun attributes(): Map<String, String> {
            val result = linkedMapOf<String, String>()
            for (index in 0 until parser.attributeCount) {
                val name = parser.getAttributeName(index)
                val value = parser.getAttributeValue(index)
                if (!parser.getAttributeNamespace(index).isNullOrEmpty() ||
                    name.length > 32 || value.length > 1024 || result.size >= 12 || result.put(name, value) != null) valid = false
            }
            return result
        }
        val fields = attributes()
        val rows = mutableListOf<Map<String, String>>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> {
                    if (parser.depth == initialDepth + 1 && parser.name == "thread" &&
                        parser.namespace == THREAD_DIRECTORY_NAMESPACE && rows.size < DIRECTORY_PAGE_SIZE) {
                        rows += attributes()
                    } else valid = false
                }
                XmlPullParser.Event.TEXT_CHARACTERS,
                XmlPullParser.Event.IGNORABLE_WHITESPACE -> if (!parser.text.isNullOrBlank()) valid = false
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) {
                    return ThreadDirectoryIq(fields, rows.toList(), valid)
                }
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("Unclosed thread directory")
                else -> valid = false
            }
        }
    }
}
