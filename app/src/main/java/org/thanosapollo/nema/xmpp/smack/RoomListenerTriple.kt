package org.thanosapollo.nema.xmpp.smack

import java.util.concurrent.atomic.AtomicBoolean

internal class RoomListenerTriple<S, P, U> private constructor(
    private val status: S,
    private val participant: P,
    private val subject: U,
    private val removeStatus: (S) -> Unit,
    private val removeParticipant: (P) -> Unit,
    private val removeSubject: (U) -> Unit,
) {
    private val cleaned = AtomicBoolean(false)

    fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        bestEffort(
            { removeSubject(subject) },
            { removeParticipant(participant) },
            { removeStatus(status) },
        )
    }

    companion object {
        fun <S, P, U> install(
            status: S,
            participant: P,
            subject: U,
            addStatus: (S) -> Boolean,
            addParticipant: (P) -> Boolean,
            addSubject: (U) -> Boolean,
            removeStatus: (S) -> Unit,
            removeParticipant: (P) -> Unit,
            removeSubject: (U) -> Unit,
        ): RoomListenerTriple<S, P, U>? {
            var installed = 0
            try {
                if (!addStatus(status)) return null
                installed++
                if (!addParticipant(participant)) return rollback(installed, status, participant, subject, removeStatus, removeParticipant, removeSubject)
                installed++
                if (!addSubject(subject)) return rollback(installed, status, participant, subject, removeStatus, removeParticipant, removeSubject)
                return RoomListenerTriple(status, participant, subject, removeStatus, removeParticipant, removeSubject)
            } catch (failure: Throwable) {
                rollback(installed, status, participant, subject, removeStatus, removeParticipant, removeSubject)
                throw failure
            }
        }

        private fun <S, P, U> rollback(
            installed: Int,
            status: S,
            participant: P,
            subject: U,
            removeStatus: (S) -> Unit,
            removeParticipant: (P) -> Unit,
            removeSubject: (U) -> Unit,
        ): RoomListenerTriple<S, P, U>? {
            val removals = listOf(
                (installed >= 3) to { removeSubject(subject) },
                (installed >= 2) to { removeParticipant(participant) },
                (installed >= 1) to { removeStatus(status) },
            )
            bestEffort(*removals.filter { it.first }.map { it.second }.toTypedArray())
            return null
        }

        private fun bestEffort(vararg removals: () -> Unit) {
            removals.forEach { removal -> runCatching(removal) }
        }
    }
}
