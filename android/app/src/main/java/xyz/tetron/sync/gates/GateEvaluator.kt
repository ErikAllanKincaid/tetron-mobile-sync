// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.gates

import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.bridge.ConnKind

/**
 * SYNC-004: the AND-logic of conditions that decide whether a run proceeds.
 * Pure function, no I/O of any kind -- callers gather [GateInputs] from
 * cheap local reads (bridge cache, `ConnectivityManager`, `BatteryManager`)
 * before ever calling this, so a blocked result is produced without any
 * network activity by construction.
 *
 * Order matters only for which single [GateReason] surfaces when more than
 * one condition fails: the implicit tunnel-active gate first (nothing else
 * is meaningful without it), then the two local device-state gates, then
 * the per-target direct-only gate last -- it is "second-stage" (spec
 * SYNC-004): only meaningful once a target/connection exists.
 */
object GateEvaluator {
    fun evaluate(inputs: GateInputs, config: GateConfig = GateConfig()): GateDecision {
        if (inputs.tunnelState != BridgeTunnelState.Active) {
            return GateDecision.Blocked(GateReason.TunnelNotActive)
        }
        if (config.wifiOnly && !inputs.isWifiConnected) {
            return GateDecision.Blocked(GateReason.NotOnWifi)
        }
        if (config.chargingRequired && !inputs.isCharging) {
            return GateDecision.Blocked(GateReason.ChargingRequired)
        }
        if (config.lowBatteryPauseEnabled && inputs.batteryPercent < config.lowBatteryThresholdPercent) {
            return GateDecision.Blocked(GateReason.LowBattery)
        }
        if (config.directOnly && inputs.targetConnKind != ConnKind.Direct) {
            return GateDecision.Blocked(GateReason.RelayOnlyPath)
        }
        return GateDecision.Allowed
    }
}
