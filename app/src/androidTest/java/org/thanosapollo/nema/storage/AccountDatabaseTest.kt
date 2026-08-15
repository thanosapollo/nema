package org.thanosapollo.nema.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountDatabaseTest {
    private lateinit var database: NemaDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NemaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun freshDatabaseStartsWithoutActiveAccount() = runBlocking {
        assertNull(database.accountDao().observeActiveAccount().first())
    }

    @Test
    fun activatingAnotherAccountRetainsBothConfigurationsAndOneAuthority() = runBlocking {
        val first = account("first", "first@example.org")
        val second = account("second", "second@example.org")
        database.accountDao().upsert(first)
        database.accountDao().upsert(second)

        database.accountDao().activate(first.id)
        database.accountDao().activate(second.id)

        assertEquals(second, database.accountDao().observeActiveAccount().first())
        assertEquals(listOf(first, second), database.accountDao().allAccounts())
    }

    private fun account(id: String, bareJid: String) = AccountEntity(
        id = id,
        bareJid = bareJid,
        authenticationId = bareJid.substringBefore('@'),
        authorizationId = null,
        serviceDomain = bareJid.substringAfter('@'),
        networkHost = null,
        networkPort = null,
    )
}
