package com.suhel.llamabro.demo.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    /** Follows the Wi-Fi and Ethernet networks and the battery from now on; the returned handle stops it. */
    fun watch(context: Context): AutoCloseable {
        val app = context.applicationContext
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val network = Callback()
        // not the default network: a LAN without internet is not the default while cellular is. The request asks for
        // no internet capability, which the builder would otherwise leave required
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        connectivity.registerNetworkCallback(request, network)
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

    /**
     * Each Wi-Fi or Ethernet network's addresses, kept per network: a LAN with no internet is listed even when
     * cellular is the default network, and two networks at once are both listed.
     */
    private inner class Callback : ConnectivityManager.NetworkCallback() {
        private var book = AddressBook<Network>()

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            book = book.with(network, reachableAddresses(properties.linkAddresses.map { it.address }, true))
            setAddresses(book.merged())
        }

        override fun onLost(network: Network) {
            book = book.without(network)
            setAddresses(book.merged())
        }
    }
}

/** The addresses of each network by key, merged into the one sorted, de-duplicated list the page shows. */
internal class AddressBook<K>(private val byNetwork: Map<K, List<String>> = emptyMap()) {
    fun with(key: K, addresses: List<String>) = AddressBook(byNetwork + (key to addresses))

    fun without(key: K) = AddressBook(byNetwork - key)

    fun merged(): List<String> =
        byNetwork.values
            .flatten()
            .distinct()
            .sorted()
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
