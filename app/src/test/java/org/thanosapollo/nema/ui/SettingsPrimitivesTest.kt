package org.thanosapollo.nema.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsPrimitivesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsProfileTopBarLeavesStatusBarInsetsToItsCaller() {
        val source = File(
            "src/main/java/org/thanosapollo/nema/ui/SettingsPrimitives.kt",
        ).readText()
        val screen = source
            .substringAfter("fun SettingsProfileScreen(")
            .substringBefore("fun SettingsSectionHeader(")

        assertTrue(
            "SettingsProfileScreen TopAppBar must not apply status-bar insets twice",
            screen.contains("windowInsets = WindowInsets(0, 0, 0, 0),"),
        )
    }

    @Test
    fun screenOwnsTitleBackAffordanceAndTaggedLazyListShell() {
        var backCalls = 0
        composeRule.setContent {
            MaterialTheme {
                SettingsProfileScreen(
                    title = "Profile",
                    onBack = { backCalls++ },
                    listTag = "profile-list",
                ) {
                    item { SettingsRow(title = "Profile row") }
                }
            }
        }

        composeRule.onAllNodesWithText("Profile").assertCountEquals(1)
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-list").performScrollToIndex(0)
        composeRule.onNodeWithText("Profile row").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backCalls)
    }

    @Test
    fun sharedHeaderAndRowsKeepTagsGeometryAndClickFence() {
        var enabledCalls = 0
        var disabledCalls = 0
        composeRule.setContent {
            MaterialTheme {
                SettingsProfileScreen(title = "Settings") {
                    item {
                        SettingsSectionHeader(
                            title = "Section",
                            modifier = Modifier.testTag("shared-section"),
                        )
                    }
                    item {
                        SettingsRow(
                            title = "Enabled row",
                            supportingText = "Supporting text",
                            onClick = { enabledCalls++ },
                            modifier = Modifier.testTag("enabled-row"),
                        )
                    }
                    item {
                        SettingsRow(
                            title = "Disabled row",
                            enabled = false,
                            onClick = { disabledCalls++ },
                            modifier = Modifier.testTag("disabled-row"),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        composeRule.onNodeWithTag("shared-section").assertIsDisplayed()
        composeRule.onNodeWithTag("enabled-row")
            .assertHeightIsAtLeast(64.dp)
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag("disabled-row")
            .assertHeightIsAtLeast(64.dp)
            .assertIsNotEnabled()
            .assertHasNoClickAction()
        assertEquals(1, enabledCalls)
        assertEquals(0, disabledCalls)
    }
}
