package org.thanosapollo.nema

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.android.controller.ActivityController
import org.thanosapollo.nema.account.LoginFormInput
import org.thanosapollo.nema.chat.ChatRoute
import org.thanosapollo.nema.service.XmppConnectionService
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@Config(sdk = [34], application = NemaApplication::class)
class MessageNotificationTapTest {
    @get:Rule val compose = createEmptyComposeRule()

    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun provideUnusedTestKeystore() {
            // Only construction is needed. No credential is stored or transport started.
            java.security.Security.addProvider(object : java.security.Provider("NemaTest", 1.0, "JVM test only") {
                init { put("KeyStore.AndroidKeyStore", "com.sun.crypto.provider.JceKeyStore") }
            })
        }

        @JvmStatic
        @org.junit.AfterClass
        fun removeTestKeystore() { java.security.Security.removeProvider("NemaTest") }
    }

    private val app get() = ApplicationProvider.getApplicationContext<NemaApplication>()
    private val accountId = AccountId.require("account-a")

    private fun seed() {
        val account = LoginFormInput("me@example.org").toConfiguration(accountId)
        runBlocking {
            AccountRepository(app.database.accountDao()).apply { save(account); activate(account.id) }
        }
        app.sessionRuntime.claimAutomaticConnectionStart()
    }

    // Literal external contract, not the producer/helper: proves Activity + Compose consume it.
    private fun tap(peer: String = "peer@example.org", owner: String = accountId.value) =
        Intent(app, MainActivity::class.java)
            .setAction("org.thanosapollo.nema.action.OPEN_MESSAGE")
            .putExtra(XmppConnectionService.EXTRA_ACCOUNT_ID, owner)
            .putExtra(XmppConnectionService.EXTRA_PEER_JID, peer)

    private fun launch(intent: Intent = tap(), state: Bundle = Bundle()): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).create(state).start().resume().visible()

    private fun awaitPeer(peer: String?) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            app.sessionRuntime.visiblePeer.get() == peer
        }
        compose.waitForIdle()
        assertEquals(peer, app.sessionRuntime.visiblePeer.get())
    }

    private fun awaitFeedback() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Message not opened").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Message not opened").assertExists()
    }

    private fun destroy(controller: ActivityController<MainActivity>) { controller.pause().stop().destroy() }

    @Test
    fun coldNotificationOpensNotifiedConversationInRealActivity() {
        seed()
        val controller = launch()
        try { awaitPeer("peer@example.org") } finally { destroy(controller) }
    }

    @Test
    fun warmTapReplacesRouteAndBackDoesNotReplayAfterRecreation() {
        seed()
        val controller = launch()
        try {
            awaitPeer("peer@example.org")
            controller.newIntent(tap("other@example.org"))
            awaitPeer("other@example.org")
            controller.get().onBackPressedDispatcher.onBackPressed()
            awaitPeer(null)
            val saved = Bundle()
            controller.saveInstanceState(saved)
            // Recreate with the original launch Intent, as Android can retain it.
            destroy(controller)
            val recreated = launch(tap(), saved)
            try {
                compose.waitForIdle()
                awaitPeer(null)
                assertNull(MessageNotificationTarget.fromIntent(recreated.get().intent))
                recreated.newIntent(tap("other@example.org"))
                awaitPeer("other@example.org")
            } finally { destroy(recreated) }
        } finally {
            if (!controller.get().isDestroyed) destroy(controller)
        }
    }

    @Test
    fun pendingTapSurvivesRecreationBeforeCompositionConsumesIt() {
        seed()
        val first = Robolectric.buildActivity(MainActivity::class.java, tap()).create(Bundle())
        val saved = Bundle()
        first.saveInstanceState(saved).destroy()
        val second = launch(Intent(app, MainActivity::class.java), saved)
        try { awaitPeer("peer@example.org") } finally { destroy(second) }
    }

    @Test
    fun latestWarmTapWinsAndOldSavedRouteCannotOverrideIt() {
        seed()
        runBlocking { app.chatRepository.saveRoute(accountId.value, ChatRoute("old@example.org")) }
        val saved = Bundle().apply { putString("nema.process-token", app.processToken) }
        val controller = launch(tap(), saved)
        controller.newIntent(tap("newest@example.org"))
        try {
            awaitPeer("newest@example.org")
            compose.waitUntil(5_000) {
                runBlocking { app.chatRepository.observeRoute(accountId.value).first()?.peerJid == "newest@example.org" }
            }
        } finally { destroy(controller) }
    }

    @Test
    fun wrongAccountSamePeerShowsFeedbackAndNeverSwitches() {
        seed()
        runBlocking {
            AccountRepository(app.database.accountDao()).save(
                LoginFormInput("other@example.org").toConfiguration(AccountId.require("account-b")),
            )
        }
        val controller = launch()
        try {
            awaitPeer("peer@example.org")
            controller.newIntent(tap(owner = "account-b"))
            awaitFeedback()
            assertEquals(accountId, runBlocking { app.sessionRuntime.activeAccount.first() }?.id)
            assertEquals("peer@example.org", app.sessionRuntime.visiblePeer.get())
            compose.onNodeWithText("OK").performClick()
            controller.get().onBackPressedDispatcher.onBackPressed()
            awaitPeer(null)
        } finally { destroy(controller) }
    }

    @Test
    fun coldWrongAccountTapNeverOpensPeerOrActivatesItsOwner() {
        seed()
        val controller = launch(tap(owner = "account-b"))
        try {
            awaitFeedback()
            assertNull(app.sessionRuntime.visiblePeer.get())
            assertEquals(accountId, runBlocking { app.sessionRuntime.activeAccount.first() }?.id)
            assertNull(MessageNotificationTarget.fromIntent(controller.get().intent))
        } finally { destroy(controller) }
    }

    @Test
    fun absentAccountIsResolvedRatherThanWaitingForever() {
        app.sessionRuntime.claimAutomaticConnectionStart()
        val controller = launch()
        try {
            awaitFeedback()
            assertNull(runBlocking { app.sessionRuntime.activeAccount.first() })
            assertNull(app.sessionRuntime.visiblePeer.get())
        } finally { destroy(controller) }
    }

    @Test
    fun malformedAndLegacyIntentsDoNotNavigateOrReplaceCurrentRoute() {
        seed()
        val controller = launch()
        try {
            awaitPeer("peer@example.org")
            val invalid = listOf(
                Intent(app, MainActivity::class.java).putExtra("peer_jid", "other@example.org"),
                tap().removeOwner(), tap(peer = "not a jid"), tap(peer = "peer@example.org/resource"),
                tap(owner = ""), tap(owner = "bad\naccount"), tap(owner = "a".repeat(1025)),
                tap().putExtra("peer_jid", 42), tap().putExtra("account_id", 42),
                tap().apply { removeExtra("peer_jid") }, tap().setAction(Intent.ACTION_VIEW),
            )
            invalid.forEach {
                controller.newIntent(it)
                compose.waitForIdle()
                assertEquals("peer@example.org", app.sessionRuntime.visiblePeer.get())
            }
        } finally { destroy(controller) }
    }

    @Test
    fun pendingIdentitySeparatesAccountsAndCollidingPeersWithoutRetargeting() {
        val targets = listOf(
            MessageNotificationTarget("account-a", "an@example.org"),
            MessageNotificationTarget("account-b", "an@example.org"),
            MessageNotificationTarget("account-a", "c0@example.org"),
        )
        assertEquals(targets[0].peerJid.hashCode(), targets[2].peerJid.hashCode())
        val pending = targets.map { it.pendingIntent(app) }
        assertEquals(3, pending.toSet().size)
        assertEquals(3, targets.map { it.notificationTag }.toSet().size)
        targets.forEachIndexed { index, target ->
            assertEquals(pending[index], target.pendingIntent(app))
            assertTrue(pending[index].isImmutable)
            pending[index].send()
            val delivered = shadowOf(app).nextStartedActivity
            assertEquals(target, MessageNotificationTarget.fromIntent(delivered))
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                delivered.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun Intent.removeOwner() = apply { removeExtra("account_id") }
}
