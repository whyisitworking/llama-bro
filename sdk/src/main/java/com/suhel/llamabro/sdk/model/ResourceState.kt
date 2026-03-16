package com.suhel.llamabro.sdk.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Represents the lifecycle of an asynchronous resource load (e.g., [com.suhel.llamabro.sdk.LlamaEngine]
 * or [com.suhel.llamabro.sdk.LlamaSession]).
 *
 * Use this to observe progress and handle the success or failure of resource allocation.
 */
sealed interface ResourceState<out T> {
    /** 
     * The resource is currently being loaded or allocated. 
     * @param progress Optional progress value between 0.0 and 1.0.
     */
    data class Loading(val progress: Float? = null) : ResourceState<Nothing>
    
    /** 
     * The resource was successfully loaded. 
     * @param value The loaded resource instance.
     */
    data class Success<T>(val value: T) : ResourceState<T>
    
    /** 
     * An error occurred during loading. 
     * @param error The [LlamaError] describing what went wrong.
     */
    data class Failure(val error: LlamaError) : ResourceState<Nothing>
}

/** Returns the success value if this state is [ResourceState.Success], otherwise null. */
fun <T> ResourceState<T>.getOrNull(): T? =
    (this as? ResourceState.Success)?.value

/** Maps the success value to a new type while preserving [Loading] and [Failure] states. */
inline fun <T, R> ResourceState<T>.map(mapper: (T) -> R): ResourceState<R> =
    when (this) {
        is ResourceState.Loading -> ResourceState.Loading(progress)
        is ResourceState.Success -> ResourceState.Success(mapper(value))
        is ResourceState.Failure -> ResourceState.Failure(error)
    }

/** 
 * Suspends and maps the success value within a [Flow]. 
 * Useful for chaining resource loads or performing side effects on a loaded resource.
 */
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

/** 
 * Flat-maps the success value into another [ResourceState] flow. 
 * Ideal for sequential resource initialization (e.g., Engine -> Session).
 */
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

/** Standard functional fold for handling all [ResourceState] branches. */
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

/** Returns the success value or a default value provided by the lambda. */
inline fun <T> ResourceState<T>.getOrElse(default: (LlamaError?) -> T): T =
    when (this) {
        is ResourceState.Success -> value
        is ResourceState.Failure -> default(error)
        is ResourceState.Loading -> default(null)
    }

/** Executes an action only when the state is [ResourceState.Success]. */
inline fun <T> Flow<ResourceState<T>>.onEachSuccess(
    crossinline action: suspend (T) -> Unit
): Flow<ResourceState<T>> =
    onEach { state ->
        if (state is ResourceState.Success) {
            action(state.value)
        }
    }

/** Executes an action only when the state is [ResourceState.Loading]. */
inline fun <T> Flow<ResourceState<T>>.onEachLoading(
    crossinline action: suspend (Float?) -> Unit
): Flow<ResourceState<T>> =
    onEach { state ->
        if (state is ResourceState.Loading) {
            action(state.progress)
        }
    }

/** Filters a [Flow<ResourceState<T>>] to only emit the successful values. */
fun <T> Flow<ResourceState<T>>.filterSuccess(): Flow<T> =
    filterIsInstance<ResourceState.Success<T>>()
        .map { it.value }
