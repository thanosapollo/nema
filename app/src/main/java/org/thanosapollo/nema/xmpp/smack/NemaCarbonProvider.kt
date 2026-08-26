package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StreamOpen
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.util.ParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.carbons.packet.CarbonExtension
import org.jivesoftware.smackx.carbons.provider.CarbonManagerProvider
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.delay.provider.DelayInformationProvider
import org.jivesoftware.smackx.forward.packet.Forwarded

internal class MalformedCarbonException(message: String) : IOException(message)

private val carbonProviderLock = Any()
private val carbonDirections = listOf(CarbonExtension.Direction.sent, CarbonExtension.Direction.received)

internal object NemaCarbonProvider : ExtensionElementProvider<CarbonExtension>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): CarbonExtension {
        val direction = CarbonExtension.Direction.entries.firstOrNull { it.name == parser.name }
        val rootProblem = if (direction == null || parser.namespace != CarbonExtension.NAMESPACE) {
            "Unexpected Carbon element"
        } else {
            null
        }
        val parsed = parseWrapper(parser, initialDepth, xmlEnvironment)
        val problem = rootProblem ?: parsed.problem ?: if (parsed.forwarded == null) {
            "Carbon omitted forwarded element"
        } else {
            null
        }
        problem?.let { throw MalformedCarbonException(it) }
        return CarbonExtension(checkNotNull(direction), checkNotNull(parsed.forwarded))
    }

    private fun parseWrapper(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): ParsedWrapper {
        var forwarded: Forwarded<Message>? = null
        var forwardedSeen = false
        var problem: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> {
                    val depth = parser.depth
                    if (depth == initialDepth + 1 &&
                        parser.name == Forwarded.ELEMENT &&
                        parser.namespace == Forwarded.NAMESPACE
                    ) {
                        if (!forwardedSeen) {
                            forwardedSeen = true
                            val parsed = parseForwarded(parser, xmlEnvironment)
                            forwarded = parsed.forwarded
                            problem = problem ?: parsed.problem
                        } else {
                            problem = problem ?: "Carbon contains duplicate forwarded elements"
                            ParserUtils.forwardToEndTagOfDepth(parser, depth)
                        }
                    } else if (depth == initialDepth + 1) {
                        ParserUtils.forwardToEndTagOfDepth(parser, depth)
                    }
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) break
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("Carbon ended before its closing element")
                else -> Unit
            }
        }
        return ParsedWrapper(forwarded, problem)
    }

    private fun parseForwarded(
        parser: XmlPullParser,
        xmlEnvironment: XmlEnvironment,
    ): ParsedForwarded {
        val initialDepth = parser.depth
        var delay: DelayInformation? = null
        var message: Message? = null
        var problem: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> {
                    val depth = parser.depth
                    when {
                        depth != initialDepth + 1 -> Unit
                        parser.name == DelayInformation.ELEMENT &&
                            parser.namespace == DelayInformation.NAMESPACE -> if (delay != null) {
                                problem = problem ?: "Forwarded Carbon contains duplicate delay elements"
                                ParserUtils.forwardToEndTagOfDepth(parser, depth)
                            } else {
                                delay = DelayInformationProvider.INSTANCE.parse(parser, depth, xmlEnvironment)
                            }
                        parser.name == Message.ELEMENT -> {
                            val validNamespace = parser.namespace == StreamOpen.CLIENT_NAMESPACE ||
                                parser.namespace == StreamOpen.SERVER_NAMESPACE
                            when {
                                !validNamespace -> {
                                    problem = problem ?: "Forwarded Carbon contains message in invalid namespace"
                                    ParserUtils.forwardToEndTagOfDepth(parser, depth)
                                }
                                message != null -> {
                                    problem = problem ?: "Forwarded Carbon contains duplicate messages"
                                    ParserUtils.forwardToEndTagOfDepth(parser, depth)
                                }
                                else -> {
                                    message = PacketParserUtils.parseMessage(parser, xmlEnvironment)
                                }
                            }
                        }
                        else -> ParserUtils.forwardToEndTagOfDepth(parser, depth)
                    }
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) break
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("Forwarded Carbon ended before its closing element")
                else -> Unit
            }
        }
        if (message == null) problem = problem ?: "Forwarded Carbon omitted message"
        return ParsedForwarded(message?.let { Forwarded(it, delay) }, problem)
    }

    private data class ParsedWrapper(val forwarded: Forwarded<Message>?, val problem: String?)
    private data class ParsedForwarded(val forwarded: Forwarded<Message>?, val problem: String?)
}

internal fun installNemaCarbonProvider() = synchronized(carbonProviderLock) {
    val owners = carbonDirections.associateWith(::currentCarbonProvider)
    owners.forEach { (direction, owner) ->
        check(owner === NemaCarbonProvider || owner?.javaClass == CarbonManagerProvider::class.java) {
            "Unexpected ${direction.name} Carbon provider owner"
        }
    }
    owners.forEach { (direction, owner) ->
        if (owner !== NemaCarbonProvider) {
            ProviderManager.addExtensionProvider(
                direction.name,
                CarbonExtension.NAMESPACE,
                NemaCarbonProvider,
            )
        }
    }
}

private fun currentCarbonProvider(direction: CarbonExtension.Direction): ExtensionElementProvider<*>? =
    ProviderManager.getExtensionProvider(direction.name, CarbonExtension.NAMESPACE)
