package org.thanosapollo.nema.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.update.UpdateUiModel
import org.thanosapollo.nema.ui.theme.PaletteCatalog
import org.thanosapollo.nema.xmpp.transport.AccountId

private val hasButtonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
private val hasNoRole = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountSettingsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun updatesActionsAreVisibleAndInvokeEnabledCallbacksOnce() {
        var checks = 0
        var downloads = 0
        var installs = 0
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    update = UpdateUiModel(
                        currentVersion = "Current version: Nema 0.1.1 (2)",
                        status = "Nema 0.2 is available",
                        canCheck = true,
                        canDownload = true,
                        canInstall = false,
                    ),
                    onCheckForUpdates = { checks++ },
                    onDownloadUpdate = { downloads++ },
                    onInstallUpdate = { installs++ },
                )
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToIndex(8)
        composeRule.onNodeWithText("Current version: Nema 0.1.1 (2)").assertIsDisplayed()
        composeRule.onNodeWithText("Nema 0.2 is available").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-row-update-status")
            .assertHasNoClickAction()
            .assert(hasNoRole)
        list.performScrollToIndex(9)
        composeRule.onNodeWithTag("settings-row-check-update")
            .assert(hasButtonRole)
            .performClick()
        list.performScrollToIndex(10)
        composeRule.onNodeWithTag("settings-row-download-update")
            .assert(hasButtonRole)
            .performClick()
        composeRule.onNodeWithText("Install update").assertDoesNotExist()
        assertEquals(1, checks)
        assertEquals(1, downloads)
        assertEquals(0, installs)
    }

    @Test
    fun updaterCurrentStatusAndDisabledCheckInstallStateStayExact() {
        var checks = 0
        var downloads = 0
        var installs = 0
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    update = UpdateUiModel(
                        currentVersion = "Current version: Nema 0.1.1 (2)",
                        status = "Update verified",
                        canCheck = false,
                        canDownload = false,
                        canInstall = true,
                    ),
                    onCheckForUpdates = { checks++ },
                    onDownloadUpdate = { downloads++ },
                    onInstallUpdate = { installs++ },
                )
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToIndex(8)
        composeRule.onNodeWithText("Current version: Nema 0.1.1 (2)").assertIsDisplayed()
        composeRule.onNodeWithText("Update verified").assertIsDisplayed()
        list.performScrollToIndex(9)
        composeRule.onNodeWithTag("settings-row-check-update")
            .assertIsNotEnabled()
            .assertHasNoClickAction()
            .assert(hasButtonRole)
        composeRule.onNodeWithText("Download update").assertDoesNotExist()
        list.performScrollToIndex(10)
        composeRule.onNodeWithTag("settings-row-install-update")
            .assert(hasButtonRole)
            .performClick()
        assertEquals(0, checks)
        assertEquals(0, downloads)
        assertEquals(1, installs)
    }

    @Test
    fun settingsKeepsTaggedThemesPrivacyAndSessionOrder() {
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(activeAccountId = FIRST)
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToIndex(0)
        composeRule.onNodeWithTag("settings-row-themes")
            .assertHeightIsAtLeast(64.dp)
            .assert(hasButtonRole)
        list.performScrollToIndex(1)
        composeRule.onNodeWithText("Accounts").assertIsDisplayed()
        list.performScrollToIndex(2)
        composeRule.onNodeWithTag("settings-section-privacy").assertIsDisplayed()
        list.performScrollToIndex(3)
        composeRule.onNodeWithTag("settings-row-read-receipts")
            .assertHeightIsAtLeast(64.dp)
            .assertHasNoClickAction()
            .assert(hasNoRole)
        list.performScrollToIndex(4)
        composeRule.onNodeWithTag("settings-section-session").assertIsDisplayed()
        list.performScrollToIndex(5)
        composeRule.onNodeWithTag("settings-row-stop")
            .assertHeightIsAtLeast(64.dp)
            .assert(hasButtonRole)
        list.performScrollToIndex(6)
        composeRule.onNodeWithTag("settings-row-sign-out")
            .assertHeightIsAtLeast(64.dp)
            .assert(hasButtonRole)
    }

    @Test
    fun settingsOwnsStopAndSignOut() {
        var stopCalls = 0
        var signOutCalls = 0
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    onStop = { stopCalls++ },
                    onSignOut = { signOutCalls++ },
                )
            }
        }

        val list = composeRule.onNodeWithTag("settings-list")
        list.performScrollToIndex(6)
        composeRule.onNodeWithTag("settings-row-sign-out").performClick()
        list.performScrollToIndex(5)
        composeRule.onNodeWithTag("settings-row-stop").performClick()

        assertEquals(1, signOutCalls)
        assertEquals(1, stopCalls)
    }

    @Test
    fun themesJourneyIsVisibleWithoutDormantAppearanceControls() {
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    conversationPeer = "peer@example.org",
                    onSetThemeMode = {},
                    onSetPalette = {},
                )
            }
        }

        composeRule.onNodeWithText("Themes").assertIsDisplayed()
        composeRule.onNodeWithText("Custom accent #RRGGBB").assertDoesNotExist()
        composeRule.onNodeWithText("Apply custom palette").assertDoesNotExist()
        composeRule.onNodeWithText("Dark").assertDoesNotExist()
    }

    @Test
    fun callerTopPaddingProtectsSettingsAndThemesEqually() {
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }

        val callerTopPaddingPx = with(composeRule.density) { 32.dp.toPx() }
        val settingsTitleTop = composeRule.onNodeWithText("Settings")
            .fetchSemanticsNode().boundsInRoot.top
        val settingsListTop = composeRule.onNodeWithTag("settings-list")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "caller top padding must protect Settings title",
            settingsTitleTop >= callerTopPaddingPx,
        )
        assertTrue(
            "caller top padding must protect Settings list",
            settingsListTop >= callerTopPaddingPx,
        )

        composeRule.onNodeWithTag("settings-row-themes").performClick()

        val themesTitleTop = composeRule.onNodeWithText("Themes")
            .fetchSemanticsNode().boundsInRoot.top
        val themesListTop = composeRule.onNodeWithTag("theme-scroll-list")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "caller top padding must protect Themes title",
            themesTitleTop >= callerTopPaddingPx,
        )
        assertTrue(
            "caller top padding must protect Themes list",
            themesListTop >= callerTopPaddingPx,
        )
        assertEquals(settingsTitleTop, themesTitleTop, 0.5f)
        assertEquals(settingsListTop, themesListTop, 0.5f)
    }

    @Test
    fun settingsOwnsAccountSwitching() {
        val selected = mutableListOf<AccountId>()
        var addCalls = 0
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    accounts = listOf(account(FIRST), account(SECOND)),
                    onSelectAccount = { selected += it },
                    onAddAccount = { addCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("Accounts").assertIsDisplayed()
        composeRule.onNodeWithText("Switch to second@example.org").performScrollTo().performClick()
        composeRule.onNodeWithText("Add account").performScrollTo().performClick()
        assertEquals(listOf(SECOND), selected)
        assertEquals(1, addCalls)
    }

    @Test
    fun readReceiptPrivacyControlIsOffByDefaultAndReportsExplicitOptIn() {
        val selected = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    onSetReadReceiptsEnabled = { selected += it },
                )
            }
        }

        composeRule.onNodeWithTag("read-receipts-toggle").performScrollTo().performClick()
        assertEquals(listOf(true), selected)
    }

    @Test
    fun themeHierarchyListsEveryPaletteWithSwatchesAndSelection() {
        val empty = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                if (empty.value) ThemeSettingsContent("missing", palettes = emptyList()) { open ->
                    TextButton(onClick = open) { Text("Themes") }
                }
                else AccountSettingsContent(activeAccountId = FIRST)
            }
        }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Themes").performClick()
        composeRule.onNodeWithText("Themes").assertIsDisplayed()
        composeRule.onNodeWithText("Select theme").assertDoesNotExist()
        composeRule.onNodeWithText("Apply").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithText("Default").assertIsSelected()
        composeRule.onNodeWithText("Default")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithTag("theme-scroll-list")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        composeRule.onNodeWithTag("theme-list")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.SelectableGroup))
        PaletteCatalog.entries.forEachIndexed { index, palette ->
            composeRule.onNodeWithTag("theme-scroll-list").performScrollToIndex(index)
            composeRule.onNodeWithText(palette.displayName).assertIsDisplayed()
            composeRule.onNodeWithTag("theme-swatches-${palette.id}", true).assertExists()
        }
        composeRule.runOnIdle { empty.value = true }
        composeRule.onNodeWithText("Themes").performClick()
        composeRule.onNodeWithText("No themes available").assertIsDisplayed()
    }

    @Test
    fun themeTapImmediatelySavesStaysOnThemesReportsFailureAndBackReturnsToSettings() {
        val events = mutableListOf<String>()
        lateinit var completeSave: (Boolean) -> Unit
        lateinit var back: OnBackPressedDispatcher
        composeRule.setContent {
            MaterialTheme {
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                var current by remember { mutableStateOf(PaletteCatalog.default.id) }
                AccountSettingsContent(
                    activeAccountId = FIRST,
                    currentPaletteId = current,
                    onSelectPalette = { id, complete ->
                        current = id
                        events += "save:$id"
                        completeSave = complete
                    },
                )
            }
        }
        composeRule.onNodeWithText("Themes").performClick()
        selectPalette("nord", "Nord")
        composeRule.onNodeWithText("Nord").assertIsSelected()
        composeRule.runOnIdle { assertEquals(listOf("save:nord"), events); completeSave(false) }
        composeRule.onNodeWithText("Theme was not saved").assertIsDisplayed()
        composeRule.onNodeWithText("Themes").assertIsDisplayed()
        selectPalette("white", "White")
        composeRule.runOnIdle { completeSave(true); back.onBackPressed() }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    private fun selectPalette(id: String, name: String) {
        val index = PaletteCatalog.entries.indexOfFirst { it.id == id }
        composeRule.onNodeWithTag("theme-scroll-list").performScrollToIndex(index)
        composeRule.onNodeWithText(name).performClick()
    }

    private fun account(id: AccountId) = AccountConfiguration.create(
        id = id,
        bareJid = "${id.value}@example.org",
        authenticationId = id.value,
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private companion object {
        val FIRST = AccountId.require("first")
        val SECOND = AccountId.require("second")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountsUseSharedTaggedRowsWithAvatarSelectionAndExactCallbacks() {
        val selected = mutableListOf<AccountId>()
        var addCalls = 0
        composeRule.setContent {
            MaterialTheme {
                AccountsContent(
                    accounts = listOf(account(FIRST), account(SECOND)),
                    activeAccountId = FIRST,
                    switching = false,
                    onSelectAccount = { selected += it },
                    onAddAccount = { addCalls++ },
                )
            }
        }

        composeRule.onNodeWithTag("settings-section-accounts").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("settings-row-account-first") and
                hasAnyDescendant(hasTestTag("account-avatar-first")),
            useUnmergedTree = true,
        )
            .assertHeightIsAtLeast(64.dp)
            .assertIsSelected()
            .assertIsNotEnabled()
            .assertHasNoClickAction()
            .assert(hasNoRole)
        composeRule.onNodeWithTag("settings-row-account-second")
            .assertHeightIsAtLeast(64.dp)
            .assertIsNotSelected()
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(hasNoRole)
            .performClick()
        composeRule.onNodeWithTag("settings-row-add-account")
            .assertHeightIsAtLeast(64.dp)
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(hasButtonRole)
            .performClick()

        assertEquals(listOf(SECOND), selected)
        assertEquals(1, addCalls)
    }

    @Test
    fun accountsSelectsConfiguredAccountAndOffersAddAccount() {
        val selected = mutableListOf<AccountId>()
        var addCalls = 0
        composeRule.setContent {
            MaterialTheme {
                AccountsContent(
                    accounts = listOf(account(FIRST), account(SECOND)),
                    activeAccountId = FIRST,
                    switching = false,
                    onSelectAccount = { accountId: AccountId -> selected += accountId },
                    onAddAccount = { addCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("Active: first@example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Switch to second@example.org").performClick()
        composeRule.onNodeWithText("Add account").performClick()

        assertEquals(listOf(SECOND), selected)
        assertEquals(1, addCalls)
    }

    @Test
    fun switchingStateDisablesAnotherSelection() {
        composeRule.setContent {
            MaterialTheme {
                AccountsContent(
                    accounts = listOf(account(FIRST), account(SECOND)),
                    activeAccountId = FIRST,
                    switching = true,
                    onSelectAccount = {},
                    onAddAccount = {},
                )
            }
        }

        composeRule.onNodeWithText("Switching account").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-row-account-second")
            .assertIsNotEnabled()
            .assertHasNoClickAction()
        composeRule.onNodeWithTag("settings-row-add-account")
            .assertIsNotEnabled()
            .assertHasNoClickAction()
            .assert(hasButtonRole)
    }

    private fun account(id: AccountId) = AccountConfiguration.create(
        id = id,
        bareJid = "${id.value}@example.org",
        authenticationId = id.value,
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private companion object {
        val FIRST = AccountId.require("first")
        val SECOND = AccountId.require("second")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionBottomBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun destinationsPresentAndSelectedStateTracksClicks() {
        var selected = PrimaryDestination.HOME
        composeRule.setContent {
            MaterialTheme {
                var current by remember { mutableStateOf(PrimaryDestination.HOME) }
                selected = current
                SessionBottomBar(
                    selected = current,
                    onSelect = { current = selectSessionDestination(it, {}) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Primary destinations").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Primary destinations").assertCountEquals(1)
        composeRule.onNodeWithText("Home").assertIsSelected()
        composeRule.onNodeWithText("Home").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Settings").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.runOnIdle { assertEquals(PrimaryDestination.SETTINGS, selected) }
        composeRule.onNodeWithText("Settings").assertIsSelected()
        composeRule.onNodeWithText("Switch account").assertDoesNotExist()
        composeRule.onNodeWithText("Home").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun selectedDestinationSurvivesRecreation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                var current by rememberSaveable { mutableStateOf(PrimaryDestination.SETTINGS.name) }
                SessionBottomBar(
                    selected = PrimaryDestination.fromSaved(current),
                    onSelect = { current = it.name },
                )
            }
        }

        composeRule.onNodeWithText("Settings").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Settings").assertIsSelected()
    }
}
