package com.suhel.llamabro.demo.server

import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.settings.LoadConfig
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.server.ModelUnavailable
import com.suhel.llamabro.server.ServedLease
import com.suhel.llamabro.server.ServedModel
import com.suhel.llamabro.server.ServedModels

/**
 * The one model server mode serves, [key], listed by its `HfModel` string. Each request holds its own [ModelHost]
 * lease under the serving [config], sharing the load whatever its config (`keepConfig`), so a request never reloads
 * the model; [created] is Unix seconds.
 */
class HostServedModels(
    private val host: ModelHost,
    private val key: HfModel,
    private val config: LoadConfig,
    private val created: Long,
) : ServedModels {
    private val id = key.toString()

    override suspend fun list(): List<ServedModel> = listOf(ServedModel(id, created))

    override suspend fun acquire(id: String?): ServedLease {
        if (id != null && id != this.id) throw ModelUnavailable(id)
        val lease = host.hold(key, config) { true }
        return object : ServedLease {
            override val model: Model = lease.model

            override fun release() = lease.release()
        }
    }
}
