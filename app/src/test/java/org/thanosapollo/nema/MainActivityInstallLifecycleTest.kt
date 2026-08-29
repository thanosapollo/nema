package org.thanosapollo.nema

import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.update.AcceptedGeneration
import org.thanosapollo.nema.update.AcceptedUpdate
import org.thanosapollo.nema.update.BoundUpdateArtifact
import org.thanosapollo.nema.update.InstallHandoffLease
import org.thanosapollo.nema.update.PackageFacts
import org.thanosapollo.nema.update.UpdateManifest
import org.thanosapollo.nema.update.UpdateState
import org.thanosapollo.nema.update.VerifiedUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityInstallLifecycleTest {
    @Test
    fun `ordinary pauses authorize no settlement`() {
        val gate = InstallResumeGate()
        gate.onPause()
        assertNull(gate.onResumeAndBeginSettlement())
    }

    @Test
    fun `pause before arm rejects lease and next resume settles nothing`() {
        val gate = InstallResumeGate()
        val expected = lease(1)

        gate.onPause()

        assertFalse(gate.arm(expected))
        assertNull(gate.onResumeAndBeginSettlement())
    }

    @Test
    fun `resume arm pause and replacement resume settle exact lease once`() {
        val gate = InstallResumeGate()
        val expected = lease(1)

        assertNull(gate.onResumeAndBeginSettlement())
        assertTrue(gate.arm(expected))
        gate.onPause()

        assertSame(expected, gate.onResumeAndBeginSettlement())
        assertNull(gate.onResumeAndBeginSettlement())
        gate.complete(expected)
        assertNull(gate.onResumeAndBeginSettlement())
    }

    @Test
    fun `gate maintains exact lease through completion and requeue`() {
        val gate = InstallResumeGate()
        val expected = lease(1)
        val wrong = lease(2)

        assertNull(gate.onResumeAndBeginSettlement())
        assertTrue(gate.arm(expected))
        gate.abort(wrong)
        gate.onPause()
        assertSame(expected, gate.onResumeAndBeginSettlement())
        assertNull(gate.onResumeAndBeginSettlement())
        gate.complete(wrong)
        gate.requeue(wrong)
        assertNull(gate.onResumeAndBeginSettlement())
        gate.requeue(expected)
        assertSame(expected, gate.onResumeAndBeginSettlement())
        gate.complete(expected)
        assertNull(gate.onResumeAndBeginSettlement())
    }

    @Test
    fun `abort identity clears exact lease from every reachable phase only`() {
        val expected = lease(1)
        val wrong = lease(2)
        fun resumedGate() = InstallResumeGate().also {
            assertNull(it.onResumeAndBeginSettlement())
        }

        val armedGate = resumedGate()
        assertTrue(armedGate.arm(expected))
        armedGate.abort(wrong)
        assertFalse(armedGate.arm(wrong))
        armedGate.abort(expected)
        assertTrue(armedGate.arm(wrong))

        val pendingGate = resumedGate()
        assertTrue(pendingGate.arm(expected))
        pendingGate.onPause()
        pendingGate.abort(wrong)
        pendingGate.abort(expected)
        assertNull(pendingGate.onResumeAndBeginSettlement())
        assertTrue(pendingGate.arm(wrong))

        val inFlightGate = resumedGate()
        assertTrue(inFlightGate.arm(expected))
        inFlightGate.onPause()
        assertSame(expected, inFlightGate.onResumeAndBeginSettlement())
        inFlightGate.abort(wrong)
        assertNull(inFlightGate.onResumeAndBeginSettlement())
        inFlightGate.abort(expected)
        assertTrue(inFlightGate.arm(wrong))
    }

    @Test
    fun `wrong identity cannot replace armed lease across executors`() {
        val gate = InstallResumeGate()
        val expected = lease(1)
        val wrong = lease(2)
        val worker = Executors.newSingleThreadExecutor()
        val lifecycle = Executors.newSingleThreadExecutor()
        try {
            assertNull(lifecycle.submit<InstallHandoffLease?> { gate.onResumeAndBeginSettlement() }.get())
            assertTrue(worker.submit<Boolean> { gate.arm(expected) }.get())
            assertFalse(worker.submit<Boolean> { gate.arm(wrong) }.get())
            val observed = lifecycle.submit<InstallHandoffLease?> {
                gate.onPause()
                gate.onResumeAndBeginSettlement()
            }.get()
            assertSame(expected, observed)
            assertNull(lifecycle.submit<InstallHandoffLease?> { gate.onResumeAndBeginSettlement() }.get())
        } finally {
            worker.shutdownNow()
            lifecycle.shutdownNow()
        }
    }

    @Test
    fun `every gate operation synchronizes the complete state machine`() {
        val operations = listOf("arm", "abort", "onPause", "onResumeAndBeginSettlement", "complete", "requeue")
        val methods = InstallResumeGate::class.java.declaredMethods.filter { it.name in operations }
        val monitorSynchronized = methods.map { it.name }.toSet() == operations.toSet() &&
            methods.all { Modifier.isSynchronized(it.modifiers) }
        val source = File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()
        val privateLock = Regex("private val \\w*[Ll]ock\\s*=\\s*Any\\(\\)").containsMatchIn(source)
        val lockSynchronized = privateLock && operations.all { operation ->
            Regex("fun\\s+$operation\\b[^=\\{]*(?:(?:=\\s*)|(?:\\{\\s*))synchronized\\s*\\(").containsMatchIn(source)
        }
        assertTrue("gate operations must share one synchronization boundary", monitorSynchronized || lockSynchronized)
    }

    @Test
    fun `activity cancellation cannot cancel process settlement`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processScope = CoroutineScope(SupervisorJob() + dispatcher)
        val activityOwner = Job()
        val activityScope = CoroutineScope(activityOwner + dispatcher)
        val expected = lease(1)
        val gate = pendingGate(expected)
        val release = CompletableDeferred<Unit>()
        val settled = mutableListOf<InstallHandoffLease>()
        val controller = InstallResumeController(processScope, gate) { exact ->
            release.await()
            settled += exact
        }

        activityScope.launch { controller.onResume() }
        runCurrent()
        activityOwner.cancel()
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(expected), settled)
        assertNull(gate.onResumeAndBeginSettlement())
        processScope.cancel()
    }

    @Test
    fun `cancelled process settlement requeues exact lease for replacement`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processScope = CoroutineScope(SupervisorJob() + dispatcher)
        val expected = lease(1)
        val gate = pendingGate(expected)
        val blocked = CompletableDeferred<Unit>()
        var firstCalls = 0
        val first = InstallResumeController(processScope, gate) {
            firstCalls++
            blocked.await()
        }

        val cancelled = first.onResume()
        runCurrent()
        assertEquals(1, firstCalls)
        cancelled?.cancel()
        runCurrent()

        val settled = mutableListOf<InstallHandoffLease>()
        val replacement = InstallResumeController(processScope, gate) { settled += it }
        replacement.onResume()
        runCurrent()
        assertEquals(listOf(expected), settled)
        assertNull(gate.onResumeAndBeginSettlement())
        processScope.cancel()
    }

    @Test
    fun `repeated resume while in flight launches no second settlement`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processScope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = pendingGate(lease(1))
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val controller = InstallResumeController(processScope, gate) {
            calls++
            release.await()
        }

        controller.onResume()
        runCurrent()
        controller.onResume()
        controller.onResume()
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        runCurrent()
        assertNull(gate.onResumeAndBeginSettlement())
        processScope.cancel()
    }

    @Test
    fun `application owns process controller and activity only signals lifecycle`() {
        val application = File("src/main/java/org/thanosapollo/nema/NemaApplication.kt").readText()
        val activity = File("src/main/java/org/thanosapollo/nema/MainActivity.kt").readText()

        assertTrue(application.contains("applicationScope"))
        assertTrue(application.contains("val installResumeGate = InstallResumeGate()"))
        assertTrue(Regex("InstallResumeController\\s*\\(\\s*applicationScope\\s*,\\s*installResumeGate").containsMatchIn(application))
        assertTrue(application.indexOf("updates = UpdateCoordinator") < application.indexOf("InstallResumeController("))
        assertTrue(application.contains("updates.settleInstallOnResume(handoff)"))
        assertEquals(1, "installResumeController\\.onResume\\(\\)".toRegex().findAll(activity).count())
        assertTrue(activity.contains("installResumeGate.onPause()"))
        assertTrue(activity.contains("gate.onResumeAndBeginSettlement()"))
        assertFalse(activity.contains("takePendingOnResume"))
        assertFalse(activity.contains("fun beginSettlement"))
        assertFalse(activity.contains("settleInstallOnResume"))
    }

    private fun pendingGate(exact: InstallHandoffLease) = InstallResumeGate().also {
        assertNull(it.onResumeAndBeginSettlement())
        assertTrue(it.arm(exact))
        it.onPause()
    }

    private fun lease(generation: Long): InstallHandoffLease {
        val manifest = UpdateManifest(1, "org.thanosapollo.nema", 3, "0.2", "https://example.test/nema.apk", 1, "a".repeat(64), "b".repeat(64), "c".repeat(40))
        val accepted = AcceptedGeneration(generation, AcceptedUpdate.Available(manifest))
        val artifact = BoundUpdateArtifact(accepted, File("candidate-$generation.apk"), 1, "a".repeat(64))
        val facts = PackageFacts("org.thanosapollo.nema", 2, setOf("b".repeat(64)), signingHistory = setOf("b".repeat(64)))
        return InstallHandoffLease(UpdateState.Verified(accepted, VerifiedUpdate(artifact, facts, facts)))
    }
}
