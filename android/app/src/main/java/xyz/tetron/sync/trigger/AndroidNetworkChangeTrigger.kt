// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import android.net.ConnectivityManager
import android.net.Network

/**
 * SYNC-006: the real network-change trigger -- a `ConnectivityManager
 * .NetworkCallback` registered only while some app component is alive
 * (spec/sync.py: "not a wake-up mechanism"; deliberately no manifest
 * `BroadcastReceiver`). Callers own the lifecycle: pair [register] with
 * [unregister] (e.g. an Activity/Application's onStart/onStop). No logic
 * of its own -- delegates immediately to [NetworkChangeDispatcher], which
 * carries the actual (tested) behavior -- same untested-wrapper bar as
 * [xyz.tetron.sync.bridge.ProviderStatusCaller].
 */
class AndroidNetworkChangeTrigger(
    private val connectivityManager: ConnectivityManager,
    private val dispatcher: NetworkChangeDispatcher,
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = dispatcher.onNetworkAvailable()
    }

    fun register() {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(callback)
    }
}
