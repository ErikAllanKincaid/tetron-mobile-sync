// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.gates

import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.bridge.ConnKind

/**
 * SYNC-004: reasons a run can be gated. [TargetUnreachable] is not produced
 * by [GateEvaluator] -- it is reserved for SYNC-005's pipeline, which is the
 * only place an actual connection attempt happens; it exists here so the
 * coalescing window (decision #3) covers all six reasons from one enum.
 */
enum class GateReason {
    TunnelNotActive,
    NotOnWifi,
    ChargingRequired,
    LowBattery,
    RelayOnlyPath,
    TargetUnreachable,
}

/**
 * All-configurable, defaults from the decision register (spec/sync.py
 * SYNC-004): Wi-Fi-only and direct-only ON, charging-required OFF,
 * low-battery pause ON at ~20%.
 */
data class GateConfig(
    val wifiOnly: Boolean = true,
    val directOnly: Boolean = true,
    val chargingRequired: Boolean = false,
    val lowBatteryPauseEnabled: Boolean = true,
    val lowBatteryThresholdPercent: Int = 20,
)

/**
 * Cheap-local-first inputs: [tunnelState]/[targetConnKind] come from the
 * SYNC-003 bridge's cached snapshot (no provider poll triggered by gating);
 * the rest come from local `ConnectivityManager`/`BatteryManager` reads.
 * None of these require network activity to produce.
 */
data class GateInputs(
    val tunnelState: BridgeTunnelState,
    val isWifiConnected: Boolean,
    val isCharging: Boolean,
    val batteryPercent: Int,
    val targetConnKind: ConnKind?,
)

sealed class GateDecision {
    data object Allowed : GateDecision()
    data class Blocked(val reason: GateReason) : GateDecision()
}

/**
 * SYNC-009: the config knob a manual "Transfer anyway?" override relaxes
 * for exactly the one gate that blocked -- never a blanket bypass, and
 * never more than the single reason the caller is overriding. `null` when
 * [reason] has no corresponding [GateConfig] field: [GateReason
 * .TunnelNotActive] (nothing is running to connect to) and [GateReason
 * .TargetUnreachable] (no target/source resolved) are not policy gates, so
 * there is nothing to relax -- those two never become overridable
 * regardless of what a caller asks for.
 */
fun relaxedGateConfig(reason: GateReason, config: GateConfig): GateConfig? = when (reason) {
    GateReason.NotOnWifi -> config.copy(wifiOnly = false)
    GateReason.ChargingRequired -> config.copy(chargingRequired = false)
    GateReason.LowBattery -> config.copy(lowBatteryPauseEnabled = false)
    GateReason.RelayOnlyPath -> config.copy(directOnly = false)
    GateReason.TunnelNotActive, GateReason.TargetUnreachable -> null
}
