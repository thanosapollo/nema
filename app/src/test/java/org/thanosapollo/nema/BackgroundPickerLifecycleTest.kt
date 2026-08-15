package org.thanosapollo.nema

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.ui.theme.AppearanceScope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundPickerLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingPickerResultRetainsExactScopeAcrossStateRestorationAndIsConsumedOnce() {
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var pending: MutableState<AppearanceScope?>
        val delivered = mutableListOf<AppearanceScope>()
        restorationTester.setContent {
            pending = rememberPendingBackgroundScope()
        }
        val scopes = listOf(
            AppearanceScope.App,
            AppearanceScope.Account("account-a"),
            AppearanceScope.Conversation("account-b", "peer@example.org"),
        )

        scopes.forEach { expected ->
            composeRule.runOnIdle { pending.value = expected }
            restorationTester.emulateSavedInstanceStateRestore()
            composeRule.runOnIdle {
                consumeBackgroundSelection(pending, Uri.parse("content://provider/background.png")) { scope, _ ->
                    delivered += scope
                }
                consumeBackgroundSelection(pending, Uri.parse("content://provider/duplicate.png")) { scope, _ ->
                    delivered += scope
                }
            }
        }

        assertEquals(scopes, delivered)
    }

    @Test
    fun absentOrMalformedRestoredOwnerNeverRetargetsResult() {
        assertNull(restoreBackgroundScope(emptyList<Any>()))
        assertNull(restoreBackgroundScope(listOf("account", "")))
        assertNull(restoreBackgroundScope(listOf("conversation", "account-only")))
        assertNull(restoreBackgroundScope(listOf("unknown", "account", "peer")))

        lateinit var pending: MutableState<AppearanceScope?>
        var deliveries = 0
        composeRule.setContent { pending = rememberPendingBackgroundScope() }
        composeRule.runOnIdle {
            consumeBackgroundSelection(pending, Uri.parse("content://provider/background.png")) { _, _ ->
                deliveries++
            }
        }

        assertEquals(0, deliveries)
    }
}
