// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.pipeline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** SYNC-005: the production [DeviceStateProvider] -- thin `ConnectivityManager`/
 *  `BatteryManager` reads, matching SYNC-004's gate spec ("Wi-Fi only --
 *  `ConnectivityManager` `TRANSPORT_WIFI` check", never a network-type
 *  heuristic beyond that single check). No logic of its own, so no unit
 *  test (same bar as [xyz.tetron.sync.bridge.ProviderStatusCaller]); real
 *  behavior is verified on-device (SYNC-011). */
class AndroidDeviceStateProvider(context: Context) : DeviceStateProvider {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    override fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun isCharging(): Boolean =
        batteryManager.isCharging

    override fun batteryPercent(): Int =
        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
