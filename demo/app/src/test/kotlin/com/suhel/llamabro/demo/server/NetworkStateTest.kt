package com.suhel.llamabro.demo.server

import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The transforms behind [NetworkState]: which addresses a phone can be reached on, and whether it is charging. */
class NetworkStateTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test fun onlyIpv4AddressesAreKept() {
        val all = listOf(ip("fe80::1"), ip("192.168.1.20"), ip("2001:db8::5"))
        assertEquals(listOf("192.168.1.20"), reachableAddresses(all, wifiOrEthernet = true))
    }

    @Test fun loopbackAndLinkLocalAreLeftOut() {
        val all = listOf(ip("127.0.0.1"), ip("169.254.3.4"), ip("10.0.0.7"))
        assertEquals(listOf("10.0.0.7"), reachableAddresses(all, wifiOrEthernet = true))
    }

    @Test fun cellularHasNoAddressesToServeOn() {
        assertEquals(emptyList<String>(), reachableAddresses(listOf(ip("10.1.2.3")), wifiOrEthernet = false))
    }

    @Test fun noNetworkHasNoAddresses() {
        assertEquals(emptyList<String>(), reachableAddresses(emptyList(), wifiOrEthernet = true))
    }

    @Test fun theOrderTheNetworkGaveIsKept() {
        val all = listOf(ip("192.168.1.20"), ip("10.0.0.7"))
        assertEquals(listOf("192.168.1.20", "10.0.0.7"), reachableAddresses(all, wifiOrEthernet = true))
    }

    @Test fun wifiAndEthernetCount() {
        assertTrue(carriesLocalNetwork { it == NetworkCapabilities.TRANSPORT_WIFI })
        assertTrue(carriesLocalNetwork { it == NetworkCapabilities.TRANSPORT_ETHERNET })
        assertFalse(carriesLocalNetwork { it == NetworkCapabilities.TRANSPORT_CELLULAR })
        assertFalse(carriesLocalNetwork { false })
    }

    @Test fun chargingStatusesCharge() {
        assertTrue(isCharging(BatteryManager.BATTERY_STATUS_CHARGING))
        assertTrue(isCharging(BatteryManager.BATTERY_STATUS_FULL))
        assertFalse(isCharging(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertFalse(isCharging(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertFalse(isCharging(BatteryManager.BATTERY_STATUS_UNKNOWN))
    }

    @Test fun startsOfflineAndNotCharging() {
        val state = NetworkState()
        assertEquals(emptyList<String>(), state.addresses.value)
        assertFalse(state.charging.value)
    }

    @Test fun lanOnlyWifiWhileCellularIsDefaultStillListed() {
        // the book holds the Wi-Fi's addresses whatever the default network is; cellular never enters it
        val book = AddressBook<String>().with("wifi", listOf("192.168.8.2"))
        assertEquals(listOf("192.168.8.2"), book.merged())
    }

    @Test fun lostNetworkDropsItsAddresses() {
        val book = AddressBook<String>().with("wifi", listOf("192.168.8.2")).with("eth", listOf("10.0.0.5"))
        assertEquals(listOf("10.0.0.5"), book.without("wifi").merged())
        assertEquals(emptyList<String>(), book.without("wifi").without("eth").merged())
    }

    @Test fun twoNetworksMerge() {
        val book =
            AddressBook<String>()
                .with("wifi", listOf("192.168.1.20", "10.0.0.5"))
                .with("eth", listOf("10.0.0.5", "172.16.0.9"))
        assertEquals(listOf("10.0.0.5", "172.16.0.9", "192.168.1.20"), book.merged())
        // a network's later properties replace its earlier ones
        assertEquals(listOf("10.0.0.5", "172.16.0.9"), book.with("wifi", listOf("10.0.0.5")).merged())
    }
}
