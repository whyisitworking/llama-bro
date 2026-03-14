package com.suhel.llamabro.sdk.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Represents the lifecycle of an asynchronous resource load (engine or session).
 *
 * Collect a `Flow<ResourceState<T>>` to observe progress, receive the loaded resource,
 * or handle errors.
 */
sealed interface ResourceState<out T> {
    data class Loading(val progress: Float? = null) : ResourceState<Nothing>
    data class Success<T>(val value: T) : ResourceState<T>
    data class Failure(val error: LlamaError) : ResourceState<Nothing>
}

fun <T> ResourceState<T>.getOrNull(): T? =
    (this as? ResourceState.Success)?.value

inline fun <T, R> ResourceState<T>.map(mapper: (T) -> R): ResourceState<R> =
    when (this) {
        is ResourceState.Loading -> ResourceState.Loading(progress)
        is ResourceState.Success -> ResourceState.Success(mapper(value))
        is ResourceState.Failure -> ResourceState.Failure(error)
    }

inline fun <T, R> Flow<ResourceState<T>>.mapSuccess(
    crossinline mapper: suspend (T) -> R
): Flow<ResourceState<R>> =
    map { state ->
        when (state) {
            is ResourceState.Success -> ResourceState.Success(mapper(state.value))
            is ResourceState.Loading -> ResourceState.Loading(state.progress)
            is ResourceState.Failure -> ResourceState.Failure(state.error)
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T, R> Flow<ResourceState<T>>.flatMapSuccess(
    crossinline mapper: suspend (T) -> Flow<ResourceState<R>>
): Flow<ResourceState<R>> =
    flatMapLatest { state ->
        when (state) {
            is ResourceState.Success -> mapper(state.value)
            is ResourceState.Loading -> flowOf(ResourceState.Loading(state.progress))
            is ResourceState.Failure -> flowOf(ResourceState.Failure(state.error))
        }
    }

inline fun <T, R> ResourceState<T>.fold(
    onLoading: (Float?) -> R,
    onSuccess: (T) -> R,
    onFailure: (LlamaError) -> R
): R =
    when (this) {
        is ResourceState.Loading -> onLoading(progress)
        is ResourceState.Success -> onSuccess(value)
        is ResourceState.Failure -> onFailure(error)
    }

inline fun <T> ResourceState<T>.getOrElse(default: (LlamaError?) -> T): T =
    when (this) {
        is ResourceState.Success -> value
        is ResourceState.Failure -> default(error)
        is ResourceState.Loading -> default(null)
    }

inline fun <T> Flow<ResourceState<T>>.onEachSuccess(
    crossinline action: suspend (T) -> Unit
): Flow<ResourceState<T>> =
    onEach { state ->
        if (state is ResourceState.Success) {
            action(state.value)
        }
    }

fun <T> Flow<ResourceState<T>>.filterSuccess(): Flow<T> =
    filterIsInstance<ResourceState.Success<T>>()
        .map { it.value }
