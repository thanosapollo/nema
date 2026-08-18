package org.thanosapollo.nema.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummarySqlTest {
    @Test
    fun homeConversationSqlStaysCheapTipMerge() {
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("archive_message_positions"))
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("WITH "))
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("JOIN accounts"))
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("NOT EXISTS"))
        assertTrue(CHEAP_CONVERSATION_SUMMARIES.contains("MAX(sentAtEpochMs)"))
        assertTrue(CHEAP_CONVERSATION_SUMMARIES.contains("MAX(messages.localSequence)"))
    }
}
