package org.thanosapollo.nema.storage

import android.database.Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Compare all old values while separately verifying the additive ordinary defaults. */
internal fun Cursor.ordinaryHistoricalColumns(): List<Int> {
    getColumnIndex("protectedState").takeIf { it >= 0 }?.let { assertEquals("NONE", getString(it)) }
    getColumnIndex("protectedEvidence").takeIf { it >= 0 }?.let { assertTrue(isNull(it)) }
    return columnNames.indices.filterNot { columnNames[it] in setOf("protectedState", "protectedEvidence") }
}
