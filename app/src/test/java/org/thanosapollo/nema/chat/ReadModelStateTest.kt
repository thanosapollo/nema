package org.thanosapollo.nema.chat

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

class ReadModelStateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun equalHistoryRetainsPublishedIdentityAndRetirementCancelsPendingComparison() = runTest {
        val workerScheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(workerScheduler)
        val job = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext.minusKey(TestCoroutineScheduler) + job)
        val input = Channel<List<String>>(Channel.UNLIMITED)
        val state = input.receiveAsFlow().stateInReadModel(scope, null, worker)
        runCurrent()
        input.send(listOf("original"))
        runCurrent()
        assertEquals("must await worker comparison", null, state.value.value)
        workerScheduler.runCurrent()
        runCurrent()
        val original = state.value
        assertEquals(listOf("original"), original.value)
        input.send(arrayListOf("original"))
        runCurrent()
        workerScheduler.runCurrent()
        runCurrent()
        assertSame("structurally equal history must not emit a new identity", original, state.value)
        input.send(listOf("edited"))
        runCurrent()
        workerScheduler.runCurrent()
        runCurrent()
        val edited = state.value
        assertNotSame(original, edited)
        assertEquals(listOf("edited"), edited.value)
        input.send(emptyList())
        runCurrent()
        job.cancel()
        workerScheduler.runCurrent()
        runCurrent()
        job.join()
        assertSame("retirement must not publish the pending history", edited, state.value)
        input.close()
    }

    @Test
    fun structuralComparisonRunsOffConsumerThreadAndChangedSnapshotsReachSlowCollector() {
        Executors.newSingleThreadExecutor { Thread(it, "read-model-main") }.asCoroutineDispatcher().use { main ->
            Executors.newSingleThreadExecutor { Thread(it, "read-model-worker") }.asCoroutineDispatcher().use { worker ->
                runBlocking(main) {
                    withTimeout(5_000) {
                        val job = Job(coroutineContext[Job])
                        val scope = CoroutineScope(main + job)
                        val input = Channel<Payload>(Channel.UNLIMITED)
                        val comparisons = Channel<Unit>(Channel.UNLIMITED)
                        val consumed = Channel<ReadModelSnapshot<Payload?>>(Channel.UNLIMITED)
                        val resume = Channel<Unit>()
                        fun payload(body: String) = Payload(CheckedList(listOf(body), comparisons))
                        val state = input.receiveAsFlow().stateInReadModel(scope, null, worker)
                        val collector = scope.launch {
                            state.collect {
                                assertEquals("read-model-main", Thread.currentThread().name.substringBefore(" @"))
                                consumed.send(it)
                                resume.receive()
                            }
                        }
                        try {
                            assertEquals(null, consumed.receive().value)

                            resume.send(Unit)
                            input.send(payload("original"))
                            val original = consumed.receive()

                            assertEquals("original", original.value!!.messages.single())
                            // The Main collector is parked with the original snapshot. An equal
                            // but newly allocated history must retain that exact published value.
                            input.send(payload("original"))
                            comparisons.receive()

                            input.send(payload("edited"))
                            val edited = state.first { it.value?.messages?.single() == "edited" }

                            comparisons.receive()
                            assertNotSame(original, edited)
                            // StateFlow's consumer-side equality now runs on Main. Unwrapping
                            // before sharing or only moving stateIn to worker fails this guard.
                            resume.send(Unit)
                            assertSame(edited, consumed.receive())

                            input.send(payload("edited"))
                            comparisons.receive()

                            // A following distinct value is a completion barrier for the equal
                            // comparison; no sleep or elapsed-time assertion is involved.
                            input.send(payload("next"))
                            val next = state.first { it.value?.messages?.single() == "next" }
                            comparisons.receive()
                            resume.send(Unit)
                            assertSame(next, consumed.receive())

                            assertEquals("next", next.value!!.messages.single())
                        } finally {
                            scope.cancel()
                            collector.join()
                            job.join()
                            input.close()
                        }
                    }
                }
            }
        }
    }

    private data class Payload(val messages: List<String>)

    private class CheckedList(
        private val values: List<String>,
        private val comparisons: Channel<Unit>,
    ) : AbstractList<String>() {
        override val size get() = values.size
        override fun get(index: Int) = values[index]
        override fun equals(other: Any?): Boolean {
            assertEquals("read-model-worker", Thread.currentThread().name.substringBefore(" @"))
            return super.equals(other).also { comparisons.trySend(Unit).getOrThrow() }
        }
        override fun hashCode(): Int = values.hashCode()
    }
}
