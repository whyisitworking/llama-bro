package com.suhel.llamabro.sdk.model

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
