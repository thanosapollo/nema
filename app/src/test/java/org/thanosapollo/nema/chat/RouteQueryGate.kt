package org.thanosapollo.nema.chat

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Holds actual Room query continuations without blocking a worker thread. */
internal class RouteQueryGate : CoroutineDispatcher() {
    private val lock = Any()
    private var held = false
    private val waiting = mutableListOf<Pair<CoroutineContext, Runnable>>()
    private var entered = CompletableDeferred<Unit>()

    fun hold(): CompletableDeferred<Unit> = synchronized(lock) {
        check(!held)
        held = true
        entered = CompletableDeferred()
        entered
    }

    fun release() {
        val tasks = synchronized(lock) {
            held = false
            waiting.toList().also { waiting.clear() }
        }
        tasks.forEach { (context, task) -> Dispatchers.IO.dispatch(context, task) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val queued = synchronized(lock) {
            if (held) {
                waiting += context to block
                entered.complete(Unit)
                true
            } else false
        }
        if (!queued) Dispatchers.IO.dispatch(context, block)
    }
}
