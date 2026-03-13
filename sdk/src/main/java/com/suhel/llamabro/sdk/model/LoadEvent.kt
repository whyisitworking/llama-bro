package com.suhel.llamabro.sdk.model

/**
 * Represents the lifecycle of an asynchronous resource load (engine or session).
 *
 * Collect a `Flow<LoadEvent<T>>` to observe progress, receive the loaded resource,
 * or handle errors.
 */
sealed interface LoadEvent<out T> {
    data class Loading(val progress: Float? = null) : LoadEvent<Nothing>
    data class Ready<T>(val resource: T) : LoadEvent<T>
    data class Error(val error: LlamaError) : LoadEvent<Nothing>
}

fun <T> LoadEvent<T>.getOrNull(): T? =
    (this as? LoadEvent.Ready)?.resource

fun <T, R> LoadEvent<T>.map(mapper: (T) -> R): LoadEvent<R> =
    when (this) {
        is LoadEvent.Loading -> LoadEvent.Loading(progress)
        is LoadEvent.Ready -> LoadEvent.Ready(mapper(resource))
        is LoadEvent.Error -> LoadEvent.Error(error)
    }
