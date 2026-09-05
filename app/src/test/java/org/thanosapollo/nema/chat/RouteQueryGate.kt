package org.thanosapollo.nema.chat

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Holds actual Room query continuations without blocking a worker thread. */
internal class RouteQueryGate(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localQueryOnly: Boolean = false,
) : CoroutineDispatcher() {
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
        tasks.forEach { (context, task) -> dispatcher.dispatch(context, task) }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val queued = synchronized(lock) {
            // Timeline/recent-thread queries enter from their Default flowOn workers.
            // Draft/list queries on the controlled presenter dispatcher remain independent.
            if (held && (!localQueryOnly || Thread.currentThread().name.startsWith("DefaultDispatcher-worker"))) {
                waiting += context to block
                entered.complete(Unit)
                true
            } else false
        }
        if (!queued) dispatcher.dispatch(context, block)
    }
}
