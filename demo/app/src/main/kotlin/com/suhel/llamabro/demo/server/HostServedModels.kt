package com.suhel.llamabro.demo.server

import com.suhel.llamabro.Model
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.server.ServedLease
import com.suhel.llamabro.server.ServedModel
import com.suhel.llamabro.server.ServedModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one model server mode serves, [key], listed by its `HfModel` string; [created] is Unix seconds. Every request is
 * answered by [model], whatever its `model` field names, as upstream's llama-server answers with one model loaded:
 * Claude Code and other clients send their own model's name (D164). [model] is the serving lease's, so a request never
 * touches `ModelHost`: a per-request hold would start a new load whenever the host has let go of the served model (a
 * delete), and wait behind the serving lease for ever. The requests in flight are counted instead, so serving releases
 * its lease only once they have ended ([drained]).
 */
class HostServedModels(private val model: Model, key: HfModel, private val created: Long) : ServedModels {
    private val id = key.toString()
    private val inFlight = MutableStateFlow(0)

    override suspend fun list(): List<ServedModel> = listOf(ServedModel(id, created))

    override suspend fun acquire(id: String?): ServedLease {
        inFlight.update { it + 1 }
        val released = AtomicBoolean(false)
        return object : ServedLease {
            override val model: Model = this@HostServedModels.model

            override fun release() {
                if (released.compareAndSet(false, true)) inFlight.update { it - 1 }
            }
        }
    }

    /** Returns once no request holds the model. */
    suspend fun drained() {
        inFlight.first { it == 0 }
    }
}
