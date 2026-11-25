package com.suhel.llamabro.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Announces the server on the local network, so a laptop finds it as `<name>.local`: `ServerConfig { advertise =
 * Advertise("Llama Bro", context) }`. The server registers `_http._tcp` and `_llamabro._tcp` while it serves, with
 * TXT `path=/v1` and `auth=key|none`. A network that refuses the registration leaves the server serving, unannounced.
 *
 * This is Android API, so it stays at the edge (docs/design/server.md).
 */
public class Advertise internal constructor(public val name: String, internal val registry: () -> ServiceRegistry) {
    // the application context, never an Activity: the config outlives screens
    public constructor(name: String, context: Context) : this(name, advertiserOf(context.applicationContext))

    override fun toString(): String = "Advertise($name)"
}

private fun advertiserOf(applicationContext: Context): () -> ServiceRegistry = { NsdAdvertiser(applicationContext) }

/** What the server announces itself through; [NsdAdvertiser] on a device, a fake in the tests. */
internal interface ServiceRegistry {
    fun register(name: String, type: String, port: Int, txt: Map<String, String>): Registration
}

internal fun interface Registration {
    fun unregister()
}

/** [ServiceRegistry] over [NsdManager]: registering needs no permission beyond the INTERNET the server has. */
internal class NsdAdvertiser(context: Context) : ServiceRegistry {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)

    override fun register(name: String, type: String, port: Int, txt: Map<String, String>): Registration {
        val info =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = type
                this.port = port
                txt.forEach { (key, value) -> setAttribute(key, value) }
            }
        lateinit var listener: NsdManager.RegistrationListener
        val lifecycle = RegistrationLifecycle { runCatching { manager.unregisterService(listener) } }
        listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = lifecycle.registered()

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = lifecycle.failed()

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Registration(lifecycle::stop)
    }
}

/**
 * One registration's life, driven by NsdManager's callbacks: NSD refuses to unregister a service it hasn't confirmed,
 * so a stop that comes first is remembered and carried out when the confirmation arrives.
 */
internal class RegistrationLifecycle(private val withdraw: () -> Unit) {
    private enum class State { Pending, Registered, Done }

    private var state = State.Pending
    private var stopRequested = false

    @Synchronized
    fun registered() {
        if (state != State.Pending) return
        if (stopRequested) {
            state = State.Done
            withdraw()
        } else {
            state = State.Registered
        }
    }

    @Synchronized
    fun failed() {
        state = State.Done
    }

    @Synchronized
    fun stop() {
        when (state) {
            State.Pending -> {
                stopRequested = true
            }

            State.Registered -> {
                state = State.Done
                withdraw()
            }

            State.Done -> {
                Unit
            }
        }
    }
}
