// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui

import xyz.tetron.sync.bridge.BridgeTunnelState
import xyz.tetron.sync.gates.GateReason

/** SYNC-009: human-readable copy for the enums SYNC-003/004 defined without
 *  UI in mind. Exact wording is explicitly an open item (spec/sync.py
 *  SYNC-009: "exact copy is implementation-time"); centralized here so
 *  Home/Progress/notifications never duplicate their own phrasing. */
fun describeTunnelState(state: BridgeTunnelState): String = when (state) {
    BridgeTunnelState.Active -> "Mesh connected"
    BridgeTunnelState.Standby -> "Mesh on standby"
    BridgeTunnelState.Suspended -> "Mesh suspended"
    BridgeTunnelState.Reconnecting -> "Mesh reconnecting…"
    BridgeTunnelState.NotJoined -> "Not joined to a mesh"
    BridgeTunnelState.CoreNotRunning -> "tetron is not running"
    BridgeTunnelState.Unknown -> "Mesh status unknown"
}

fun describeGateReason(reason: GateReason): String = when (reason) {
    GateReason.TunnelNotActive -> "The mesh tunnel is not active"
    GateReason.NotOnWifi -> "Not on Wi-Fi"
    GateReason.ChargingRequired -> "Device is not charging"
    GateReason.LowBattery -> "Battery is low"
    GateReason.RelayOnlyPath -> "Only a relay path is available to this target"
    GateReason.TargetUnreachable -> "No backup target is reachable"
}
