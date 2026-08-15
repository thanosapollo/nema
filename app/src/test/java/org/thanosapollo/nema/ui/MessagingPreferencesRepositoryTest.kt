package org.thanosapollo.nema.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessagingPreferencesRepositoryTest {
    @Test
    fun `read receipts default off and remain account scoped`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("messaging-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val repository = MessagingPreferencesRepository(preferences)

        assertEquals(false, repository.readReceipts("account-a").first())
        repository.setReadReceipts("account-a", true)
        assertEquals(true, repository.readReceipts("account-a").first())
        assertEquals(false, repository.readReceipts("account-b").first())
    }
}
