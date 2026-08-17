package org.thanosapollo.nema.xmpp.reactions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.xmpp.smack.SmackAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageReactionsTest {
    @Before
    fun installProviders() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        installNemaReactionProviders()
    }

    @Test
    fun deduplicatesAndTogglesReplacementSets() {
        assertEquals(listOf("👍", "❤️"), deduplicateReactions(listOf("👍", " 👍 ", "", "❤️")))
        assertEquals(listOf("👍", "🎉"), toggleReaction("🎉", listOf("👍")))
        assertEquals(listOf("🎉"), toggleReaction("👍", listOf("👍", "🎉")))
        assertEquals(emptyList<String>(), toggleReaction("👍", listOf("👍")))
        assertEquals("👍\u001f❤️", encodeReactionEmojis(listOf("👍", "❤️", "👍")))
        assertEquals(listOf("👍", "❤️"), decodeReactionEmojis("👍\u001f❤️"))
        assertEquals(emptyList<String>(), decodeReactionEmojis(""))
    }

    @Test
    fun parseRequiresOneReactionsElementAndTargetId() {
        assertEquals(
            ParsedReactions("origin-1", listOf("👋", "🐢")),
            parse(
                """
                <message xmlns='jabber:client' type='chat'>
                  <reactions xmlns='urn:xmpp:reactions:0' id='origin-1'>
                    <reaction>👋</reaction>
                    <reaction>🐢</reaction>
                    <reaction>👋</reaction>
                  </reactions>
                </message>
                """.trimIndent(),
            ),
        )
        assertEquals(
            ParsedReactions("origin-1", emptyList()),
            parse(
                """
                <message xmlns='jabber:client' type='chat'>
                  <reactions xmlns='urn:xmpp:reactions:0' id='origin-1'/>
                </message>
                """.trimIndent(),
            ),
        )
        assertNull(parse("<message xmlns='jabber:client' type='chat'><body>hello</body></message>"))
        assertNull(
            parse(
                """
                <message xmlns='jabber:client' type='chat'>
                  <reactions xmlns='urn:xmpp:reactions:0'>
                    <reaction>👋</reaction>
                  </reactions>
                </message>
                """.trimIndent(),
            ),
        )
        assertNull(
            parse(
                """
                <message xmlns='jabber:client' type='chat'>
                  <reactions xmlns='urn:xmpp:reactions:0' id='one'>
                    <reaction>👋</reaction>
                  </reactions>
                  <reactions xmlns='urn:xmpp:reactions:0' id='two'>
                    <reaction>🐢</reaction>
                  </reactions>
                </message>
                """.trimIndent(),
            ),
        )
    }

    private fun parse(xml: String): ParsedReactions? =
        (PacketParserUtils.parseStanza(xml) as Message).parseReactions()
}
