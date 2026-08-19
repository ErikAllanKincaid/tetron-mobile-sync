// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.bridge

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.tetron.mobile.BridgePeer as WirePeer
import xyz.tetron.mobile.BridgeTunnelState as WireTunnelState
import xyz.tetron.mobile.StatusSnapshot as WireSnapshot

/**
 * SYNC-003: the automated gate's core -- mapping the MOBILE-024 wire
 * vocabulary onto this app's own typed models, and the bridge response
 * algebra. Pure JVM tests, no Android deps. Every value the provider can
 * send must map; anything it has not been defined to send yet must fall
 * back defensively instead of crashing.
 */
class MeshBridgeMappingTest {

    @Test
    fun tunnelStateMapping_coversEveryWireState() {
        val cases =
            listOf(
                WireTunnelState.Active to BridgeTunnelState.Active,
                WireTunnelState.Standby to BridgeTunnelState.Standby,
                WireTunnelState.Suspended to BridgeTunnelState.Suspended,
                WireTunnelState.Reconnecting to BridgeTunnelState.Reconnecting,
                WireTunnelState.NotJoined to BridgeTunnelState.NotJoined,
                WireTunnelState.CoreNotRunning to BridgeTunnelState.CoreNotRunning,
                WireTunnelState.Unknown to BridgeTunnelState.Unknown,
            )
        for ((wire, app) in cases) {
            assertEquals("wire $wire must map to $app", app, wire.toAppState())
        }
    }

    @Test
    fun connKindMapping_coversWireVocabulary() {
        assertEquals(ConnKind.Direct, ConnKind.fromWireInt(0))
        assertEquals(ConnKind.Relay, ConnKind.fromWireInt(1))
        assertEquals(ConnKind.Tor, ConnKind.fromWireInt(2))
        assertEquals(ConnKind.Unknown, ConnKind.fromWireInt(3))
    }

    @Test
    fun connKindMapping_unknownIntFallsBackToUnknown() {
        assertEquals(ConnKind.Unknown, ConnKind.fromWireInt(99))
        assertEquals(ConnKind.Unknown, ConnKind.fromWireInt(-1))
    }

    @Test
    fun snapshotMapping_copiesAllFields() {
        val wire =
            WireSnapshot(
                state = WireTunnelState.Active,
                network = "wifi",
                ownMeshIp = "10.10.0.2",
                subnet = "10.10.0.0/16",
                peers =
                    listOf(
                        WirePeer(null, "10.10.0.1", 0), // Direct
                        WirePeer("home", "10.10.0.3", 3), // Unknown
                        WirePeer("relay", "10.10.1.1", 1), // Relay
                    ),
                updatedAtMillis = 123456789L,
            )

        val app = wire.toAppSnapshot()

        assertEquals(BridgeTunnelState.Active, app.state)
        assertEquals("wifi", app.network)
        assertEquals("10.10.0.2", app.ownMeshIp)
        assertEquals("10.10.0.0/16", app.subnet)
        assertEquals(123456789L, app.updatedAtMillis)
        assertEquals(3, app.peers.size)
        assertEquals(BridgePeer(null, "10.10.0.1", ConnKind.Direct), app.peers[0])
        assertEquals(BridgePeer("home", "10.10.0.3", ConnKind.Unknown), app.peers[1])
        assertEquals(BridgePeer("relay", "10.10.1.1", ConnKind.Relay), app.peers[2])
    }

    @Test
    fun snapshotMapping_copiesNullableFields() {
        val wire = WireSnapshot(WireTunnelState.CoreNotRunning, null, null, null, emptyList(), 0L)
        val app = wire.toAppSnapshot()
        assertEquals(BridgeTunnelState.CoreNotRunning, app.state)
        assertEquals(null, app.network)
        assertEquals(null, app.ownMeshIp)
        assertEquals(null, app.subnet)
        assertEquals(emptyList<BridgePeer>(), app.peers)
        assertEquals(0L, app.updatedAtMillis)
    }

    @Test
    fun statusResponse_algebra() {
        // consent-required takes precedence even if a stray snapshot
        // arrived alongside it (defensive: never trust a bundle with both).
        assertEquals(
            BridgeResponse.ConsentRequired("xyz.tetron.sync"),
            statusResponseFrom(consentRequired = true, callerPackage = "xyz.tetron.sync", wireSnapshot = null),
        )
        assertEquals(
            BridgeResponse.ConsentRequired(""),
            statusResponseFrom(consentRequired = true, callerPackage = null, wireSnapshot = null),
        )
        assertEquals(
            BridgeResponse.ConsentRequired("xyz.tetron.sync"),
            statusResponseFrom(
                consentRequired = true,
                callerPackage = "xyz.tetron.sync",
                wireSnapshot = WireSnapshot(WireTunnelState.Active, null, null, null, emptyList(), 0L),
            ),
        )

        val snapshot =
            statusResponseFrom(
                consentRequired = false,
                callerPackage = null,
                wireSnapshot = WireSnapshot(WireTunnelState.Standby, null, null, null, emptyList(), 7L),
            ) as BridgeResponse.Snapshot
        assertEquals(BridgeTunnelState.Standby, snapshot.snapshot.state)

        assertEquals(BridgeResponse.Unavailable, statusResponseFrom(false, null, null))
    }
}