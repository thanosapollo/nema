package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.ui.HomeContent
import org.thanosapollo.nema.xmpp.transport.AccountId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Separate diagnostic: this slice does not change Home's structural publication. */
@Implements(value = ConversationSummary::class, isInAndroidSdk = false)
class CountHomePhotoEquality {
    @RealObject lateinit var actual: ConversationSummary
    @Implementation
    override fun equals(other: Any?): Boolean {
        if (other is ConversationSummary && actual.photoBytes != null &&
            actual.photoBytes !== other.photoBytes && actual.photoBytes.contentEquals(other.photoBytes) &&
            Looper.myLooper() == Looper.getMainLooper() && Thread.currentThread().stackTrace.any {
                it.className.contains("StateFlowImpl") && it.methodName == "updateState"
            }) equalPhotoMain.incrementAndGet()
        return Shadow.directlyOn(actual, ConversationSummary::class.java, "equals",
            ClassParameter.from(Any::class.java, other))
    }
    companion object { val equalPhotoMain = AtomicInteger() }
}

/** Measures real constructor work, not a replacement request made by the test callback. */
@Implements(value = VisibleReadRequest::class, isInAndroidSdk = false)
class CountReadRequest {
    @RealObject lateinit var actual: VisibleReadRequest

    @Implementation
    fun __constructor__(accountId: String, occurrence: ChatRouteOccurrence,
                        messageIds: Set<String>, observedTimelineIds: List<String>) {
        val idsField = if (observedTimelineIds is TimelineReadSnapshot)
            TimelineReadSnapshot::class.java.getDeclaredField("ids").apply { isAccessible = true } else null
        @Suppress("UNCHECKED_CAST")
        val source = idsField?.get(observedTimelineIds) as? List<String> ?: observedTimelineIds
        val counted = object : AbstractList<String>() {
            override val size get() = source.size
            override fun get(index: Int): String {
                if (Looper.myLooper() == Looper.getMainLooper()) copiedOnMain.incrementAndGet()
                return source[index]
            }
        }
        if (idsField != null) idsField.set(observedTimelineIds, counted)
        try {
            Shadow.invokeConstructor(VisibleReadRequest::class.java, actual,
                ClassParameter.from(String::class.java, accountId),
                ClassParameter.from(ChatRouteOccurrence::class.java, occurrence),
                ClassParameter.from(Set::class.java, messageIds),
                ClassParameter.from(List::class.java, if (idsField != null) observedTimelineIds else counted))
        } finally {
            if (idsField != null) idsField.set(observedTimelineIds, source)
        }
    }

    companion object { val copiedOnMain = AtomicInteger() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [CountReadRequest::class, CountHomePhotoEquality::class], instrumentedPackages = ["org.thanosapollo.nema.chat"])
class ConsumedReadAdmissionTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun settledVisibilityDoesNotCopyOrScanHistoryOnMain() = admission(512)
    @Test fun largerHistoryDoesNotIncreaseMainAdmissionWork() = admission(2_048)

    private fun admission(historySize: Int) {
        val sql = CopyOnWriteArrayList<String>()
        val roomThread = Executors.newSingleThreadExecutor { Thread(it, "read-admission-room") }.asCoroutineDispatcher()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NemaDatabase::class.java)
            .setQueryCoroutineContext(roomThread)
            .setQueryCallback({ query, _ -> sql.add(query) }, java.util.concurrent.Executor { it.run() })
            .build()
        val repository = ChatRepository(db)
        val account = "admission-account"
        val peer = "peer@example.org"
        val scope = CoroutineScope(SupervisorJob() + android.os.Handler(Looper.getMainLooper()).asCoroutineDispatcher("admission-main"))
        lateinit var presenter: DirectChatPresenter
        var presenterCreated = false
        val resumed = mutableStateOf(false)
        val completions = AtomicInteger()
        val scannedOnMain = AtomicInteger()
        val scannedOffMain = AtomicInteger()
        val requests = CopyOnWriteArrayList<VisibleReadRequest>()
        val homes = CopyOnWriteArrayList<List<ConversationSummary>>()
        val histories = CopyOnWriteArrayList<List<TimelineMessage>>()
        try {
            runBlocking {
                db.accountDao().upsert(AccountEntity(account, "self@example.org", "self", null, "example.org", null, null))
                db.withTransaction {
                    repeat(historySize) { index ->
                        MessageStore(db).ingest(IncomingMessage(
                            accountId = account, localMessageId = "row-$index", peerJid = peer, senderJid = peer,
                            direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT,
                            threadId = null, parentThreadId = null, body = "body $index", archiveOrdinal = null, aliases = emptyList(),
                        ))
                    }
                }
                db.messageDao().upsertPeer(PeerEntity(account, peer, photoBytes = ByteArray(16_384) { 7 }, photoMime = "image/png"))
                repository.saveDraft(DirectConversationKey(account, peer), "keep draft")
            }
            presenter = DirectChatPresenter(
                AccountConfiguration.create(AccountId.require(account), "self@example.org", "self", null, "example.org", null),
                repository, scope, enqueue = { _, _ -> error("network forbidden") },
            )
            presenterCreated = true
            compose.runOnUiThread { presenter.selectNotificationDestination(peer, null) }
            compose.setContent {
                val state by presenter.state.collectAsState()
                androidx.compose.runtime.LaunchedEffect(presenter) {
                    var previous: DirectChatState? = null
                    presenter.state.collect { published ->
                        if (previous?.conversations !== published.conversations) homes.add(published.conversations)
                        if (previous?.messages !== published.messages) histories.add(published.messages)
                        previous = published
                    }
                }
                MaterialTheme { Box {
                    HomeContent(state.conversations, state.conversationsReady, "Offline", "Self", onSelectPeer = { true })
                    if (state.contentStatus == ChatContentStatus.Ready && state.messages.size == historySize) {
                        MessageTimeline(state.messages, accountId = state.accountId, routeOccurrence = state.routeOccurrence,
                            activityResumed = resumed.value, readReceiptsEnabled = false,
                            onMarkVisibleRead = { request ->
                                assertSame("real Compose Main", Looper.getMainLooper(), Looper.myLooper())
                                requests.add(request)
                                val snapshot = presenter.state.value
                                val original = snapshot.messages
                                val counted = object : AbstractList<TimelineMessage>() {
                                    override val size get() = original.size
                                    override fun get(index: Int): TimelineMessage {
                                        if (Thread.currentThread().stackTrace.any {
                                            it.methodName.startsWith("markVisibleConversationRead") ||
                                                it.className.contains("markVisibleConversationRead")
                                        }) {
                                            if (Looper.myLooper() == Looper.getMainLooper()) scannedOnMain.incrementAndGet()
                                            else scannedOffMain.incrementAndGet()
                                        }
                                        return original[index]
                                    }
                                }
                                // Preserve the real Room projection and actual presenter; count only its admission traversal.
                                val field = DirectChatState::class.java.getDeclaredField("messages").apply { isAccessible = true }
                                field.set(snapshot, counted)
                                try {
                                    presenter.markVisibleConversationRead(request).also { if (it) completions.incrementAndGet() }
                                } finally { field.set(snapshot, original) }
                            }, modifier = Modifier.height(300.dp))
                    }
                } }
            }
            compose.waitUntil(15_000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); presenter.state.value.messages.size == historySize }
            compose.runOnIdle { resumed.value = true }
            compose.waitUntil(15_000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); completions.get() >= 1 && presenter.state.value.conversations.singleOrNull()?.unreadCount == 0 }
            compose.waitForIdle()
            val before = completions.get()
            compose.runOnIdle { resumed.value = false }
            compose.waitForIdle()
            sql.clear(); homes.clear(); histories.clear()
            CountReadRequest.copiedOnMain.set(0); scannedOnMain.set(0); scannedOffMain.set(0)
            CountHomePhotoEquality.equalPhotoMain.set(0)
            // Settled control admission, then an explicit viewport-change admission.
            compose.runOnIdle { resumed.value = true }
            compose.waitUntil(15_000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); completions.get() > before }
            compose.onNodeWithTag("message-timeline").performScrollToIndex(50)
            compose.waitUntil(15_000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); completions.get() > before + 1 }
            compose.waitForIdle()
            val copied = CountReadRequest.copiedOnMain.get()
            val scanned = scannedOnMain.get()
            println("READ_ADMISSION history=$historySize completed=${completions.get() - before} requestCopyMain=$copied presenterScanMain=$scanned presenterScanOffMain=${scannedOffMain.get()} updates=${sql.count { it.trimStart().startsWith("UPDATE", true) }} selects=${sql.count { it.trimStart().startsWith("SELECT", true) }} homePublications=${homes.size} historyPublications=${histories.size}")
            println("SQL_DIAGNOSTIC " + sql.groupingBy { it.trim().replace(Regex("\\s+"), " ").take(240) }.eachCount())
            println("HOME_DIAGNOSTIC equalDistinctPhotoComparisonsInMainStateFlow=${CountHomePhotoEquality.equalPhotoMain.get()}")
            assertTrue("actual worker preparation traversed captured history", scannedOffMain.get() >= historySize)
            assertEquals("keep draft", presenter.state.value.draft)
            assertEquals(true, runBlocking { db.messageDao().message(account, "row-0") }?.locallyRead)
            assertTrue("actual callback admitted only message rows", requests.all { it.messageIds.all { id -> id.startsWith("row-") } })
            assertTrue("requestCopyMain=$copied presenterScanMain=$scanned history=$historySize", copied < historySize && scanned < historySize)
        } finally {
            compose.runOnUiThread { if (presenterCreated) presenter.close(); scope.cancel() }
            try {
                compose.waitUntil(5_000) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    scope.coroutineContext[Job]!!.isCompleted
                }
            } finally { db.close(); roomThread.close() }
        }
    }
}
