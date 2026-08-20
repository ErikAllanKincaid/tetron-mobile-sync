// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SYNC-003: the TTL cache keeps UI polling from hammering the provider.
 * Pure JVM test with a fake [StatusCaller] and an injected clock -- no
 * Android deps. Every response kind, including ConsentRequired and
 * Unavailable, must be cached for the TTL.
 */
class MeshBridgeCacheTest {

    private class FakeClock {
        var now: Long = 0
        fun current(): Long = now
    }

    @Test
    fun withinTtl_servesCachedResponseWithoutRefetch() {
        var calls = 0
        val caller = StatusCaller {
            calls++
            BridgeResponse.Unavailable
        }
        val clock = FakeClock()
        val bridge = MeshBridge(caller, cacheTtlMillis = 10_000, nowMillis = clock::current)

        val first = bridge.current()
        assertEquals(1, calls)

        clock.now += 9_999
        assertEquals(first, bridge.current())
        assertEquals("within TTL the caller must not be re-invoked", 1, calls)
    }

    @Test
    fun afterTtl_fetchesFreshResponse() {
        var calls = 0
        val caller = StatusCaller {
            calls++
            BridgeResponse.Unavailable
        }
        val clock = FakeClock()
        val bridge = MeshBridge(caller, cacheTtlMillis = 10_000, nowMillis = clock::current)

        bridge.current()
        clock.now += 10_000
        bridge.current()
        assertEquals("at/after TTL the caller must be re-invoked", 2, calls)
    }

    @Test
    fun cachesConsentRequiredForTtl() {
        var calls = 0
        val caller = StatusCaller {
            calls++
            BridgeResponse.ConsentRequired("xyz.tetron.sync")
        }
        val clock = FakeClock()
        val bridge = MeshBridge(caller, cacheTtlMillis = 5_000, nowMillis = clock::current)

        assertEquals(BridgeResponse.ConsentRequired("xyz.tetron.sync"), bridge.current())
        clock.now += 5_000 - 1
        assertEquals(BridgeResponse.ConsentRequired("xyz.tetron.sync"), bridge.current())
        assertEquals(1, calls)
    }

    @Test
    fun cachesSnapshotForTtl() {
        var calls = 0
        val caller =
            StatusCaller {
                calls++
                BridgeResponse.Snapshot(
                    BridgeSnapshot(BridgeTunnelState.Active, null, null, null, emptyList(), 1L),
                )
            }
        val clock = FakeClock()
        val bridge = MeshBridge(caller, cacheTtlMillis = 5_000, nowMillis = clock::current)

        assertEquals(BridgeResponse.Snapshot(BridgeSnapshot(BridgeTunnelState.Active, null, null, null, emptyList(), 1L)), bridge.current())
        clock.now += 1_000
        assertEquals(BridgeResponse.Snapshot(BridgeSnapshot(BridgeTunnelState.Active, null, null, null, emptyList(), 1L)), bridge.current())
        assertEquals(1, calls)
    }

    @Test
    fun zeroTtlAlwaysFetches() {
        var calls = 0
        val caller = StatusCaller {
            calls++
            BridgeResponse.Unavailable
        }
        val clock = FakeClock()
        val bridge = MeshBridge(caller, cacheTtlMillis = 0, nowMillis = clock::current)

        bridge.current()
        bridge.current()
        assertEquals(2, calls)
    }
}