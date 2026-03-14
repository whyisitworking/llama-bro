package com.suhel.llamabro.demo.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

inline fun <T, U, R> Flow<T>.withLatestFrom(
    other: Flow<U>,
    crossinline transform: suspend (T, U) -> R
): Flow<R> = channelFlow {
    var latest: U? = null

    launch {
        other.collect { latest = it }
    }

    collect { value ->
        latest?.let {
            send(transform(value, it))
        }
    }
}
