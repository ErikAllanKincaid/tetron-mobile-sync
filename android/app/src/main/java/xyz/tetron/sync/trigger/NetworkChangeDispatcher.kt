// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.trigger

import java.util.concurrent.Executor

/**
 * SYNC-006: what a network-becoming-available event actually does, kept
 * free of `ConnectivityManager`/`Network` so it is JVM-unit-testable. The
 * real callback ([AndroidNetworkChangeTrigger]) just calls
 * [onNetworkAvailable]. This is "an immediate check while the app happens
 * to be alive" (spec/sync.py SYNC-006) -- [executor] must not be the
 * calling (Binder callback) thread, since [runner] blocks on network I/O.
 */
class NetworkChangeDispatcher(
    private val runner: PipelineRunner,
    private val executor: Executor,
) {
    fun onNetworkAvailable() {
        executor.execute { runner.run() }
    }
}
