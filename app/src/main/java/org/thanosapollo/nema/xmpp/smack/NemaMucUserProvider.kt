/*
 * Copyright 2003-2007 Jive Software.
 *
 * Adapted from Smack 4.4.8 MUCUserProvider under the Apache License, Version 2.0.
 * Original provider author: Gaston Dombiak.
 * Nema's adaptation preserves direct item cardinality for fail-closed archive attribution.
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.provider.ExtensionElementProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.jivesoftware.smack.util.ParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jivesoftware.smackx.muc.packet.MUCItem
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jivesoftware.smackx.muc.provider.MUCParserUtils
import org.jivesoftware.smackx.muc.provider.MUCUserProvider

private val mucUserProviderLock = Any()

internal class NemaMucUser : MUCUser() {
    var itemCount: Int = 0
        private set

    fun recordItem(parsed: MUCItem) {
        itemCount += 1
        item = parsed
    }
}

internal object NemaMucUserProvider : ExtensionElementProvider<NemaMucUser>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        xmlEnvironment: XmlEnvironment,
    ): NemaMucUser {
        val user = NemaMucUser()
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> if (
                    parser.depth == initialDepth + 1 && parser.namespace == MUCUser.NAMESPACE
                ) {
                    when (parser.name) {
                        "invite" -> user.invite = parseInvite(parser)
                        "item" -> user.recordItem(MUCParserUtils.parseItem(parser))
                        "password" -> user.password = parser.nextText()
                        "status" -> user.addStatusCode(
                            MUCUser.Status.create(parser.getAttributeValue("", "code")),
                        )
                        "decline" -> user.decline = parseDecline(parser)
                        "destroy" -> user.destroy = MUCParserUtils.parseDestroy(parser)
                    }
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) return user
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("MUC user element ended before closing")
                else -> Unit
            }
        }
    }

    private fun parseInvite(parser: XmlPullParser): MUCUser.Invite {
        val initialDepth = parser.depth
        val to = ParserUtils.getBareJidAttribute(parser, "to")
        val from = ParserUtils.getEntityJidAttribute(parser, "from")
        var reason: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> if (parser.name == "reason") {
                    reason = parser.nextText()
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) {
                    return MUCUser.Invite(reason, from, to)
                }
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("MUC invite ended before closing")
                else -> Unit
            }
        }
    }

    private fun parseDecline(parser: XmlPullParser): MUCUser.Decline {
        val initialDepth = parser.depth
        val to = ParserUtils.getBareJidAttribute(parser, "to")
        val from = ParserUtils.getBareJidAttribute(parser, "from")
        var reason: String? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.Event.START_ELEMENT -> if (parser.name == "reason") {
                    reason = parser.nextText()
                }
                XmlPullParser.Event.END_ELEMENT -> if (parser.depth == initialDepth) {
                    return MUCUser.Decline(reason, from, to)
                }
                XmlPullParser.Event.END_DOCUMENT -> throw IOException("MUC decline ended before closing")
                else -> Unit
            }
        }
    }
}

internal fun installNemaMucUserProvider() = synchronized(mucUserProviderLock) {
    when (val current = currentMucUserProvider()) {
        NemaMucUserProvider -> Unit
        null -> error("Smack MUC user provider is missing")
        else -> {
            check(current.javaClass == MUCUserProvider::class.java) {
                "Unexpected MUC user provider owner"
            }
            ProviderManager.addExtensionProvider(MUCUser.ELEMENT, MUCUser.NAMESPACE, NemaMucUserProvider)
        }
    }
}

internal fun requireNemaMucUserProvider() {
    check(currentMucUserProvider() === NemaMucUserProvider) {
        "Nema does not own the MUC user provider"
    }
}

internal fun currentMucUserProvider(): ExtensionElementProvider<*>? =
    ProviderManager.getExtensionProvider(MUCUser.ELEMENT, MUCUser.NAMESPACE)
