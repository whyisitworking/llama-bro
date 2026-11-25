package com.suhel.llamabro.demo.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * What the Server page and the serving notification say about the phone: the IPv4 [addresses] a computer on the same
 * Wi-Fi (or Ethernet) can reach it on, empty when it is offline, and whether it is [charging]. Serving goes on when
 * the network goes: the server is bound to every address, so the addresses only change what is shown.
 *
 * The values are fed by [watch]; the transforms from the system's types are the functions below, which are pure.
 */
class NetworkState {
    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> = _addresses.asStateFlow()

    private val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    internal fun setAddresses(value: List<String>) {
        _addresses.value = value
    }

    internal fun setCharging(value: Boolean) {
        _charging.value = value
    }

    /** Follows the default network and the battery from now on; the returned handle stops it. */
    fun watch(context: Context): AutoCloseable {
        val app = context.applicationContext
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = Callback()
        connectivity.registerDefaultNetworkCallback(network)
        val battery =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
            }
        // the battery broadcast is sticky: registering returns its latest value now and delivers each change after
        ContextCompat
            .registerReceiver(
                app,
                battery,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )?.let(::readBattery)
        return AutoCloseable {
            connectivity.unregisterNetworkCallback(network)
            app.unregisterReceiver(battery)
        }
    }

    private fun readBattery(intent: Intent) {
        setCharging(isCharging(intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)))
    }

    /** The default network's changes. Its properties and capabilities arrive separately, so each is kept. */
    private inner class Callback : ConnectivityManager.NetworkCallback() {
        private var local = false
        private var found: List<InetAddress> = emptyList()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            local = carriesLocalNetwork(capabilities::hasTransport)
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            found = properties.linkAddresses.map { it.address }
            publish()
        }

        override fun onLost(network: Network) {
            local = false
            found = emptyList()
            publish()
        }

        private fun publish() = setAddresses(reachableAddresses(found, local))
    }
}

/** [addresses]' IPv4 ones that a computer on the same network could use, when that network is [wifiOrEthernet]. */
internal fun reachableAddresses(addresses: List<InetAddress>, wifiOrEthernet: Boolean): List<String> =
    if (!wifiOrEthernet) {
        emptyList()
    } else {
        addresses
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    }

/** Whether a network with these transports is one the phone's neighbours are on: Wi-Fi or Ethernet. */
internal fun carriesLocalNetwork(hasTransport: (Int) -> Boolean): Boolean =
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

/** [BatteryManager.EXTRA_STATUS]: charging, or full on the charger. */
internal fun isCharging(status: Int): Boolean =
    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
