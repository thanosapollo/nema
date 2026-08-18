package org.thanosapollo.nema.storage

import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationSummarySqlTest {
    @Test
    fun homeConversationSqlStaysCheapTipMerge() {
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("archive_message_positions"))
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("WITH positioned"))
        assertFalse(CHEAP_CONVERSATION_SUMMARIES.contains("JOIN accounts"))
    }
}
