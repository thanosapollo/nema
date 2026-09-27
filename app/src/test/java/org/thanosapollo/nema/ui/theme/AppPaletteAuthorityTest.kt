package org.thanosapollo.nema.ui.theme

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.SideEffect
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppPaletteAuthorityTest {
    @get:Rule
    val composeRule = createRobolectricComposeRule()

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()
    private val preferences by lazy { context.getSharedPreferences("palette-authority-test", 0) }
    private lateinit var repository: AppearanceRepository

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        repository = AppearanceRepository(preferences, NoBackgroundAccess, Dispatchers.Unconfined)
    }

    @Test
    fun persistedNonDefaultPaletteDrivesLoginAndAuthenticatedBranches() = runTest {
        assertTrue(repository.saveAppPaletteId("catppuccin-latte"))
        val loginAuthority = AppPaletteAuthority(recreatedRepository())
        val authenticatedAuthority = AppPaletteAuthority(recreatedRepository())
        assertEquals("catppuccin-latte", loginAuthority.palette.id)
        assertEquals(loginAuthority.palette, authenticatedAuthority.palette)
    }

    @Test
    fun selectionRecomposesBeforePersistenceAndFailureRestoresPersistedPalette() = runTest {
        preferences.edit().putString("appearance.app.palette-id", "nord").commit()
        lateinit var authority: AppPaletteAuthority
        val failingRepository = AppearanceRepository(
            InspectingFailPreferences(preferences) { assertEquals("white", authority.palette.id) },
            NoBackgroundAccess, Dispatchers.Unconfined,
        )
        authority = AppPaletteAuthority(failingRepository)
        lateinit var observed: PaletteDefinition
        composeRule.setContent {
            val palette = authority.palette
            SideEffect { observed = palette }
        }

        assertFalse(authority.select("white"))
        composeRule.runOnIdle { assertEquals("nord", observed.id) }
        assertEquals("nord", recreatedRepository().appPaletteId())
    }

    @Test
    fun selectionSurvivesRepositoryAndActivityRecreationAndAccountTransitions() = runTest {
        val authority = AppPaletteAuthority(repository)
        assertTrue(authority.select("everforest"))
        assertEquals("everforest", authority.palette.id)
        assertEquals("everforest", AppPaletteAuthority(recreatedRepository()).palette.id)
    }

    @Test
    fun failedApplyDoesNotBecomePersistedAfterRecreation() = runTest {
        assertTrue(repository.saveAppPaletteId("nord"))
        val failingRepository = AppearanceRepository(
            FailingCommitPreferences(preferences), NoBackgroundAccess, Dispatchers.Unconfined,
        )
        val authority = AppPaletteAuthority(failingRepository)
        assertFalse(authority.select("white"))
        assertEquals("nord", authority.palette.id)
        assertEquals("nord", AppPaletteAuthority(recreatedRepository()).palette.id)
    }

    @Test
    fun appPaletteWritesUseRepositorySerializationBoundary() {
        val source = File("src/main/java/org/thanosapollo/nema/ui/theme/AppearanceRepository.kt").readText()
        val save = source.substringAfter("suspend fun saveAppPaletteId")
            .substringBefore("fun resolve(")

        assertTrue(save.contains("writes.withLock"))
    }

    @Test
    fun unknownPersistedAndSelectedIdsFallBackToDefault() = runTest {
        preferences.edit().putString("appearance.app.palette-id", "removed").commit()
        val authority = AppPaletteAuthority(recreatedRepository())

        assertEquals(PaletteCatalog.default.id, authority.palette.id)
        authority.select("also-removed")
        assertEquals(PaletteCatalog.default.id, authority.palette.id)
    }

    @Test
    fun mainActivityHasExactlyOneThemeRootAboveItsBranch() {
        val source = File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()
        val root = source.indexOf("NemaTheme(themeAuthority.palette)")
        val branch = source.indexOf("AccountConnectionScreen(", root)

        assertTrue(root >= 0)
        assertTrue(branch > root)
        assertEquals(1, Regex("NemaTheme\\(").findAll(source).count())
        assertFalse(source.contains("AppearanceSpec.DEFAULT"))
    }

    private fun recreatedRepository() =
        AppearanceRepository(preferences, NoBackgroundAccess, Dispatchers.Unconfined)

    private object NoBackgroundAccess : PersistableBackgroundAccess {
        override fun takeReadPermission(uri: Uri) = false
        override fun isReadable(uri: Uri) = false
    }

    private class FailingCommitPreferences(private val delegate: SharedPreferences) :
        SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = FailingEditor(delegate.edit())
    }

    private class FailingEditor(private val delegate: SharedPreferences.Editor) :
        SharedPreferences.Editor by delegate {
        override fun commit(): Boolean {
            delegate.commit()
            return false
        }
    }

    private class InspectingFailPreferences(
        private val delegate: SharedPreferences,
        private val onCommit: () -> Unit,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by delegate.edit() {
            override fun commit(): Boolean { onCommit(); return false }
        }
    }
}
