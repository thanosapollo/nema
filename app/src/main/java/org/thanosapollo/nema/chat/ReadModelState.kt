package org.thanosapollo.nema.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

// Identity is safe only after structural distinctness has been established off Main.
// StateFlow compares both when publishing AND when each collector consumes a value;
// moving only the sharing coroutine off Main does not protect Main collectors.
internal class ReadModelSnapshot<T>(val value: T)

internal fun <T> Flow<T>.stateInReadModel(
    scope: CoroutineScope,
    initialValue: T,
    comparisonDispatcher: CoroutineDispatcher = Dispatchers.Default,
): StateFlow<ReadModelSnapshot<T>> {
    val initial = ReadModelSnapshot(initialValue)
    return flow {
        var previous = initial
        collect { value ->
            val next = withContext(comparisonDispatcher) {
                if (previous.value == value) null else ReadModelSnapshot(value)
            }
            if (next != null) {
                previous = next
                emit(next)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, initial)
}
