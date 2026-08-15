package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountSettingsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

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

        composeRule.onNodeWithText("Sign out").performScrollTo().performClick()
        composeRule.onNodeWithText("Stop").performScrollTo().performClick()

        assertEquals(1, signOutCalls)
        assertEquals(1, stopCalls)
    }

    @Test
    fun appearanceControlsAreHiddenAsWorkInProgress() {
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

        composeRule.onNodeWithText("Appearance (WIP)").assertIsDisplayed()
        composeRule.onNodeWithText("Theme and palette options are hidden until they are stable.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Custom accent #RRGGBB").assertDoesNotExist()
        composeRule.onNodeWithText("Apply custom palette").assertDoesNotExist()
        composeRule.onNodeWithText("Dark").assertDoesNotExist()
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
        composeRule.onNodeWithText("Switch to second@example.org").assertIsNotEnabled()
        composeRule.onNodeWithText("Add account").assertIsNotEnabled()
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
                    onSelect = { current = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Primary destinations").assertIsDisplayed()
        composeRule.onNodeWithText("Home").assertIsSelected()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.runOnIdle { assertEquals(PrimaryDestination.SETTINGS, selected) }
        composeRule.onNodeWithText("Settings").assertIsSelected()
        composeRule.onNodeWithText("Switch account").assertDoesNotExist()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
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
