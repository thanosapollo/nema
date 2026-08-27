package org.thanosapollo.nema.xmpp.smack

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.jivesoftware.smackx.muc.UserStatusListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

class RoomStatusHandoffTest {
    private val first = attempt(1)
    @Test
    fun `failed and stale candidates remove only their listener`() {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(first) }
        val listeners = RecordingListeners()
        val handoff = RoomStatusHandoff(Any(), registry, onCurrentRevoked = {})
        val firstCandidate = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        assertTrue(handoff.publish(firstCandidate, FEATURES))
        val failed = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        handoff.discard(failed)
        assertSame(firstCandidate.lease, registry.snapshot(first, ROOM)?.lease)
        assertEquals(setOf(firstCandidate.listener), listeners.live)
        val stale = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        registry.begin(attempt(2))
        handoff.discard(stale)
        assertEquals(setOf(firstCandidate.listener), listeners.live)
    }
    @Test
    fun `pending candidate revocation prevents publish and preserves current`() {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(first) }
        val listeners = RecordingListeners()
        var currentRevocations = 0
        val handoff = RoomStatusHandoff(Any(), registry, onCurrentRevoked = { currentRevocations += 1 })
        val firstCandidate = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        assertTrue(handoff.publish(firstCandidate, FEATURES))
        val pending = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        pending.listener.membershipRevoked()
        assertFalse(handoff.publish(pending, FEATURES))
        assertSame(firstCandidate.lease, registry.snapshot(first, ROOM)?.lease)
        assertEquals(setOf(firstCandidate.listener), listeners.live)
        assertEquals(0, currentRevocations)
    }
    @Test
    fun `revocation cannot enter between publication and activation`() {
        val published = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val revocationEntered = CountDownLatch(1)
        val releaseRevocation = CountDownLatch(1)
        val registry = RoomStableIdAuthorityRegistry().apply { begin(first) }
        val listeners = RecordingListeners()
        val handoff = RoomStatusHandoff(Any(), registry, {}, beforeRevocation = {
            revocationEntered.countDown()
            releaseRevocation.await(5, TimeUnit.SECONDS)
        })
        val old = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        assertTrue(handoff.publish(old, FEATURES))
        val replacement = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        val result = AtomicReference<Boolean>()
        val publisher = thread {
            result.set(handoff.publish(replacement, FEATURES) {
                published.countDown()
                releaseActivation.await(5, TimeUnit.SECONDS)
            })
        }
        assertTrue(published.await(5, TimeUnit.SECONDS))
        val revokerStarted = CountDownLatch(1)
        val revoker = thread { revokerStarted.countDown(); replacement.listener.membershipRevoked() }
        assertTrue(revokerStarted.await(5, TimeUnit.SECONDS))
        assertFalse(revocationEntered.await(200, TimeUnit.MILLISECONDS))
        releaseActivation.countDown()
        assertTrue(revocationEntered.await(5, TimeUnit.SECONDS))
        assertSame(replacement.lease, registry.snapshot(first, ROOM)?.lease)
        assertEquals(setOf(replacement.listener), listeners.live)
        releaseRevocation.countDown()
        publisher.join(5_000)
        revoker.join(5_000)
        assertEquals(true, result.get())
        assertNull(registry.snapshot(first, ROOM))
        assertTrue(listeners.live.isEmpty())
    }

    @Test
    fun `successful handoff leaves one listener and stale callback is inert`() {
        val registry = RoomStableIdAuthorityRegistry().apply { begin(first) }
        val listeners = RecordingListeners()
        val handoff = RoomStatusHandoff(Any(), registry, onCurrentRevoked = {})
        val old = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        assertTrue(handoff.publish(old, FEATURES))
        val replacement = listeners.candidate(handoff, requireNotNull(registry.beginJoin(first, ROOM)))
        assertTrue(handoff.publish(replacement, FEATURES))
        old.listener.membershipRevoked()
        assertSame(replacement.lease, registry.snapshot(first, ROOM)?.lease)
        assertEquals(setOf(replacement.listener), listeners.live)
    }

    private class RecordingListeners {
        val live = linkedSetOf<UserStatusListener>()
        fun candidate(handoff: RoomStatusHandoff, lease: RoomStableIdLease) =
            handoff.candidate(lease, live::add, live::remove)
    }

    private companion object {
        const val ROOM = "room@conference.example.org"
        val FEATURES = RoomFeatureSupport(stableIds = true, occupantIds = true)

        fun attempt(value: Long) = SessionAttemptIdentity(
            AccountId.require("account"), ConnectionGeneration.require(1),
            ConnectionAttempt.require(value), LifecycleEpoch.require(1),
        )
    }
}
