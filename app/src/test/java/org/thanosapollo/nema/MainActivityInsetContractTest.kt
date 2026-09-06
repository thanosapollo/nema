package org.thanosapollo.nema

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.ui.PrimaryDestination

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityInsetContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sessionScaffoldIsTheOnlyBottomBarOwner() {
        val activity = java.io.File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()
        val home = java.io.File("src/main/java/org/thanosapollo/nema/ui/HomeContent.kt").readText()

        val bottomBar = activity.substringAfter("bottomBar = {").substringBefore(") { contentPadding ->")
        assertTrue(bottomBar.contains("ArchiveStatusBanner("))
        assertTrue(bottomBar.contains("SessionBottomBar("))
        assertEquals(1, Regex("SessionBottomBar\\(").findAll(activity).count())
        assertTrue(activity.contains("onAccepted = { destination = PrimaryDestination.HOME }"))
        assertFalse(activity.contains("onAccepted = { selectDestination(PrimaryDestination.HOME) }"))
        assertFalse(home.contains("SessionBottomBar("))
    }

    @Test
    fun sessionScaffoldPaddingIsConsumedBeforeScreenInsets() {
        val activity = java.io.File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()
        val ownedInsets = Regex(
            """\.padding\(contentPadding\)\s*\.consumeWindowInsets\(contentPadding\)\s*\.statusBarsPadding\(\)""",
        )

        assertEquals(3, ownedInsets.findAll(activity).count())
    }

    @Test
    fun chatRouteRestoresOnlyWithinSameProcess() {
        assertEquals(false, shouldRestoreChatRoute(null, "process-b"))
        assertEquals(false, shouldRestoreChatRoute("process-a", "process-b"))
        assertEquals(true, shouldRestoreChatRoute("process-a", "process-a"))
    }

    @Test
    fun topDestinationRestoresWithinSameProcess() {
        val restorationTester = StateRestorationTester(composeRule)
        val processToken = "process-a"
        lateinit var destination: MutableState<PrimaryDestination>
        restorationTester.setContent {
            destination = rememberPrimaryDestination(processToken)
        }

        composeRule.runOnIdle { destination.value = PrimaryDestination.ROSTER }
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(PrimaryDestination.ROSTER, destination.value) }
    }

    @Test
    fun topDestinationFromDeadProcessStartsHome() {
        val restorationTester = StateRestorationTester(composeRule)
        var processToken = "process-a"
        lateinit var destination: MutableState<PrimaryDestination>
        restorationTester.setContent {
            destination = rememberPrimaryDestination(processToken)
        }

        composeRule.runOnIdle { destination.value = PrimaryDestination.ROSTER }
        processToken += "-replacement"
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(PrimaryDestination.HOME, destination.value) }
    }

    @Test
    fun shareIntentUsesConversationsCompatibleXmppUri() {
        val intent = xmppShareIntent("#fdroid%irc?room.example@irc.example")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(
            "xmpp:%23fdroid%25irc%3Froom.example@irc.example",
            intent.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertEquals(
            "#fdroid%irc?room.example@irc.example",
            Uri.parse(intent.getStringExtra(Intent.EXTRA_TEXT)).schemeSpecificPart,
        )
    }

    @Test
    fun mainActivityResizesForIme() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activityInfo.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }
}
