// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.bridge

import xyz.tetron.mobile.BridgeTunnelState as WireTunnelState
import xyz.tetron.mobile.StatusSnapshot as WireSnapshot

/** Wire -> app-vocabulary mapping. All pure functions, JVM-unit-tested
 *  (MeshBridgeMappingTest). Never throws: unknown inputs fall back. */
internal fun WireTunnelState.toAppState(): BridgeTunnelState =
    when (this) {
        WireTunnelState.Active -> BridgeTunnelState.Active
        WireTunnelState.Standby -> BridgeTunnelState.Standby
        WireTunnelState.Suspended -> BridgeTunnelState.Suspended
        WireTunnelState.Reconnecting -> BridgeTunnelState.Reconnecting
        WireTunnelState.NotJoined -> BridgeTunnelState.NotJoined
        WireTunnelState.CoreNotRunning -> BridgeTunnelState.CoreNotRunning
        WireTunnelState.Unknown -> BridgeTunnelState.Unknown
    }

internal fun WireSnapshot.toAppSnapshot(): BridgeSnapshot =
    BridgeSnapshot(
        state = state.toAppState(),
        network = network,
        ownMeshIp = ownMeshIp,
        subnet = subnet,
        peers = peers.map { BridgePeer(hostname = it.hostname, ip = it.ip, connKind = ConnKind.fromWireInt(it.connKind)) },
        updatedAtMillis = updatedAtMillis,
    )

/** Decides what a provider-returned bundle means. Consent-required wins
 *  even if a stray snapshot arrived alongside it. */
internal fun statusResponseFrom(
    consentRequired: Boolean,
    callerPackage: String?,
    wireSnapshot: WireSnapshot?,
): BridgeResponse =
    when {
        consentRequired -> BridgeResponse.ConsentRequired(callerPackage ?: "")
        wireSnapshot != null -> BridgeResponse.Snapshot(wireSnapshot.toAppSnapshot())
        else -> BridgeResponse.Unavailable
    }