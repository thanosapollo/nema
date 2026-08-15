package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smack.packet.StreamOpen
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.util.ParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.delay.provider.DelayInformationProvider
import org.jivesoftware.smackx.forward.packet.Forwarded
import org.jivesoftware.smackx.mam.element.MamElements
import org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension
import org.jivesoftware.smackx.mam.provider.MamResultProvider

private val redactedMamSentinel: Message = StanzaBuilder.buildMessage("nema-redacted-mam-sentinel").build()
private val mamResultProviderLock = Any()

internal class NemaMamResultExtension(
    queryId: String?,
    id: String,
    val actualMessage: Message?,
    delay: DelayInformation?,
) : MamResultExtension(
    queryId,
    id,
    Forwarded(actualMessage ?: redactedMamSentinel, delay),
)

internal class MalformedMamResultException(message: String) : IOException(message)

internal object NemaMamResultProvider : ExtensionElementProvider<NemaMamResultExtension>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): NemaMamResultExtension {
        var malformedReason: String? = null
        if (parser.name != MamResultExtension.ELEMENT || parser.namespace != MamElements.NAMESPACE) {
            malformedReason = "Unexpected MAM result element"
        }
        val id = parser.getAttributeValue("", "id")
        if (id.isNullOrEmpty()) malformedReason = malformedReason ?: "MAM result ID is missing"
        val queryId = parser.getAttributeValue("", "queryid")
        if (queryId != null && queryId.isEmpty()) malformedReason = malformedReason ?: "MAM query ID is empty"

        var forwarded: ParsedForwarded? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> {
                    val depth = parser.depth
                    if (depth == initialDepth + 1 &&
                        parser.name == Forwarded.ELEMENT &&
                        parser.namespace == Forwarded.NAMESPACE
                    ) {
                        if (forwarded == null) {
                            forwarded = parseForwarded(parser, xmlEnvironment)
                        } else {
                            malformedReason = malformedReason ?: "MAM result contains duplicate forwarded elements"
                            ParserUtils.forwardToEndTagOfDepth(parser, depth)
                        }
                    } else if (depth == initialDepth + 1) {
                        ParserUtils.forwardToEndTagOfDepth(parser, depth)
                    }
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) break
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("MAM result ended before its closing element")
                else -> Unit
            }
        }
        val parsed = forwarded
        if (parsed == null) malformedReason = malformedReason ?: "MAM result omitted forwarded element"
        malformedReason = malformedReason ?: parsed?.malformedReason
        malformedReason?.let { throw MalformedMamResultException(it) }
        return NemaMamResultExtension(queryId, checkNotNull(id), checkNotNull(parsed).message, parsed.delay)
    }

    private fun parseForwarded(
        parser: XmlPullParser,
        xmlEnvironment: XmlEnvironment,
    ): ParsedForwarded {
        val initialDepth = parser.depth
        var delay: DelayInformation? = null
        var delaySeen = false
        var message: Message? = null
        var messageSeen = false
        var malformedReason: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> {
                    val depth = parser.depth
                    when {
                        depth != initialDepth + 1 -> Unit
                        parser.name == DelayInformation.ELEMENT &&
                            parser.namespace == DelayInformation.NAMESPACE -> {
                            if (delaySeen) {
                                malformedReason = malformedReason ?: "Forwarded result contains duplicate delay elements"
                                ParserUtils.forwardToEndTagOfDepth(parser, depth)
                            } else {
                                delaySeen = true
                                delay = DelayInformationProvider.INSTANCE.parse(
                                    parser,
                                    parser.depth,
                                    xmlEnvironment,
                                )
                            }
                        }
                        parser.name == Message.ELEMENT -> {
                            val validNamespace = parser.namespace == StreamOpen.CLIENT_NAMESPACE ||
                                parser.namespace == StreamOpen.SERVER_NAMESPACE
                            when {
                                !validNamespace -> {
                                    malformedReason = malformedReason ?: "Forwarded result contains message in invalid namespace"
                                    ParserUtils.forwardToEndTagOfDepth(parser, depth)
                                }
                                messageSeen -> {
                                    malformedReason = malformedReason ?: "Forwarded result contains duplicate messages"
                                    ParserUtils.forwardToEndTagOfDepth(parser, depth)
                                }
                                else -> {
                                    messageSeen = true
                                    message = PacketParserUtils.parseMessage(parser, xmlEnvironment)
                                }
                            }
                        }
                        else -> ParserUtils.forwardToEndTagOfDepth(parser, depth)
                    }
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) break
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("Forwarded result ended before its closing element")
                else -> Unit
            }
        }
        return ParsedForwarded(delay, message, malformedReason)
    }

    private data class ParsedForwarded(
        val delay: DelayInformation?,
        val message: Message?,
        val malformedReason: String?,
    )
}

internal fun installNemaMamResultProvider() = synchronized(mamResultProviderLock) {
    when (val current = currentMamResultProvider()) {
        NemaMamResultProvider -> Unit
        null -> error("Smack MAM result provider is missing")
        else -> {
            check(current.javaClass == MamResultProvider::class.java) {
                "Unexpected MAM result provider owner"
            }
            ProviderManager.addExtensionProvider(
                MamResultExtension.ELEMENT,
                MamElements.NAMESPACE,
                NemaMamResultProvider,
            )
        }
    }
}

internal fun requireNemaMamResultProvider() {
    check(currentMamResultProvider() === NemaMamResultProvider) {
        "Nema does not own the MAM result provider"
    }
}

internal fun currentMamResultProvider(): ExtensionElementProvider<*>? = ProviderManager.getExtensionProvider(
    MamResultExtension.ELEMENT,
    MamElements.NAMESPACE,
)
