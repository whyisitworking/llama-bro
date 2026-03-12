package com.suhel.llamabro.sdk.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

inline fun <T : AutoCloseable> resourceFlow(
    crossinline acquire: () -> T
): Flow<T> = flow {
    val res = acquire()

    try {
        emit(res)
    } finally {
        res.close()
    }
}.flowOn(Dispatchers.IO)
