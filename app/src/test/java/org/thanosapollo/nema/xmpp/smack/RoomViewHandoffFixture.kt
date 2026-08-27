package org.thanosapollo.nema.xmpp.smack

import java.util.concurrent.CountDownLatch

internal class RoomViewHandoffFixture(
    private val failAt: Operation? = null,
    private val blockAt: Set<Gate> = emptySet(),
) {
    data class Listener(val name: String)

    enum class Operation(val label: String) {
        ADD("add"), REMOVE("remove"), EVENT("event"),
    }

    enum class Gate(val label: String) {
        MUC("muc"), ORDERING("ordering"), ACTIVATION("activation"),
        CALLBACK("callback"), RESET("reset"),
    }

    val entryLock = Any()
    val log = mutableListOf<String>()
    val entered = Gate.entries.associateWith { CountDownLatch(1) }
    val release = Gate.entries.associateWith { CountDownLatch(1) }

    fun listeners(candidate: String) = listOf("status", "participant", "subject")
        .map { Listener("$candidate:$it") }

    fun effect(operation: Operation, listener: String) {
        log += "${operation.label}:$listener"
        if (failAt == operation) {
            log += "fail:${operation.label}"
            throw IllegalStateException(operation.label)
        }
    }

    fun entryProbe() {
        log += if (Thread.holdsLock(entryLock)) "entry:held" else "entry:free"
    }

    fun reach(gate: Gate) {
        log += "reach:${gate.label}"
        if (gate in blockAt) {
            entered.getValue(gate).countDown()
            release.getValue(gate).await()
        }
        log += "pass:${gate.label}"
    }
}
