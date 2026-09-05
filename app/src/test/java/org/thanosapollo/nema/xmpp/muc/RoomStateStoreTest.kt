package org.thanosapollo.nema.xmpp.muc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomStateStoreTest {
    @Test
    fun `clearing account resets retained observer without detaching it`() = runTest {
        val store = RoomStateStore()
        val view = RoomView("room@example.org", subject = "old")
        val observed = mutableListOf<RoomView?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe("a", view.roomJid).collect { observed += it }
        }
        store.apply("a", view)
        store.apply("b", view)
        store.clearAccount("a")
        assertNull(observed.last())
        assertNull(store.current("a", view.roomJid))
        assertEquals(view, store.current("b", view.roomJid))
        val fresh = view.copy(subject = "new")
        store.apply("a", fresh)
        assertEquals(listOf(null, view, null, fresh), observed)
    }
}
