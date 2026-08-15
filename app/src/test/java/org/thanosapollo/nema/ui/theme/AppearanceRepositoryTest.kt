package org.thanosapollo.nema.ui.theme

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppearanceRepositoryTest {
    private val context: Application
        get() = ApplicationProvider.getApplicationContext()
    private lateinit var access: FakeBackgroundAccess
    private lateinit var repository: AppearanceRepository

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFERENCES, 0).edit().clear().commit()
        access = FakeBackgroundAccess()
        repository = AppearanceRepository(
            preferences = context.getSharedPreferences(PREFERENCES, 0),
            backgroundAccess = access,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES, 0).edit().clear().commit()
    }

    @Test
    fun modesAndOverridesPersistAcrossRepositoryRecreationWithStrictAccountIsolation() = runTest {
        repository.save(AppearanceScope.App, AppearanceSpec(themeMode = ThemeMode.LIGHT))
        repository.save(
            AppearanceScope.Account("a"),
            AppearanceSpec(themeMode = ThemeMode.DARK),
        )
        repository.save(
            AppearanceScope.Conversation("a", "peer@example.org"),
            AppearanceSpec(palette = PaletteChoice.Custom.require("#315DA8", "#F7F8FA")),
        )

        val recreated = AppearanceRepository(
            preferences = context.getSharedPreferences(PREFERENCES, 0),
            backgroundAccess = access,
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(ThemeMode.LIGHT, recreated.resolve(null, null).themeMode)
        assertEquals(ThemeMode.DARK, recreated.resolve("a", null).themeMode)
        assertTrue(recreated.resolve("a", "peer@example.org").palette is PaletteChoice.Custom)
        assertEquals(ThemeMode.LIGHT, recreated.resolve("b", "peer@example.org").themeMode)
    }

    @Test
    fun textAndDisplayScalePersistAcrossRepositoryRecreation() = runTest {
        repository.save(
            AppearanceScope.App,
            AppearanceSpec(textScale = 1.25f, uiScale = 1.1f),
        )
        val recreated = AppearanceRepository(
            preferences = context.getSharedPreferences(PREFERENCES, 0),
            backgroundAccess = access,
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertEquals(1.25f, recreated.resolve(null, null).textScale)
        assertEquals(1.1f, recreated.resolve(null, null).uiScale)
    }

    @Test
    fun everyExplicitThemeModePersists() = runTest {
        ThemeMode.entries.forEach { mode ->
            repository.save(AppearanceScope.App, AppearanceSpec(themeMode = mode))
            val recreated = AppearanceRepository(
                preferences = context.getSharedPreferences(PREFERENCES, 0),
                backgroundAccess = access,
                ioDispatcher = Dispatchers.Unconfined,
            )
            assertEquals(mode, recreated.resolve(null, null).themeMode)
        }
    }

    @Test
    fun successfulSaveEmitsLiveRevisionAndClearRestoresInheritance() = runTest {
        val originalRevision = repository.revision.value
        repository.save(
            AppearanceScope.Account("a"),
            AppearanceSpec(themeMode = ThemeMode.DARK),
        )

        assertTrue(repository.revision.value > originalRevision)
        assertEquals(ThemeMode.DARK, repository.resolve("a", null).themeMode)

        repository.clear(AppearanceScope.Account("a"))
        assertEquals(AppearanceSpec.DEFAULT, repository.resolve("a", null))
    }

    @Test
    fun serializedUpdatesPreserveIndependentRapidChanges() = runTest {
        val custom = PaletteChoice.Custom.require("#315DA8", "#F7F8FA")
        coroutineScope {
            launch {
                repository.update(AppearanceScope.App) { it.copy(themeMode = ThemeMode.DARK) }
            }
            launch {
                repository.update(AppearanceScope.App) { it.copy(palette = custom) }
            }
        }

        assertEquals(AppearanceSpec(ThemeMode.DARK, custom), repository.resolve(null, null))
    }

    @Test
    fun backgroundIsStoredOnlyAfterDurablePermissionAndReadableCheck() = runTest {
        val accepted = Uri.parse("content://pictures/accepted")
        val denied = Uri.parse("content://pictures/denied")
        access.accepted += accepted

        assertTrue(repository.setBackground(AppearanceScope.Account("a"), accepted))
        assertEquals(accepted.toString(), repository.resolve("a", null).backgroundUri)

        assertFalse(repository.setBackground(AppearanceScope.Account("a"), denied))
        assertEquals(accepted.toString(), repository.resolve("a", null).backgroundUri)
    }

    @Test
    fun revokedBackgroundFailsClosedWithoutDestroyingPersistedPalette() = runTest {
        val uri = Uri.parse("content://pictures/revoked")
        access.accepted += uri
        val custom = PaletteChoice.Custom.require("#315DA8", "#F7F8FA")
        repository.save(AppearanceScope.Account("a"), AppearanceSpec(palette = custom))
        assertTrue(repository.setBackground(AppearanceScope.Account("a"), uri))

        access.readable -= uri

        val effective = repository.resolve("a", null)
        assertEquals(custom, effective.palette)
        assertNull(effective.backgroundUri)
    }

    @Test
    fun replacedAndClearedBackgroundsReleaseUnusedDurablePermissions() = runTest {
        val first = Uri.parse("content://pictures/first")
        val second = Uri.parse("content://pictures/second")
        access.accepted += first
        access.accepted += second

        assertTrue(repository.setBackground(AppearanceScope.App, first))
        assertTrue(repository.setBackground(AppearanceScope.App, second))
        assertEquals(listOf(first), access.released)

        assertTrue(repository.clearBackground(AppearanceScope.App))
        assertEquals(listOf(first, second), access.released)
    }

    @Test
    fun sharedBackgroundPermissionIsRetainedUntilLastScopeClearsIt() = runTest {
        val shared = Uri.parse("content://pictures/shared")
        access.accepted += shared
        assertTrue(repository.setBackground(AppearanceScope.App, shared))
        assertTrue(repository.setBackground(AppearanceScope.Account("a"), shared))

        assertTrue(repository.clearBackground(AppearanceScope.App))
        assertTrue(access.released.isEmpty())

        assertTrue(repository.clear(AppearanceScope.Account("a")))
        assertEquals(listOf(shared), access.released)
    }

    private class FakeBackgroundAccess : PersistableBackgroundAccess {
        val accepted = mutableSetOf<Uri>()
        val readable = mutableSetOf<Uri>()
        val released = mutableListOf<Uri>()

        override fun takeReadPermission(uri: Uri): Boolean {
            if (uri !in accepted) return false
            readable += uri
            return true
        }

        override fun isReadable(uri: Uri): Boolean = uri in readable

        override fun releaseReadPermission(uri: Uri) {
            readable -= uri
            released += uri
        }
    }

    private companion object {
        const val PREFERENCES = "appearance-test"
    }
}
