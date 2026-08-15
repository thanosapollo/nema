package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoginFormContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `blank first run shows basic login and expands advanced on disclosure`() {
        composeRule.setContent {
            MaterialTheme {
                var advancedOpen by remember { mutableStateOf(false) }
                LoginFormContent(
                    bareJid = "",
                    onBareJidChange = {},
                    password = "",
                    onPasswordChange = {},
                    advancedOpen = advancedOpen,
                    onAdvancedToggle = { advancedOpen = !advancedOpen },
                    authenticationId = "",
                    onAuthenticationIdChange = {},
                    authorizationId = "",
                    onAuthorizationIdChange = {},
                    serviceDomain = "",
                    onServiceDomainChange = {},
                    networkHost = "",
                    onNetworkHostChange = {},
                    networkPort = "5222",
                    onNetworkPortChange = {},
                    message = null,
                    showSessionChrome = false,
                    connectionStatus = null,
                    signInEnabled = false,
                    onSignIn = {},
                    onStop = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("JID (name@domain)").assertIsDisplayed()
        composeRule.onNodeWithText("Password").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Advanced").assertIsDisplayed()

        composeRule.onNodeWithText("Authentication identity").assertDoesNotExist()
        composeRule.onNodeWithText("Authorization identity (optional)").assertDoesNotExist()
        composeRule.onNodeWithText("XMPP service domain").assertDoesNotExist()
        composeRule.onNodeWithText("Network host override (optional)").assertDoesNotExist()
        composeRule.onNodeWithText("Network port").assertDoesNotExist()
        composeRule.onNodeWithText("Stopped").assertDoesNotExist()
        composeRule.onNodeWithText("Stop").assertDoesNotExist()
        composeRule.onNodeWithText("Sign out").assertDoesNotExist()

        composeRule.onNodeWithText("Advanced").performClick()

        composeRule.onNodeWithText("Hide advanced").assertIsDisplayed()
        composeRule.onNodeWithText("Authentication identity").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Authorization identity (optional)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("XMPP service domain").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Network host override (optional)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Network port").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertDoesNotExist()
        composeRule.onNodeWithText("Sign out").assertDoesNotExist()
    }

    @Test
    fun `saved account chrome shows status stop and sign out`() {
        composeRule.setContent {
            MaterialTheme {
                LoginFormContent(
                    bareJid = "person@example.org",
                    onBareJidChange = {},
                    password = "",
                    onPasswordChange = {},
                    advancedOpen = false,
                    onAdvancedToggle = {},
                    authenticationId = "person",
                    onAuthenticationIdChange = {},
                    authorizationId = "",
                    onAuthorizationIdChange = {},
                    serviceDomain = "example.org",
                    onServiceDomainChange = {},
                    networkHost = "",
                    onNetworkHostChange = {},
                    networkPort = "5222",
                    onNetworkPortChange = {},
                    message = null,
                    showSessionChrome = true,
                    connectionStatus = "Stopped",
                    signInEnabled = false,
                    onSignIn = {},
                    onStop = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("JID (name@domain)").assertIsDisplayed()
        composeRule.onNodeWithText("Stopped").assertIsDisplayed()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").assertIsDisplayed()
        composeRule.onNodeWithText("Authentication identity").assertDoesNotExist()
    }

    @Test
    fun cancelAddAccountIsOptionalAndInvokesCallback() {
        var cancelCalls = 0
        composeRule.setContent {
            MaterialTheme {
                LoginFormContent(
                    bareJid = "",
                    onBareJidChange = {},
                    password = "",
                    onPasswordChange = {},
                    advancedOpen = false,
                    onAdvancedToggle = {},
                    authenticationId = "",
                    onAuthenticationIdChange = {},
                    authorizationId = "",
                    onAuthorizationIdChange = {},
                    serviceDomain = "",
                    onServiceDomainChange = {},
                    networkHost = "",
                    onNetworkHostChange = {},
                    networkPort = "5222",
                    onNetworkPortChange = {},
                    message = null,
                    showSessionChrome = false,
                    connectionStatus = null,
                    signInEnabled = false,
                    onSignIn = {},
                    onCancelAddAccount = { cancelCalls++ },
                    onStop = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(1, cancelCalls)
        }
    }

    @Test
    fun `sign in clears password field after submit`() {
        var submitted = false
        composeRule.setContent {
            MaterialTheme {
                var password by remember { mutableStateOf("") }
                LoginFormContent(
                    bareJid = "person@example.org",
                    onBareJidChange = {},
                    password = password,
                    onPasswordChange = { password = it },
                    advancedOpen = false,
                    onAdvancedToggle = {},
                    authenticationId = "",
                    onAuthenticationIdChange = {},
                    authorizationId = "",
                    onAuthorizationIdChange = {},
                    serviceDomain = "",
                    onServiceDomainChange = {},
                    networkHost = "",
                    onNetworkHostChange = {},
                    networkPort = "5222",
                    onNetworkPortChange = {},
                    message = null,
                    showSessionChrome = false,
                    connectionStatus = null,
                    signInEnabled = password.isNotEmpty(),
                    onSignIn = {
                        submitted = true
                        password = ""
                    },
                    onStop = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithText("Password").performTextInput("secret-value")
        composeRule.onNodeWithText("Sign in").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertTrue(submitted)
        composeRule.onNodeWithText("Sign in").assertIsNotEnabled()
        composeRule.onNodeWithText("secret-value").assertDoesNotExist()
    }
}
