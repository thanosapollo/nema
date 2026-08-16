package org.thanosapollo.nema.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadSummaryLabelTest {
    @Test
    fun matchesNuntiusChipText() {
        assertEquals("Thread · 1 replies", threadSummaryLabel(1))
        assertEquals("Thread · 2 replies", threadSummaryLabel(2))
    }
}
