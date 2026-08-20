// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.bridge

/**
 * SYNC-003: this app's own typed models for the MOBILE-024 mesh status
 * bridge. Callers (UI, SYNC-004 gates, SYNC-009 target picker) only ever
 * see these types, never the wire mirrors in `xyz.tetron.mobile`.
 */
enum class ConnKind {
    Direct,
    Relay,
    Tor,
    Unknown;

    companion object {
        /** MOBILE-024's stable int vocabulary: 0 Direct, 1 Relay, 2 Tor,
         *  3 Unknown. Anything else is treated as Unknown (defensive --
         *  never crash on provider drift). */
        fun fromWireInt(value: Int): ConnKind =
            when (value) {
                0 -> Direct
                1 -> Relay
                2 -> Tor
                else -> Unknown
            }
    }
}

enum class BridgeTunnelState {
    Active,
    Standby,
    Suspended,
    Reconnecting,
    NotJoined,
    CoreNotRunning,
    /** parse-fallback for a provider state this build does not know yet;
     *  gates treat anything non-Active as "tunnel not usable". */
    Unknown,
}

data class BridgePeer(
    val hostname: String?,
    val ip: String,
    val connKind: ConnKind,
)

data class BridgeSnapshot(
    val state: BridgeTunnelState,
    val network: String?,
    val ownMeshIp: String?,
    val subnet: String?,
    val peers: List<BridgePeer>,
    val updatedAtMillis: Long,
)

/** The only things the bridge can answer. The bridge never throws to UI:
 *  every failure mode collapses to [Unavailable]. */
sealed class BridgeResponse {
    data class Snapshot(val snapshot: BridgeSnapshot) : BridgeResponse()
    data class ConsentRequired(val callerPackage: String) : BridgeResponse()
    data object Unavailable : BridgeResponse()
}