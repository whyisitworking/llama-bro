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
    public constructor(name: String, context: Context) : this(name, { NsdAdvertiser(context) })

    override fun toString(): String = "Advertise($name)"
}

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
        // the callbacks report the name the network gave, or a failure; neither changes what the server does
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Registration {
            // unregistering a listener whose registration has not completed throws; there is nothing to undo then
            runCatching { manager.unregisterService(listener) }
        }
    }
}
